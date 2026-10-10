"""The official live-info board that gateways fetch and broadcast to every phone.

Sources: current weather and the next hours' rain risk (Open-Meteo, no key needed), organizer notices posted by
the event's admin, and optionally an RSS/CAP alert feed (e.g. a state disaster authority's alert feed).
The board's seq only changes when its content changes, so gateways don't re-broadcast an identical board.
"""

from __future__ import annotations

import hashlib
import json
import logging
import threading
import time
import urllib.request
import xml.etree.ElementTree as ET

log = logging.getLogger("meshmind.bulletin")

WEATHER_CODES = {
    0: "clear sky", 1: "mainly clear", 2: "partly cloudy", 3: "overcast", 45: "fog", 48: "fog",
    51: "light drizzle", 53: "drizzle", 55: "heavy drizzle", 61: "light rain", 63: "rain", 65: "heavy rain",
    66: "freezing rain", 67: "freezing rain", 71: "light snow", 73: "snow", 75: "heavy snow", 80: "rain showers",
    81: "rain showers", 82: "violent rain showers", 95: "thunderstorm", 96: "thunderstorm with hail",
    99: "severe thunderstorm with hail",
}

KINDS = {"ALERT", "WEATHER", "NOTICE", "TRANSPORT", "INFO"}


def _now_ms() -> int:
    return int(time.time() * 1000)


class BulletinBoard:
    def __init__(self, authority, lat: float, lon: float, place: str, alert_feed: str | None) -> None:
        self.authority = authority
        self.lat, self.lon, self.place = lat, lon, place
        self.alert_feed = alert_feed
        self._notices: list[dict] = []
        self._lock = threading.Lock()
        self._cache: dict[tuple, tuple[float, list[dict]]] = {}
        self._last_hash = ""
        self._seq = 0

    # ---- organizer notices (admin endpoint) ---------------------------------------------------

    def add_notice(self, title: str, body: str, severity: int, kind: str, ttl_minutes: int) -> dict:
        if kind not in KINDS:
            raise ValueError(f"kind must be one of {sorted(KINDS)}")
        now = _now_ms()
        item = {"kind": kind, "severity": max(0, min(3, int(severity))), "title": title[:120], "body": body[:500],
                "source": "Event organizer", "issuedAt": now, "expiresAt": now + int(ttl_minutes) * 60_000}
        with self._lock:
            self._notices.append(item)
            self._notices = self._notices[-8:]
        return item

    def clear_notices(self) -> None:
        with self._lock:
            self._notices.clear()

    def notices(self) -> list[dict]:
        now = _now_ms()
        with self._lock:
            self._notices = [n for n in self._notices if n["expiresAt"] > now]
            return list(self._notices)

    # ---- feeds ---------------------------------------------------------------------------------

    def _cached(self, key: tuple, ttl_s: float, fetch) -> list[dict]:
        hit = self._cache.get(key)
        if hit and time.time() - hit[0] < ttl_s:
            return hit[1]
        try:
            items = fetch()
        except Exception as e:  # a feed being down must not take the whole board down
            log.warning("feed %s failed: %s", key[0], e)
            items = hit[1] if hit else []
        self._cache[key] = (time.time(), items)
        return items

    def _weather(self, lat: float, lon: float) -> list[dict]:
        url = ("https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f"
               "&current=temperature_2m,weather_code,wind_speed_10m"
               "&hourly=precipitation_probability&forecast_hours=6&timezone=auto" % (lat, lon))
        with urllib.request.urlopen(url, timeout=8) as r:
            d = json.load(r)
        cur = d["current"]
        desc = WEATHER_CODES.get(int(cur.get("weather_code", -1)), "")
        probs = d.get("hourly", {}).get("precipitation_probability", []) or []
        times = d.get("hourly", {}).get("time", []) or []
        rain_note = ""
        for t, p in zip(times, probs):
            if p is not None and p >= 50:
                rain_note = f" Rain likely around {t[-5:]} ({p}%)."
                break
        now = _now_ms()
        severity = 2 if int(cur.get("weather_code", 0)) >= 95 else (1 if rain_note else 0)
        return [{
            "kind": "WEATHER", "severity": severity,
            "title": f"{round(cur['temperature_2m'])}°C, {desc}".strip(", "),
            "body": f"Wind {round(cur.get('wind_speed_10m', 0))} km/h.{rain_note}".strip(),
            "source": "Open-Meteo", "issuedAt": now, "expiresAt": now + 90 * 60_000,
        }]

    def _alerts(self) -> list[dict]:
        with urllib.request.urlopen(self.alert_feed, timeout=8) as r:
            root = ET.fromstring(r.read())
        now = _now_ms()
        out = []
        for item in root.iter():
            if item.tag.split("}")[-1] not in ("item", "entry"):
                continue
            title = next((c.text for c in item if c.tag.split("}")[-1] == "title" and c.text), None)
            desc = next((c.text for c in item if c.tag.split("}")[-1] in ("description", "summary") and c.text), "")
            if title:
                out.append({"kind": "ALERT", "severity": 2, "title": title.strip()[:120], "body": (desc or "").strip()[:300],
                            "source": "Official alert feed", "issuedAt": now, "expiresAt": now + 60 * 60_000})
            if len(out) >= 3:
                break
        return out

    # ---- the signed board ----------------------------------------------------------------------

    def build(self, lat: float | None = None, lon: float | None = None) -> dict:
        lat = self.lat if lat is None else lat
        lon = self.lon if lon is None else lon
        items = list(reversed(self.notices()))
        if self.alert_feed:
            items += self._cached(("alerts",), 300, self._alerts)
        items += self._cached(("weather", round(lat, 2), round(lon, 2)), 600, lambda: self._weather(lat, lon))
        items = items[:8]
        digest = hashlib.sha256(json.dumps([{k: v for k, v in i.items() if k not in ("issuedAt", "expiresAt")} for i in items],
                                           sort_keys=True).encode()).hexdigest()
        with self._lock:
            if digest != self._last_hash:
                self._last_hash = digest
                self._seq = max(self._seq + 1, _now_ms())
            seq = self._seq
        board = {"boardId": "official", "title": f"Live info · {self.place}", "seq": seq, "issuedAt": _now_ms(), "items": items}
        return self.authority.sign_bulletin(board)
