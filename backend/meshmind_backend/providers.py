"""Where answers come from. Provider-agnostic on purpose: the phones only ever see signed, compact answers.

* ClaudeProvider: Claude with Anthropic's server-side web search (needs ANTHROPIC_API_KEY or an `ant auth login`
  profile on the backend host).
* DemoProvider: canned answers, no network, for rehearsals and offline demos.
* Gemini grounding with Google Search or any other engine can be added by implementing `answer()`.
"""

from __future__ import annotations

import html.parser
import ipaddress
import logging
import re
import socket
import urllib.parse
import urllib.request
from dataclasses import dataclass, field

log = logging.getLogger("meshmind.providers")

MAX_ANSWER_CHARS = 700
MAX_READ_CHARS = 6000


@dataclass
class ProviderAnswer:
    text: str
    sources: list[dict] = field(default_factory=list)  # [{"title": ..., "url": ...}]
    provider: str = ""
    ttl_ms: int = 30 * 60_000


class AnswerProvider:
    name = "base"

    def answer(self, query: str) -> ProviderAnswer:
        raise NotImplementedError


SYSTEM_PROMPT = """You answer questions from people whose phones have no Internet: they are at a packed event \
or in an area where the mobile network failed, and your answer travels back to them over a slow Bluetooth mesh \
of strangers' phones. {context}

Write at most 80 words of plain text: no markdown, no lists, no preamble. Lead with the direct answer, then one \
practical next step if useful. Prefer official and local sources and say how current the information is when it \
matters. If you are unsure or the information may be stale, say so briefly rather than guessing. If someone \
describes an emergency, tell them to press SOS in MeshMind and call 112 (or the local emergency number) as soon \
as any phone has signal."""


class ClaudeProvider(AnswerProvider):
    """Claude + web search. Server-side fallback is enabled so a declined request is retried on another model."""

    def __init__(self, model: str, effort: str, context: str, max_searches: int = 3) -> None:
        import anthropic  # imported lazily so the demo provider works without the SDK configured

        self._anthropic = anthropic
        self.client = anthropic.Anthropic(max_retries=2, timeout=60.0)
        self.model = model
        self.effort = effort
        self.system = SYSTEM_PROMPT.format(context=context.strip())
        self.max_searches = max_searches
        self.name = model

    def _tools(self) -> list[dict]:
        # The dynamic-filtering web search needs Opus/Sonnet 4.6+; Haiku uses the basic tool version.
        tool_type = "web_search_20250305" if self.model.startswith("claude-haiku") else "web_search_20260209"
        return [{"type": tool_type, "name": "web_search", "max_uses": self.max_searches}]

    def answer(self, query: str) -> ProviderAnswer:
        kwargs = {}
        betas = []
        if not self.model.startswith("claude-haiku"):
            # Opt in to Anthropic's recommended fallback model on a refusal (no fallback exists for Haiku).
            betas.append("server-side-fallback-2026-07-01")
            kwargs["fallbacks"] = "default"
        messages = [{"role": "user", "content": query}]
        response = None
        for _ in range(4):  # web search can pause a long turn; resume a few times at most
            response = self.client.beta.messages.create(
                model=self.model,
                max_tokens=8000,
                system=self.system,
                tools=self._tools(),
                output_config={"effort": self.effort},
                messages=messages,
                betas=betas,
                **kwargs,
            )
            if response.stop_reason != "pause_turn":
                break
            messages = [{"role": "user", "content": query}, {"role": "assistant", "content": response.content}]

        if response is None or response.stop_reason == "refusal":
            return ProviderAnswer("I can't help with that request here. Try asking it differently.", [], self.name, 5 * 60_000)

        texts, sources = [], []
        seen = set()

        def add_source(url: str | None, title: str | None) -> None:
            if url and url not in seen and len(sources) < 3:
                seen.add(url)
                sources.append({"title": (title or url)[:120], "url": url[:500]})

        for block in response.content:
            btype = getattr(block, "type", "")
            if btype == "text":
                texts.append(block.text)
                for c in getattr(block, "citations", None) or []:
                    add_source(getattr(c, "url", None), getattr(c, "title", None))
            elif btype == "web_search_tool_result":
                content = getattr(block, "content", None)
                if isinstance(content, list):  # an error result is an object, not a list
                    for r in content:
                        add_source(getattr(r, "url", None), getattr(r, "title", None))
        text = re.sub(r"\s+", " ", " ".join(texts)).strip()
        text = re.sub(r"[*_#`]+", "", text)  # the mesh UI shows plain text
        if len(text) > MAX_ANSWER_CHARS:
            text = text[: MAX_ANSWER_CHARS - 1].rsplit(" ", 1)[0] + "…"
        return ProviderAnswer(text or "No answer found.", sources, getattr(response, "model", self.name), 30 * 60_000)


