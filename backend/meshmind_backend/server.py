"""MeshMind backend: enrollment (phone verification → certificates), answers (search + AI) and the live-info board.

Stdlib HTTP server, no framework: run it on a laptop for a demo, or behind HTTPS (Cloud Run, a tunnel, nginx)
for anything real. Phones talk to it only through Internet-connected gateways, or directly while online to enroll.

    python -m meshmind_backend.server --port 8787
"""

from __future__ import annotations

import argparse
import base64
import collections
import hashlib
import json
import logging
import os
import re
import secrets
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlparse

from .authority import Authority
from .bulletin import BulletinBoard
from .canon import normalize_query
from .gemini import GeminiProvider
from .providers import AnswerProvider, ClaudeProvider, DemoProvider, read_page

log = logging.getLogger("meshmind")

ROLES = {"USER", "ORGANIZER", "RESPONDER"}
NODE_ID = re.compile(r"^[A-Za-z0-9_]{1,12}-[A-Za-z0-9]{1,8}$|^[A-Za-z0-9_-]{1,24}$")
PHONE = re.compile(r"^\+?[0-9]{10,15}$")


def now_ms() -> int:
    return int(time.time() * 1000)


class ApiError(Exception):
    def __init__(self, status: int, message: str) -> None:
        super().__init__(message)
        self.status = status


class RateLimiter:
    def __init__(self) -> None:
        self._hits: dict[str, collections.deque] = collections.defaultdict(collections.deque)
        self._lock = threading.Lock()

    def allow(self, key: str, limit: int, window_s: float = 60.0) -> bool:
        t = time.time()
        with self._lock:
            q = self._hits[key]
            while q and t - q[0] >= window_s:
                q.popleft()
            if len(q) >= limit:
                return False
            q.append(t)
            return True


