# MeshMind backend

This service is the part of MeshMind that runs on a laptop or server with Internet. Phones reach it in one of two ways:

- through a gateway phone (for Ask, Read and live info), or
- directly, while they still have Internet, to verify their phone number once.

It does three jobs:

| Job | Endpoints | What it does |
|---|---|---|
| Identity authority | `POST /v1/enroll/start`, `POST /v1/enroll/verify`, `GET /v1/authority` | Verifies a phone number by OTP, then issues a signed certificate binding a node ID to that phone's key. Roles: USER, ORGANIZER, RESPONDER. Organizer and responder roles need the event's organizer code. |
| Answers | `POST /v1/ask`, `POST /v1/read` | Ask: search plus AI (Gemini or Claude), at most 80 words, with up to 3 sources. Read: the plain text of any public HTTPS page. Every answer is signed by the authority key. |
| Live info | `GET /v1/bulletin`, `POST`/`DELETE /v1/notices` | A signed board containing weather (Open-Meteo, no key needed), organizer notices and an optional RSS/CAP alert feed. Gateways broadcast it to every phone. |

## Run it

```powershell
cd backend
uv venv .venv --python 3.12
uv pip install --python .venv\Scripts\python.exe -r requirements.txt

copy .env.example .env      # put your GEMINI_API_KEY in it (git-ignored)
.venv\Scripts\python -m meshmind_backend.server
```

`backend/.env` picks the provider:

| Provider | When | Model | Notes |
|---|---|---|---|
| `gemini` (default when `GEMINI_API_KEY` is set) | Hackathons and the free tier | `gemini-3.5-flash-lite` | Free tier; $0.30 / $2.50 per 1M tokens when paid. About 1.4 s per answer, measured. `gemini-3.1-flash-lite` is a little cheaper but took about 20 s per answer; `gemini-2.5-flash-lite` is refused for new keys. Google Search grounding needs billing enabled on the Google project; on the free tier answers come without web search and are marked "(no web search)". |
| `claude` | `ANTHROPIC_API_KEY` set | `claude-opus-5-5` (`MESHMIND_MODEL`) | Uses Anthropic's web search, with server-side refusal fallback. |
| `demo` | No key | none | Canned answers labelled "demo", for rehearsals. |

`MESHMIND_DEMO_OTP=1` (the default in `.env.example`) shows the OTP in the app instead of sending an SMS.

On startup it prints three things: the authority key ID, the provider in use, and an admin token for posting notices.

To let a gateway phone reach it, either put the phone on the same Wi-Fi and use `http://<laptop-ip>:8787`, or put it behind HTTPS with a tunnel or Cloud Run. Plain http only works in **debug** APKs; release builds require https.

Useful options:

- `--model claude-opus-5-5`: the default model. Any Claude model ID works. Haiku is cheaper; if you choose it, the server-side refusal fallback is switched off automatically.
- `--effort low`: keeps answers fast.
- `--context "They are at <event> in <city>, India."`: tells the model where people are.
- `--lat/--lon/--place`: the location used for the weather item.
- `--alert-feed <rss-url>`: adds an official alert feed to the board.
- `--organizer-code <code>`: the code needed to verify as organizer or responder. Default is `demo-organizer`.
- `--log-queries`: stores question text in `data/audit.jsonl`. By default only a hash and the length are stored.
- `--retention-days 180`: how long audit entries are kept.

Post an organizer notice from the laptop:

```powershell
curl -X POST http://localhost:8787/v1/notices -H "X-Admin-Token: <token>" -H "Content-Type: application/json" `
     -d '{"title":"Gate B closed","body":"Use Gates A or C","severity":2,"ttlMinutes":60}'
```

## Files it keeps (`backend/data/`, not committed)

- `authority_key.pem`: the authority's private key. If it is lost, every phone must verify again. If it leaks, anyone can forge certificates and answers. Keep it off shared machines.
- `registry.json`: which phone number holds which node ID (for accountability). Phone numbers never leave the backend; certificates carry only the node ID and a "phone verified" flag.
- `audit.jsonl`: an append-only log of who enrolled and who asked, recording what kind of request and when.

## Tests

```powershell
.venv\Scripts\python -m unittest discover -s tests -v   # canonical bytes, signatures, API end to end
.venv\Scripts\python tools\make_vectors.py              # refresh the vectors BackendInteropTest (Kotlin) verifies
```

## Not done yet

- SMS delivery. `enroll_start` raises 501 unless `--demo-otp` is set. Plug in MSG91, Twilio or another provider at the marked line.
- Persistence of the answer cache and notices across restarts. Both are kept in memory only.
