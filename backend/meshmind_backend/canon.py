"""Canonical byte strings that MeshMind signatures cover.

Must match dev.meshmind.core.security.Canon exactly: every field is written as
``<byte length>:<bytes>;`` (lengths in ASCII decimal), so field boundaries are
unambiguous. Booleans are "1"/"0", numbers are decimal, keys are base64.
"""

from __future__ import annotations

import base64
import re


class Canon:
    def __init__(self, tag: str) -> None:
        self._out = bytearray()
        self.str(tag)

    def bin(self, b: bytes) -> "Canon":
        self._out += f"{len(b)}:".encode("ascii") + b + b";"
        return self

    def str(self, s: str) -> "Canon":
        return self.bin(s.encode("utf-8"))

    def num(self, n: int) -> "Canon":
        return self.str(str(int(n)))

    def flag(self, b: bool) -> "Canon":
        return self.str("1" if b else "0")

    def b64(self, b: bytes) -> "Canon":
        return self.str(base64.b64encode(b).decode("ascii"))

    def build(self) -> bytes:
        return bytes(self._out)


def cert_bytes(node_id: str, public_key: bytes, role: str, phone_verified: bool,
               issued_at: int, expires_at: int, issuer: str) -> bytes:
    return (Canon("meshmind-cert-v1").str(node_id).b64(public_key).str(role).flag(phone_verified)
            .num(issued_at).num(expires_at).str(issuer).build())


def answer_bytes(a: dict) -> bytes:
    c = Canon("meshmind-answer-v1").str(a["kind"]).str(a["query"]).str(a["text"]).num(len(a["sources"]))
    for s in a["sources"]:
        c.str(s["title"]).str(s["url"])
    return c.num(a["createdAt"]).num(a["expiresAt"]).str(a["provider"]).str(a["keyId"]).build()


def bulletin_bytes(b: dict) -> bytes:
    c = (Canon("meshmind-bulletin-v1").str(b["boardId"]).str(b["title"]).num(b["seq"]).num(b["issuedAt"])
         .num(len(b["items"])))
    for i in b["items"]:
        c.str(i["kind"]).num(i["severity"]).str(i["title"]).str(i["body"]).str(i["source"]).num(i["issuedAt"]).num(i["expiresAt"])
    return c.str(b["keyId"]).build()


_STOP = {
    "a", "an", "the", "is", "are", "was", "were", "be", "to", "of", "in", "on", "at", "for", "and", "or",
    "do", "does", "did", "i", "me", "my", "we", "you", "it", "this", "that", "there", "what", "whats",
    "please", "tell", "can", "could", "would", "will", "now", "today", "right", "currently", "any",
}


def normalize_query(q: str) -> str:
    """Same as AnswerCache.normalize on the phones: one cache key for equivalent questions."""
    words = re.sub(r"[^\w]+", " ", q.lower().replace("_", " ")).split()
    return " ".join(sorted({w for w in words if w not in _STOP}))
