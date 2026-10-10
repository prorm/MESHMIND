"""Backend tests: canonical encoding, signatures, and the HTTP API end to end (demo provider, no network).

    .venv/Scripts/python -m unittest discover -s tests -v
"""

import base64
import json
import sys
import tempfile
import threading
import unittest
import urllib.error
import urllib.request
from pathlib import Path
from types import SimpleNamespace

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from cryptography.hazmat.primitives import hashes, serialization  # noqa: E402
from cryptography.hazmat.primitives.asymmetric import ec  # noqa: E402

from meshmind_backend.authority import Authority  # noqa: E402
from meshmind_backend.canon import Canon, answer_bytes, bulletin_bytes, cert_bytes, normalize_query  # noqa: E402
from meshmind_backend.server import ThreadingHTTPServer, build_app, make_handler  # noqa: E402


def verify(pub_der: bytes, data: bytes, sig_b64: str) -> bool:
    key = serialization.load_der_public_key(pub_der)
    try:
        key.verify(base64.b64decode(sig_b64), data, ec.ECDSA(hashes.SHA256()))
        return True
    except Exception:
        return False


class CanonTest(unittest.TestCase):
    def test_layout_matches_kotlin(self):
        # Same bytes as Canon("t").str("héllo").num(42).flag(True) in Kotlin.
        self.assertEqual(Canon("t").str("héllo").num(42).flag(True).build(), b"1:t;6:h\xc3\xa9llo;2:42;1:1;")

    def test_normalize_matches_phone_cache_key(self):
        self.assertEqual(normalize_query("Is the metro running?"), normalize_query("metro RUNNING now!!"))
        self.assertEqual(normalize_query("Is the metro running?"), "metro running")


class AuthorityTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.auth = Authority.load_or_create(Path(self.tmp.name) / "k.pem")

    def tearDown(self):
        self.tmp.cleanup()

    def test_key_persists(self):
        again = Authority.load_or_create(Path(self.tmp.name) / "k.pem")
        self.assertEqual(again.key_id, self.auth.key_id)

    def test_certificate_answer_bulletin_signatures(self):
        node_key = ec.generate_private_key(ec.SECP256R1()).public_key().public_bytes(
            serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)
        c = self.auth.issue_certificate("Riya-1a2b", node_key, "ORGANIZER", 1000)
        data = cert_bytes(c["nodeId"], base64.b64decode(c["publicKey"]), c["role"], c["phoneVerified"], c["issuedAt"], c["expiresAt"], c["issuer"])
        self.assertTrue(verify(self.auth.public_key, data, c["signature"]))
        a = self.auth.sign_answer({"kind": "ASK", "query": "q", "text": "t", "sources": [{"title": "s", "url": "https://x"}],
                                   "createdAt": 1, "expiresAt": 2, "provider": "p"})
        self.assertTrue(verify(self.auth.public_key, answer_bytes(a), a["signature"]))
        a["text"] = "tampered"
        self.assertFalse(verify(self.auth.public_key, answer_bytes(a), a["signature"]))
        b = self.auth.sign_bulletin({"boardId": "official", "title": "Live", "seq": 3, "issuedAt": 4,
                                     "items": [{"kind": "ALERT", "severity": 2, "title": "Gate B closed", "body": "Use A",
                                                "source": "org", "issuedAt": 4, "expiresAt": 5}]})
        self.assertTrue(verify(self.auth.public_key, bulletin_bytes(b), b["signature"]))

    def test_rejects_non_p256_keys(self):
        other = ec.generate_private_key(ec.SECP384R1()).public_key().public_bytes(
            serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)
        with self.assertRaises(ValueError):
            self.auth.issue_certificate("X-1", other, "USER", 0)


class ApiTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.TemporaryDirectory()
        args = SimpleNamespace(data=cls.tmp.name, provider="demo", model="unused", gemini_model="unused", effort="low", context="",
                               lat=12.97, lon=77.59, place="Test City", alert_feed=None, demo_otp=True,
                               organizer_code="org-code", log_queries=False, retention_days=180)
        cls.app = build_app(args)
        # No network in tests: a fixed weather item instead of Open-Meteo.
        cls.app.board._weather = lambda lat, lon: [{"kind": "WEATHER", "severity": 0, "title": "27°C, clear sky", "body": "Wind 5 km/h.",
                                                    "source": "Open-Meteo", "issuedAt": 1, "expiresAt": 2}]
        cls.srv = ThreadingHTTPServer(("127.0.0.1", 0), make_handler(cls.app))
        cls.base = f"http://127.0.0.1:{cls.srv.server_address[1]}"
        threading.Thread(target=cls.srv.serve_forever, daemon=True).start()

    @classmethod
    def tearDownClass(cls):
        cls.srv.shutdown()
        cls.tmp.cleanup()

    def call(self, method, path, body=None, headers=None):
        req = urllib.request.Request(self.base + path, method=method, data=json.dumps(body).encode() if body is not None else None,
                                     headers={"Content-Type": "application/json", **(headers or {})})
        try:
            with urllib.request.urlopen(req, timeout=5) as r:
                return r.status, json.load(r)
        except urllib.error.HTTPError as e:
            return e.code, json.load(e)

    def node_key(self):
        return base64.b64encode(ec.generate_private_key(ec.SECP256R1()).public_key().public_bytes(
            serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)).decode()

    def test_enrollment_flow(self):
        s, r = self.call("POST", "/v1/enroll/start", {"phone": "+919876543210"})
        self.assertEqual(200, s)
        code = r["demoCode"]
        s, r = self.call("POST", "/v1/enroll/verify", {"phone": "+919876543210", "code": "000000" if code != "000000" else "111111",
                                                       "nodeId": "Asha-1f2e", "publicKey": self.node_key(), "role": "USER"})
        self.assertEqual(400, s, "wrong code is refused")
        key = self.node_key()
        s, r = self.call("POST", "/v1/enroll/verify", {"phone": "+919876543210", "code": code, "nodeId": "Asha-1f2e", "publicKey": key, "role": "USER"})
        self.assertEqual(200, s, r)
        self.assertEqual("Asha-1f2e", r["certificate"]["nodeId"])
        self.assertEqual(base64.b64encode(self.app.authority.public_key).decode(), r["authorityKey"])
        # Another phone cannot take the same node name.
        _, r2 = self.call("POST", "/v1/enroll/start", {"phone": "+911111111111"})
        s, _ = self.call("POST", "/v1/enroll/verify", {"phone": "+911111111111", "code": r2["demoCode"], "nodeId": "Asha-1f2e", "publicKey": key})
        self.assertEqual(409, s)
        # Organizer role needs the event code.
        _, r3 = self.call("POST", "/v1/enroll/start", {"phone": "+912222222222"})
        s, _ = self.call("POST", "/v1/enroll/verify", {"phone": "+912222222222", "code": r3["demoCode"], "nodeId": "Org-1", "publicKey": key, "role": "ORGANIZER"})
        self.assertEqual(403, s)
        audit = (Path(self.tmp.name) / "audit.jsonl").read_text()
        self.assertIn("enrolled", audit)

    def test_ask_is_signed_cached_and_not_logged_in_clear(self):
        s, a = self.call("POST", "/v1/ask", {"query": "Is the metro running?", "requester": "A-1"})
        self.assertEqual(200, s, a)
        self.assertTrue(verify(self.app.authority.public_key, answer_bytes(a), a["signature"]))
        self.assertIn("Metro", a["text"])
        s, b = self.call("POST", "/v1/ask", {"query": "metro running now", "requester": "B-2"})
        self.assertEqual(a["signature"], b["signature"], "same normalised question is served from the backend cache")
        self.assertNotIn("metro", (Path(self.tmp.name) / "audit.jsonl").read_text().lower())

    def test_read_refuses_private_and_plain_http(self):
        for url in ("http://example.com/", "https://127.0.0.1/", "https://localhost/admin"):
            s, _ = self.call("POST", "/v1/read", {"url": url, "requester": "A"})
            self.assertEqual(400, s, url)

    def test_bulletin_and_admin_notices(self):
        s, _ = self.call("POST", "/v1/notices", {"title": "Gate B closed", "body": "Use A or C"})
        self.assertEqual(403, s)
        s, _ = self.call("POST", "/v1/notices", {"title": "Gate B closed", "body": "Use A or C", "severity": 2},
                         headers={"X-Admin-Token": self.app.admin_token})
        self.assertEqual(200, s)
        s, b = self.call("GET", "/v1/bulletin")
        self.assertEqual(200, s)
        self.assertEqual("Gate B closed", b["items"][0]["title"])
        self.assertTrue(verify(self.app.authority.public_key, bulletin_bytes(b), b["signature"]))
        _, b2 = self.call("GET", "/v1/bulletin")
        self.assertEqual(b["seq"], b2["seq"], "unchanged board keeps its seq")




class GeminiParsingTest(unittest.TestCase):
    def test_grounded_answer_and_fallback(self):
        import io
        import urllib.error
        from meshmind_backend.gemini import GeminiProvider

        calls = []

        class Fake(GeminiProvider):
            def _call(self, query, grounded):
                calls.append(grounded)
                if grounded and query == "quota":
                    raise urllib.error.HTTPError("u", 429, "quota", {}, io.BytesIO(b"RESOURCE_EXHAUSTED"))
                return {"candidates": [{"content": {"parts": [{"text": "**Metro** Line 2\n runs  every 8 min."}]},
                                        "groundingMetadata": {"groundingChunks": [{"web": {"uri": "https://bmrcl.example/x", "title": "bmrcl"}}]}}]}

        g = Fake("k", "gemini-2.5-flash-lite", "")
        a = g.answer("metro")
        self.assertEqual("Metro Line 2 runs every 8 min.", a.text)
        self.assertEqual([{"title": "bmrcl", "url": "https://bmrcl.example/x"}], a.sources)
        self.assertEqual("gemini-2.5-flash-lite", a.provider)
        b = g.answer("quota")
        self.assertTrue(b.provider.endswith("(no web search)"))
        self.assertEqual([True, True, False], calls)


if __name__ == "__main__":
    unittest.main()