class Backend:
    def __init__(self, data_dir: Path, provider: AnswerProvider, board: BulletinBoard, authority: Authority,
                 demo_otp: bool, organizer_code: str, admin_token: str, log_queries: bool, retention_days: int) -> None:
        self.data_dir = data_dir
        self.provider = provider
        self.board = board
        self.authority = authority
        self.demo_otp = demo_otp
        self.organizer_code = organizer_code
        self.admin_token = admin_token
        self.log_queries = log_queries
        self.limits = RateLimiter()
        self._otps: dict[str, tuple[str, float, int]] = {}
        self._answers: dict[str, dict] = {}
        self._lock = threading.Lock()
        self.registry_path = data_dir / "registry.json"
        self.audit_path = data_dir / "audit.jsonl"
        self.registry: dict = json.loads(self.registry_path.read_text()) if self.registry_path.exists() else {}
        self._prune_audit(retention_days)

    # ---- accountability ------------------------------------------------------------------------

    def audit(self, event: str, **fields) -> None:
        """Append-only request log kept on the backend (who asked for what kind of thing, when)."""
        entry = {"ts": now_ms(), "event": event, **fields}
        with self._lock, self.audit_path.open("a", encoding="utf-8") as f:
            f.write(json.dumps(entry, ensure_ascii=False) + "\n")

    def _prune_audit(self, days: int) -> None:
        if not self.audit_path.exists():
            return
        cutoff = now_ms() - days * 86_400_000
        keep = [ln for ln in self.audit_path.read_text(encoding="utf-8").splitlines()
                if ln and json.loads(ln).get("ts", 0) >= cutoff]
        self.audit_path.write_text("\n".join(keep) + ("\n" if keep else ""), encoding="utf-8")

    # ---- enrollment ------------------------------------------------------------------------------

    def enroll_start(self, body: dict, client: str) -> dict:
        phone = str(body.get("phone", "")).replace(" ", "")
        if not PHONE.match(phone):
            raise ApiError(400, "phone number must be 10-15 digits, optionally with +country code")
        if not self.limits.allow("otp:" + phone, 3, 600) or not self.limits.allow("otp-ip:" + client, 10, 600):
            raise ApiError(429, "too many codes requested; wait a few minutes")
        code = f"{secrets.randbelow(1_000_000):06d}"
        with self._lock:
            self._otps[phone] = (code, time.time() + 300, 0)
        self.audit("otp_sent", phone=phone)
        if self.demo_otp:
            log.warning("DEMO OTP for %s: %s", phone, code)
            return {"ok": True, "demoCode": code, "message": "demo mode: code returned directly (no SMS sent)"}
        # Production: send `code` by SMS through your provider (MSG91, Twilio, ...) here.
        raise ApiError(501, "SMS delivery is not configured on this backend; start it with --demo-otp for demos")

    def enroll_verify(self, body: dict) -> dict:
        phone = str(body.get("phone", "")).replace(" ", "")
        code = str(body.get("code", ""))
        node_id = str(body.get("nodeId", ""))
        role = str(body.get("role", "USER")).upper()
        if not NODE_ID.match(node_id):
            raise ApiError(400, "invalid node id")
        if role not in ROLES:
            raise ApiError(400, "invalid role")
        try:
            public_key = base64.b64decode(str(body.get("publicKey", "")), validate=True)
        except Exception:
            raise ApiError(400, "publicKey must be base64")
        with self._lock:
            entry = self._otps.get(phone)
            if not entry or entry[1] < time.time():
                raise ApiError(400, "code expired or never requested")
            if entry[2] >= 5:
                raise ApiError(429, "too many wrong codes; request a new one")
            if not secrets.compare_digest(entry[0], code):
                self._otps[phone] = (entry[0], entry[1], entry[2] + 1)
                raise ApiError(400, "wrong code")
            del self._otps[phone]
        if role != "USER" and not secrets.compare_digest(str(body.get("organizerCode", "")), self.organizer_code):
            raise ApiError(403, f"{role} needs the event's organizer code")
        with self._lock:
            owner = self.registry.get(node_id)
            if owner and owner["phone"] != phone:
                raise ApiError(409, "that node name is registered to another phone; rename and try again")
        try:
            cert = self.authority.issue_certificate(node_id, public_key, role, now_ms())
        except ValueError as e:
            raise ApiError(400, str(e))
        with self._lock:
            self.registry[node_id] = {"phone": phone, "role": role, "fingerprint": hashlib.sha256(public_key).digest()[:8].hex(),
                                      "issuedAt": cert["issuedAt"]}
            self.registry_path.write_text(json.dumps(self.registry, indent=1))
        self.audit("enrolled", nodeId=node_id, phone=phone, role=role)
        return {"certificate": cert, "authorityKey": base64.b64encode(self.authority.public_key).decode(),
                "message": f"verified as {role.lower()}"}

    # ---- answers ---------------------------------------------------------------------------------

    def ask(self, body: dict) -> dict:
        query = str(body.get("query", "")).strip()
        requester = str(body.get("requester", ""))[:40]
        if not query or len(query) > 300:
            raise ApiError(400, "query must be 1-300 characters")
        if not self.limits.allow("ask:" + requester, 20) or not self.limits.allow("ask:all", 240):
            raise ApiError(429, "backend busy; try again shortly")
        key = normalize_query(query)
        with self._lock:
            hit = self._answers.get(key)
        if hit and hit["expiresAt"] > now_ms():
            self.audit("ask", requester=requester, cached=True, **self._query_fields(query))
            return hit
        t0 = time.time()
        try:
            pa = self.provider.answer(query)
        except Exception as e:
            log.exception("provider failed")
            raise ApiError(502, f"answer provider failed: {type(e).__name__}")
        now = now_ms()
        answer = self.authority.sign_answer({
            "kind": "ASK", "query": query, "text": pa.text, "sources": pa.sources, "createdAt": now,
            "expiresAt": now + pa.ttl_ms, "provider": pa.provider or self.provider.name,
        })
        with self._lock:
            self._answers[key] = answer
            if len(self._answers) > 2000:
                self._answers.pop(next(iter(self._answers)))
        self.audit("ask", requester=requester, cached=False, ms=int((time.time() - t0) * 1000), **self._query_fields(query))
        return answer

    def read(self, body: dict) -> dict:
        url = str(body.get("url", "")).strip()
        requester = str(body.get("requester", ""))[:40]
        if not self.limits.allow("read:" + requester, 10):
            raise ApiError(429, "too many page reads; try again shortly")
        try:
            pa = read_page(url)
        except ValueError as e:
            raise ApiError(400, str(e))
        except Exception as e:
            raise ApiError(502, f"could not fetch the page: {type(e).__name__}")
        now = now_ms()
        self.audit("read", requester=requester, host=urlparse(url).hostname)
        return self.authority.sign_answer({
            "kind": "READ", "query": url, "text": pa.text, "sources": pa.sources, "createdAt": now,
            "expiresAt": now + pa.ttl_ms, "provider": pa.provider,
        })

    def _query_fields(self, query: str) -> dict:
        if self.log_queries:
            return {"query": query}
        return {"queryHash": hashlib.sha256(query.encode()).hexdigest()[:16], "queryChars": len(query)}

    # ---- admin -----------------------------------------------------------------------------------

    def check_admin(self, body: dict, headers) -> None:
        token = headers.get("X-Admin-Token") or str(body.get("token", ""))
        if not secrets.compare_digest(token, self.admin_token):
            raise ApiError(403, "admin token required")


