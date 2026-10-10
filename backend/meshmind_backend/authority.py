"""The MeshMind authority key: issues phone-verified certificates and signs answers and bulletins.

ECDSA P-256 / SHA-256, DER signatures, X.509 SubjectPublicKeyInfo public keys: byte-for-byte what the Kotlin
side (dev.meshmind.core.security.Ec) produces and verifies.
"""

from __future__ import annotations

import base64
import hashlib
import os
from pathlib import Path

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec

from .canon import answer_bytes, bulletin_bytes, cert_bytes

YEAR_MS = 365 * 24 * 3_600_000


def b64(b: bytes) -> str:
    return base64.b64encode(b).decode("ascii")


def fingerprint(public_key_der: bytes) -> str:
    return hashlib.sha256(public_key_der).digest()[:8].hex()


class Authority:
    def __init__(self, key: ec.EllipticCurvePrivateKey) -> None:
        self._key = key
        self.public_key = key.public_key().public_bytes(
            serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)
        self.key_id = fingerprint(self.public_key)

    @classmethod
    def load_or_create(cls, path: Path) -> "Authority":
        """The private key lives only on the backend host. Losing it means every phone must re-enroll."""
        if path.exists():
            key = serialization.load_pem_private_key(path.read_bytes(), password=None)
        else:
            path.parent.mkdir(parents=True, exist_ok=True)
            key = ec.generate_private_key(ec.SECP256R1())
            pem = key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8,
                                    serialization.NoEncryption())
            path.write_bytes(pem)
            try:
                os.chmod(path, 0o600)
            except OSError:
                pass
        return cls(key)

    def sign(self, data: bytes) -> bytes:
        return self._key.sign(data, ec.ECDSA(hashes.SHA256()))

    def issue_certificate(self, node_id: str, public_key: bytes, role: str, now_ms: int,
                          validity_ms: int = YEAR_MS) -> dict:
        # Reject anything that is not a valid P-256 public key before vouching for it.
        loaded = serialization.load_der_public_key(public_key)
        if not isinstance(loaded, ec.EllipticCurvePublicKey) or loaded.curve.name != "secp256r1":
            raise ValueError("public key must be EC P-256")
        expires = now_ms + validity_ms
        sig = self.sign(cert_bytes(node_id, public_key, role, True, now_ms, expires, self.key_id))
        return {
            "nodeId": node_id, "publicKey": b64(public_key), "role": role, "phoneVerified": True,
            "issuedAt": now_ms, "expiresAt": expires, "issuer": self.key_id, "signature": b64(sig),
        }

    def sign_answer(self, answer: dict) -> dict:
        a = dict(answer, keyId=self.key_id)
        a["signature"] = b64(self.sign(answer_bytes(a)))
        return a

    def sign_bulletin(self, bulletin: dict) -> dict:
        b = dict(bulletin, keyId=self.key_id)
        b["signature"] = b64(self.sign(bulletin_bytes(b)))
        return b
