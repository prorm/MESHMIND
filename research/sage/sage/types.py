from dataclasses import dataclass
from enum import IntEnum

class Priority(IntEnum):
    CRITICAL = 0
    CONTROL = 1
    TEXT = 2
    MEDIA = 3

@dataclass(frozen=True)
class Contact:
    start: float
    end: float
    a: str
    b: str

@dataclass(frozen=True)
class Packet:
    packet_id: str
    source: str
    destination: str
    creation_time: float
    expiry_time: float
    ttl: int
    size_bytes: int
    priority: Priority

@dataclass
class Copy:
    packet: Packet
    hop_count: int = 0
    replication_count: int = 0
    tokens: int = 4

@dataclass(frozen=True)
class SageDecisionInput:
    packet_id: str
    current: str
    peer: str
    timestamp: float
    features: tuple[float, ...]

@dataclass(frozen=True)
class SageDecisionOutput:
    action: str  # HOLD, FORWARD, REPLICATE
    score: float
    reason: str
