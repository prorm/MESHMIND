"""Gemini answer provider: Gemini 2.5 Flash-Lite with Grounding with Google Search, over plain REST (no SDK).

Why this model: it is the cheapest Gemini model ($0.10 / $0.40 per 1M tokens), it has a free tier, and it is the
Flash-Lite model whose free tier includes Google Search grounding (Google's pricing page, Oct 2026: up to 500
grounded requests/day free). If grounding is refused (quota, region), the question is retried ungrounded so the
crowd still gets an answer, marked as such.
"""

from __future__ import annotations

import json
import logging
import re
import urllib.error
import urllib.request

from .providers import MAX_ANSWER_CHARS, SYSTEM_PROMPT, AnswerProvider, ProviderAnswer

log = logging.getLogger("meshmind.gemini")

API = "https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent"


class GeminiProvider(AnswerProvider):
    def __init__(self, api_key: str, model: str, context: str) -> None:
        self.api_key = api_key
        self.model = model
        self.system = SYSTEM_PROMPT.format(context=context.strip())
        self.name = model

    def _call(self, query: str, grounded: bool) -> dict:
        body = {
            "systemInstruction": {"parts": [{"text": self.system}]},
            "contents": [{"role": "user", "parts": [{"text": query}]}],
            "generationConfig": {"maxOutputTokens": 400, "temperature": 0.3},
        }
        if grounded:
            body["tools"] = [{"google_search": {}}]
        req = urllib.request.Request(
            API.format(model=self.model), data=json.dumps(body).encode(), method="POST",
            headers={"Content-Type": "application/json", "x-goog-api-key": self.api_key},
        )
        with urllib.request.urlopen(req, timeout=45) as r:
            return json.load(r)

    def answer(self, query: str) -> ProviderAnswer:
        grounded = True
        try:
            data = self._call(query, grounded=True)
        except urllib.error.HTTPError as e:
            detail = e.read().decode(errors="replace")[:300]
            if e.code in (401, 403) and "API key" in detail:
                raise RuntimeError(f"Gemini rejected the API key: {detail}")
            log.warning("grounded call failed (%s %s); retrying without search", e.code, detail)
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