def make_handler(app: Backend):
    class Handler(BaseHTTPRequestHandler):
        server_version = "MeshMindBackend/0.2"

        def log_message(self, fmt, *args):
            log.info("%s %s", self.address_string(), fmt % args)

        def _send(self, status: int, payload: dict) -> None:
            data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
            self.send_response(status)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)

        def _body(self) -> dict:
            n = int(self.headers.get("Content-Length") or 0)
            if n > 64_000:
                raise ApiError(413, "request too large")
            raw = self.rfile.read(n) if n else b"{}"
            try:
                body = json.loads(raw or b"{}")
            except json.JSONDecodeError:
                raise ApiError(400, "invalid JSON")
            if not isinstance(body, dict):
                raise ApiError(400, "JSON object expected")
            return body

        def _route(self, method: str) -> None:
            url = urlparse(self.path)
            q = parse_qs(url.query)
            try:
                if method == "GET" and url.path == "/v1/health":
                    out = {"ok": True, "provider": app.provider.name, "authority": app.authority.key_id, "demoOtp": app.demo_otp}
                elif method == "GET" and url.path == "/v1/authority":
                    out = {"publicKey": base64.b64encode(app.authority.public_key).decode(), "keyId": app.authority.key_id}
                elif method == "GET" and url.path == "/v1/bulletin":
                    lat = float(q["lat"][0]) if "lat" in q else None
                    lon = float(q["lon"][0]) if "lon" in q else None
                    out = app.board.build(lat, lon)
                elif method == "POST" and url.path == "/v1/enroll/start":
                    out = app.enroll_start(self._body(), self.client_address[0])
                elif method == "POST" and url.path == "/v1/enroll/verify":
                    out = app.enroll_verify(self._body())
                elif method == "POST" and url.path == "/v1/ask":
                    out = app.ask(self._body())
                elif method == "POST" and url.path == "/v1/read":
                    out = app.read(self._body())
                elif method == "POST" and url.path == "/v1/notices":
                    body = self._body()
                    app.check_admin(body, self.headers)
                    out = app.board.add_notice(str(body.get("title", "")), str(body.get("body", "")),
                                               int(body.get("severity", 1)), str(body.get("kind", "NOTICE")).upper(),
                                               int(body.get("ttlMinutes", 60)))
                elif method == "DELETE" and url.path == "/v1/notices":
                    app.check_admin({}, self.headers)
                    app.board.clear_notices()
                    out = {"ok": True}
                else:
                    raise ApiError(404, "not found")
                self._send(200, out)
            except ApiError as e:
                self._send(e.status, {"error": str(e)})
            except (ValueError, KeyError) as e:
                self._send(400, {"error": f"bad request: {e}"})
            except Exception:
                log.exception("unhandled error")
                self._send(500, {"error": "internal error"})

        def do_GET(self):
            self._route("GET")

        def do_POST(self):
            self._route("POST")

        def do_DELETE(self):
            self._route("DELETE")

    return Handler


