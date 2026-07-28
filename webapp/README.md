# Badge Scanner — Webapp

Desktop counterpart to the Android app in `../mobile`: same badge → Wiegand
→ CA → 42 intranet flow, same CA/42 fetch semantics and caching rules, but
backed by a shared Go server (SQLite) with real user accounts instead of
per-device storage, and a USB NFC reader instead of a phone's NFC radio.

## Layout

- `backend/` — Go REST API: CA/42 clients, SQLite storage, auth.
- `frontend/` — React + TypeScript (Vite) webapp.
- `reader-agent/` — small Go binary that runs on the operator's PC and
  bridges a PC/SC USB NFC reader (e.g. the ACR122U) to the browser over a
  loopback WebSocket, since browsers can't talk to PC/SC directly.

## How the pieces fit together

```
 USB reader (ACR122U, PC/SC)
        │
        ▼
  reader-agent  ──ws://127.0.0.1:17420/ws──▶  browser (frontend)
                                                   │
                                                   │ HTTPS + client-id/secret
                                                   │ + session cookie
                                                   ▼
                                              backend (Go + SQLite)
                                                   │
                                          ┌────────┴────────┐
                                          ▼                 ▼
                                    CA (ibox4)         42 API (intra)
```

The reader-agent and the browser both run on the operator's machine; the
backend can run there too, or on a shared server other operators point
their browsers at.

## Auth model (as requested)

1. **API client/secret** — every backend request must carry
   `X-Client-Id` / `X-Client-Secret` headers matching the server's
   `API_CLIENT_ID` / `API_CLIENT_SECRET`. The frontend bakes these in at
   build time (`VITE_API_CLIENT_ID` / `VITE_API_CLIENT_SECRET`). **This is
   a coarse app-level gate, not a strong secret** — anyone with devtools
   access to the built frontend can read it. Treat it as blocking casual/
   direct API probing, not as your real security boundary. The actual
   boundary is (2) below plus where you expose the backend (put it behind a
   VPN / reverse proxy / IP allowlist if it's reachable outside your LAN).
2. **User accounts** — username + bcrypt password, JWT session cookie.
   On first run, if no users exist yet, the backend creates one admin from
   `ADMIN_USERNAME` / `ADMIN_PASSWORD` env vars. Every other account is
   created from the Admin page by an existing admin — there's no public
   signup.

## Running it

### Docker Compose (backend + frontend)

The quickest way to run the backend and frontend together. The
reader-agent is deliberately **not** in this compose file — it needs direct
access to a USB PC/SC reader on the operator's own machine, which doesn't
make sense to containerize on a server; run it natively there (see below).

```bash
cd webapp
cp .env.example .env
# edit .env: set API_CLIENT_ID/SECRET, JWT_SECRET, ADMIN_USERNAME/PASSWORD
docker compose up -d --build
```

This builds and starts two containers:

- `backend` — the Go server, SQLite data persisted in the `backend-data`
  named volume (survives `docker compose down`; use `down -v` to wipe it).
- `frontend` — an nginx container serving the built static assets and
  reverse-proxying `/api/` and `/health` to the backend service. The
  browser only ever talks to this one origin, so the session cookie and
  client-id/secret headers work with no CORS setup — `VITE_API_BASE` is
  baked in as `""` (relative) for exactly this reason.

Once it's up, open `http://localhost:8000` (or `$FRONTEND_PORT`), log in as
the bootstrapped admin, and set the CA/42 credentials from the Admin page.

To rebuild after changing frontend build-time vars (client id/secret,
reader-agent URL) or backend code: `docker compose up -d --build`.

### Backend (without Docker)

```bash
cd backend
go build -o badgescanner-server ./cmd/server
API_CLIENT_ID=... API_CLIENT_SECRET=... JWT_SECRET=$(openssl rand -hex 32) \
  ADMIN_USERNAME=admin ADMIN_PASSWORD=... \
  ./badgescanner-server
```

Env vars:

| Var | Required | Notes |
|---|---|---|
| `API_CLIENT_ID` / `API_CLIENT_SECRET` | yes | must match the frontend's build-time values |
| `JWT_SECRET` | yes | signs session cookies; any long random string |
| `ADMIN_USERNAME` / `ADMIN_PASSWORD` | only until the first user exists | bootstraps the one hardcoded admin |
| `DB_PATH` | no (default `badgescanner.db`) | SQLite file path |
| `LISTEN_ADDR` | no (default `:8080`) | |
| `COOKIE_SECURE` | no (default `true`) | set to `false` only for plain-HTTP local dev — browsers won't store a `Secure` cookie over HTTP |

Once running, log in as the bootstrapped admin and set the CA/42
credentials, closer id, and campus id from the **Admin** page (these
replace the mobile app's per-device Settings screen — one shared
configuration for every operator now).

`go test ./...` runs the ported-logic tests (Wiegand, cluster SVG parser,
piscine login parsing — verified against the same fixtures as the Kotlin
unit tests).

### Frontend (without Docker)

Needs Node.js (developed against Node 20) — not installed on this machine;
install it first (`apt install nodejs npm`, or a version manager).

```bash
cd frontend
cp .env.example .env   # fill in VITE_API_BASE / client id+secret
npm install
npm run dev             # dev server, http://localhost:5173
npm run build            # type-checks + production build to dist/
```

### Reader agent

Needs `pcscd` running and PC/SC dev headers to build:

```bash
sudo apt install pcscd libpcsclite-dev
cd reader-agent
go build -o reader-agent .
./reader-agent
```

It listens on `127.0.0.1:17420` (loopback only): `GET /health` reports
whether a reader was found, and `GET /ws` is what the frontend's Scan page
connects to. Run it once per operator machine, alongside the browser.

**Mixed-content note**: if the frontend is served over HTTPS, browsers
block a page from opening a plain `ws://` connection to the reader-agent
(mixed content) — there's no exception for loopback destinations. For this
kind of internal LAN tool the simplest fix is serving the frontend over
plain HTTP on the local network; wiring up `wss://` with a locally-trusted
cert is possible but adds real complexity for little benefit here.

If you don't have the reader-agent running yet (or are testing without
hardware), the Scan page also has a manual UID-hex entry field.

## What's ported 1:1 from the Android app

See the plan/commit history for the full list, but the load-bearing bits:
Wiegand candidate codes (`wiegand26`/unpadded/premium), the CA's paginated
user listing + `isListable` filtering + TLS-pinned self-signed cert, the
42 API's `client_credentials` token cache + 429 backoff + 404-as-empty
handling for coalitions, the 12h intra cache TTL, "CA directory only
refreshes on demand and clears manual badge links when it does", the
cluster SVG's two transform styles, and the coalition-pick priority order
(harkonnen/corrino/atreides > hordes/alliance > highest score).
