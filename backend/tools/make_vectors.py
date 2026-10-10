"""Writes signed test vectors that the Kotlin test BackendInteropTest verifies.

Proves that certificates, answers and bulletins signed here by Python verify on the phones (same canonical
bytes, same ECDSA encoding). Re-run after changing canon.py or the Kotlin Canon:

    .venv/Scripts/python tools/make_vectors.py
"""

import base64
import json
import sys
import tempfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from cryptography.hazmat.primitives import serialization  # noqa: E402
from cryptography.hazmat.primitives.asymmetric import ec  # noqa: E402

from meshmind_backend.authority import Authority  # noqa: E402

OUT = Path(__file__).resolve().parents[2] / "core" / "src" / "test" / "resources" / "backend_vectors.json"

with tempfile.TemporaryDirectory() as d:
    auth = Authority.load_or_create(Path(d) / "k.pem")
    node_key = ec.generate_private_key(ec.SECP256R1())
    node_pub = node_key.public_key().public_bytes(serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)
    vectors = {
        "authorityKey": base64.b64encode(auth.public_key).decode(),
        "certificate": auth.issue_certificate("Riya-1a2b", node_pub, "ORGANIZER", 1_760_000_000_000),
        "answer": auth.sign_answer({
            "kind": "ASK", "query": "Is the metro running?", "text": "Yes — Line 2 every 8 min. Ünïcödé ✓",
            "sources": [{"title": "BMRCL", "url": "https://example.com/metro"}, {"title": "News", "url": "https://example.com/n"}],
            "createdAt": 1_760_000_000_000, "expiresAt": 1_760_001_800_000, "provider": "claude-opus-5-5",
        }),
        "bulletin": auth.sign_bulletin({
            "boardId": "official", "title": "Live info · Bengaluru", "seq": 7, "issuedAt": 1_760_000_000_000,
            "items": [{"kind": "ALERT", "severity": 2, "title": "Gate B closed", "body": "Use Gates A or C.",
                       "source": "Event organizer", "issuedAt": 1_760_000_000_000, "expiresAt": 1_760_003_600_000},
                      {"kind": "WEATHER", "severity": 0, "title": "27°C, clear sky", "body": "Wind 5 km/h.",
                       "source": "Open-Meteo", "issuedAt": 1_760_000_000_000, "expiresAt": 1_760_005_400_000}],
        }),
    }
OUT.parent.mkdir(parents=True, exist_ok=True)
OUT.write_text(json.dumps(vectors, indent=1, ensure_ascii=False), encoding="utf-8")
print("wrote", OUT)
