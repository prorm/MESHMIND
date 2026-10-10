"""Gemini answer provider over plain REST (no SDK), with Grounding with Google Search when the key allows it.

Default model: gemini-3.5-flash-lite (free tier; $0.30 / $2.50 per 1M tokens when paid). Measured on 2026-10-10 with
a new free-tier key: ~1.4 s per call, versus ~20 s for gemini-3.1-flash-lite ($0.25 / $1.50); gemini-2.5-flash-lite
is cheaper still but Google no longer serves it to new keys. In a crisis, speed wins.
On the free tier Google Search grounding is refused (429); the provider then answers ungrounded, marks the answer
"(no web search)", and stops trying grounding for a while so every question isn't slowed by a failed attempt.
Enabling billing on the Google project turns grounding on (Gemini 3+: 5,000 grounded queries/month free).
"""

from __future__ import annotations

import json
import logging
import re
import time
import urllib.error
import urllib.request

from .providers import MAX_ANSWER_CHARS, SYSTEM_PROMPT, AnswerProvider, ProviderAnswer

log = logging.getLogger("meshmind.gemini")

API = "https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent"

# Without search the model has no live data; an invented "running normally today" is worse than no answer.
NO_SEARCH_RULE = (
    "You have NO web access and do not know today's date or any live status. Never say something is open, running, "
    "closed or happening today, and never state a date as current. Give general, stable facts only (locations, "
    "usual timings, what to do), and say plainly that you could not check live status."
)


class GeminiProvider(AnswerProvider):
    def __init__(self, api_key: str, model: str, context: str) -> None:
        self.api_key = api_key
        self.model = model
        self.system = SYSTEM_PROMPT.format(context=context.strip())
        self.name = model
        self._grounding_off_until = 0.0

    def _call(self, query: str, grounded: bool) -> dict:
        body = {
            "systemInstruction": {"parts": [{"text": self.system}]},
            "contents": [{"role": "user", "parts": [{"text": query}]}],
            "generationConfig": {"maxOutputTokens": 400, "temperature": 0.3},
        }
        if not grounded:
            body["systemInstruction"]["parts"].append({"text": NO_SEARCH_RULE})
        if grounded:
            body["tools"] = [{"google_search": {}}]
        req = urllib.request.Request(
            API.format(model=self.model), data=json.dumps(body).encode(), method="POST",
            headers={"Content-Type": "application/json", "x-goog-api-key": self.api_key},
        )
        with urllib.request.urlopen(req, timeout=45) as r:
            return json.load(r)

    def answer(self, query: str) -> ProviderAnswer:
        grounded = time.time() >= self._grounding_off_until
        try:
            data = self._call(query, grounded=grounded)
        except urllib.error.HTTPError as e:
            detail = e.read().decode(errors="replace")[:300]
            if e.code in (401, 403) and "API key" in detail:
                raise RuntimeError(f"Gemini rejected the API key: {detail}")
            if not grounded:
                raise RuntimeError(f"Gemini error {e.code}: {detail}")
            log.warning("grounded call failed (%s); answering without search for the next 15 min", e.code)
            self._grounding_off_until = time.time() + 15 * 60
            grounded = False
            data = self._call(query, grounded=False)

        cand = (data.get("candidates") or [{}])[0]
        if cand.get("finishReason") in ("SAFETY", "PROHIBITED_CONTENT", "BLOCKLIST"):
            return ProviderAnswer("I can't help with that request here. Try asking it differently.", [], self.name, 5 * 60_000)
        text = "".join(p.get("text", "") for p in (cand.get("content") or {}).get("parts", []) if not p.get("thought"))
        text = re.sub(r"[*_#`]+", "", re.sub(r"\s+", " ", text)).strip()
        if len(text) > MAX_ANSWER_CHARS:
            text = text[: MAX_ANSWER_CHARS - 1].rsplit(" ", 1)[0] + "…"

        sources = []
        for chunk in (cand.get("groundingMetadata") or {}).get("groundingChunks", [])[:3]:
            web = chunk.get("web") or {}
            if web.get("uri"):
                sources.append({"title": (web.get("title") or "source")[:120], "url": _resolve(web["uri"])[:500]})
        provider = self.name + ("" if grounded else " (no web search)")
        return ProviderAnswer(text or "No answer found.", sources, provider, 30 * 60_000 if grounded else 10 * 60_000)


def _resolve(uri: str) -> str:
    """Grounding links are Google redirect URLs; resolve them once so phones can open the page with Read."""
    if "grounding-api-redirect" not in uri:
        return uri

    class _Stop(urllib.request.HTTPRedirectHandler):
        def redirect_request(self, req, fp, code, msg, headers, newurl):
            raise urllib.error.HTTPError(newurl, code, msg, headers, fp)

    try:
        urllib.request.build_opener(_Stop).open(urllib.request.Request(uri, method="HEAD"), timeout=5)
    except urllib.error.HTTPError as e:
        loc = e.headers.get("Location") if e.headers else None
        if loc and loc.startswith("https://"):
            return loc
    except Exception:
        pass
    return uri