class DemoProvider(AnswerProvider):
    """Canned, clearly-labelled answers so the whole flow can be rehearsed without an API key or Internet."""

    name = "demo (canned answers)"
    canned = [
        (("metro", "train", "transport"), "Demo answer: Metro Line 2 is running every 8 minutes until 23:30; the "
         "nearest station entrance is 400 m from Gate C.", "Demo transit feed"),
        (("hospital", "doctor", "medical", "first aid"), "Demo answer: The medical tent is behind the food court "
         "near Gate A, staffed until the end of the show. Nearest hospital: City General, 2.1 km.", "Demo venue info"),
        (("exit", "gate", "gates", "evacuation"), "Demo answer: Gates A and C are open for exit. Gate B is "
         "closed due to crowding; follow the green signs.", "Demo organizer feed"),
        (("weather", "rain"), "Demo answer: 27°C now, light rain likely from 16:00, clearing by 19:00.", "Demo weather"),
    ]

    def answer(self, query: str) -> ProviderAnswer:
        q = query.lower()
        for words, text, src in self.canned:
            if any(w in q for w in words):
                return ProviderAnswer(text, [{"title": src, "url": "https://example.com/demo"}], self.name, 10 * 60_000)
        return ProviderAnswer("Demo answer: this backend runs without an AI provider. Set ANTHROPIC_API_KEY to "
                              "get real answers.", [], self.name, 5 * 60_000)


# ---------------------------------------------------------------- READ: plain readable text of a page

class _TextExtractor(html.parser.HTMLParser):
    SKIP = {"script", "style", "noscript", "nav", "footer", "header", "svg", "form", "aside", "iframe"}
    BLOCK = {"p", "div", "br", "li", "h1", "h2", "h3", "h4", "tr", "section", "article"}

    def __init__(self) -> None:
        super().__init__(convert_charrefs=True)
        self.parts: list[str] = []
        self.title = ""
        self._skip = 0
        self._in_title = False

    def handle_starttag(self, tag, attrs):
        if tag in self.SKIP:
            self._skip += 1
        elif tag == "title":
            self._in_title = True
        elif tag in self.BLOCK:
            self.parts.append("\n")

    def handle_endtag(self, tag):
        if tag in self.SKIP and self._skip:
            self._skip -= 1
        elif tag == "title":
            self._in_title = False

    def handle_data(self, data):
        if self._in_title:
            self.title += data
        elif not self._skip:
            self.parts.append(data)


def _public_host(host: str) -> bool:
    """Blocks requests to private, loopback and link-local addresses (no SSRF into the backend's network)."""
    try:
        for info in socket.getaddrinfo(host, 443, proto=socket.IPPROTO_TCP):
            ip = ipaddress.ip_address(info[4][0])
            if ip.is_private or ip.is_loopback or ip.is_link_local or ip.is_reserved or ip.is_multicast:
                return False
        return True
    except (socket.gaierror, ValueError):
        return False


def read_page(url: str, max_bytes: int = 2_000_000) -> ProviderAnswer:
    parsed = urllib.parse.urlparse(url)
    if parsed.scheme != "https" or not parsed.hostname:
        raise ValueError("HTTPS URLs only")
    if not _public_host(parsed.hostname):
        raise ValueError("that host is not reachable from here")

    class _NoRedirect(urllib.request.HTTPRedirectHandler):
        def redirect_request(self, *args, **kwargs):  # a redirect could point at a private address
            return None

    opener = urllib.request.build_opener(_NoRedirect)
    req = urllib.request.Request(url, headers={"User-Agent": "MeshMind-Reader/0.2 (+offline mesh relay)"})
    with opener.open(req, timeout=12) as r:
        ctype = r.headers.get("Content-Type", "")
        raw = r.read(max_bytes)
    charset = "utf-8"
    m = re.search(r"charset=([\w-]+)", ctype)
    if m:
        charset = m.group(1)
    body = raw.decode(charset, errors="replace")
    if "html" in ctype or body.lstrip().startswith("<"):
        ex = _TextExtractor()
        ex.feed(body)
        text = "".join(ex.parts)
        title = ex.title.strip()
    else:
        text, title = body, url
    lines = [re.sub(r"[ \t\r\f\v]+", " ", ln).strip() for ln in text.split("\n")]
    text = "\n".join(ln for ln in lines if len(ln) > 1)
    text = re.sub(r"\n{3,}", "\n\n", text).strip()
    if len(text) > MAX_READ_CHARS:
        text = text[: MAX_READ_CHARS - 1].rsplit(" ", 1)[0] + "…"
    return ProviderAnswer(text or "(no readable text on this page)", [{"title": (title or url)[:120], "url": url}],
                          "reader", 60 * 60_000)