def load_env() -> None:
    """Reads backend/.env (then the repo root .env) into os.environ without overriding real environment variables."""
    here = Path(__file__).resolve().parent.parent
    for f in (here / ".env", here.parent / ".env"):
        if not f.exists():
            continue
        for line in f.read_text(encoding="utf-8").splitlines():
            line = line.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            k, v = line.split("=", 1)
            os.environ.setdefault(k.strip(), v.strip().strip('"').strip("'"))


def _configured(name: str) -> str | None:
    v = os.environ.get(name, "").strip()
    return v if v and not v.lower().startswith(("your-", "placeholder", "<")) else None


def build_app(args) -> Backend:
    data_dir = Path(args.data).resolve()
    data_dir.mkdir(parents=True, exist_ok=True)
    authority = Authority.load_or_create(data_dir / "authority_key.pem")
    gemini_key = _configured("GEMINI_API_KEY")
    anthropic_key = _configured("ANTHROPIC_API_KEY") or _configured("ANTHROPIC_AUTH_TOKEN")
    choice = args.provider
    if choice == "auto":
        choice = "gemini" if gemini_key else "claude" if anthropic_key else "demo"
    if choice == "gemini":
        if not gemini_key:
            raise SystemExit("--provider gemini needs GEMINI_API_KEY (put it in backend/.env)")
        provider: AnswerProvider = GeminiProvider(gemini_key, args.gemini_model, args.context)
    elif choice == "claude":
        provider = ClaudeProvider(args.model, args.effort, args.context)
    else:
        provider = DemoProvider()
    board = BulletinBoard(authority, args.lat, args.lon, args.place, args.alert_feed)
    admin = os.environ.get("MESHMIND_ADMIN_TOKEN") or secrets.token_urlsafe(12)
    app = Backend(data_dir, provider, board, authority, args.demo_otp, args.organizer_code, admin,
                  args.log_queries, args.retention_days)
    log.warning("authority key %s · provider %s · demo OTP %s · admin token %s",
                authority.key_id, provider.name, args.demo_otp, admin)
    return app


def main() -> None:
    load_env()
    p = argparse.ArgumentParser(description="MeshMind backend")
    p.add_argument("--host", default="0.0.0.0")
    p.add_argument("--port", type=int, default=8787)
    p.add_argument("--data", default=str(Path(__file__).resolve().parent.parent / "data"))
    p.add_argument("--provider", choices=["auto", "gemini", "claude", "demo"], default=os.environ.get("MESHMIND_PROVIDER", "auto"),
                   help="auto = Gemini if GEMINI_API_KEY is set, else Claude if ANTHROPIC_API_KEY is set, else canned demo answers")
    p.add_argument("--gemini-model", default=os.environ.get("MESHMIND_GEMINI_MODEL", "gemini-3.5-flash-lite"))
    p.add_argument("--model", default=os.environ.get("MESHMIND_MODEL", "claude-opus-5-5"))
    p.add_argument("--effort", default=os.environ.get("MESHMIND_EFFORT", "low"), choices=["low", "medium", "high", "xhigh", "max"])
    p.add_argument("--context", default=os.environ.get("MESHMIND_CONTEXT", "They are at a large event in Bengaluru, India."))
    p.add_argument("--lat", type=float, default=12.9788)
    p.add_argument("--lon", type=float, default=77.5996)
    p.add_argument("--place", default="Bengaluru")
    p.add_argument("--alert-feed", default=os.environ.get("MESHMIND_ALERT_FEED"))
    p.add_argument("--demo-otp", action="store_true", default=os.environ.get("MESHMIND_DEMO_OTP", "") == "1",
                   help="return OTP codes in the API response instead of sending SMS")
    p.add_argument("--organizer-code", default=os.environ.get("MESHMIND_ORGANIZER_CODE", "demo-organizer"))
    p.add_argument("--log-queries", action="store_true", help="store question text in the audit log (default: hash only)")
    p.add_argument("--retention-days", type=int, default=180)
    args = p.parse_args()
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(name)s %(levelname)s %(message)s")
    app = build_app(args)
    srv = ThreadingHTTPServer((args.host, args.port), make_handler(app))
    log.warning("listening on http://%s:%d", args.host, args.port)
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
