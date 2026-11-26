# Worker protocol

The helper (the Go server, run as a binary or the Docker image) queues login jobs
from Dragonite and hands them to **workers**. A worker is anything that can run a
PTC login in a real browser engine:

- the browser extension's control tab, and
- the Android app (WebView).

Any number of workers can be connected at once. The helper sends each job to the
connected worker with the fewest jobs in progress.

```text
Dragonite --HTTP POST /api/v1/login-code--> helper <--WebSocket /ws-- worker(s)
```

## Connecting

```text
ws://<helper>:5090/ws?worker=<worker-id>&token=<WORKER_TOKEN>
wss://<reverse-proxy>/ws?worker=<worker-id>&token=<WORKER_TOKEN>
```

- `worker`: a stable ID for this worker instance, 1-64 chars of `[A-Za-z0-9._-]`.
  The extension uses one random ID per control tab, kept across reloads. The
  Android app uses one random ID per install. If a new connection arrives with the
  same ID as a connected one, the old connection is closed with code **4001**
  (replaced). A worker that receives 4001 must **not** reconnect. If omitted, the
  ID is `default`.
- Authentication, either one:
  - **Local:** the connection comes from the same machine (loopback), carries no
    `X-Forwarded-For`/`Forwarded`/`X-Real-IP` header, and has either an extension
    `Origin` (`chrome-extension://…`, `moz-extension://…`) or none. No token needed.
    This trusts every program and extension on that machine. Turn it off with
    `LOCAL_WORKERS=false` (the Docker image does) if anything on the machine
    forwards outside connections to the helper, such as a reverse proxy, `socat`,
    or an SSH tunnel.
  - **Token:** `token=<WORKER_TOKEN>` query parameter, or an
    `Authorization: Bearer <WORKER_TOKEN>` header (for clients that can set
    headers, such as OkHttp). The token works only if `WORKER_TOKEN` is set on the
    helper. Browser pages that are not extension pages are always refused.
- A refused connection gets HTTP 403 before the WebSocket upgrade.
- Jobs carry PTC passwords. Over anything except a private network (LAN,
  Tailscale, WireGuard), use `wss://` through a reverse proxy. Firefox-based
  browsers only allow `wss://` to hosts other than 127.0.0.1.

## Keepalive

The helper sends a WebSocket **ping** every 25s and drops the connection if no
pong or message arrives for 60s. Browsers and OkHttp answer pings automatically.
Workers should reconnect with backoff (2s, growing to at most 30s) after any close
except 4001.

## Messages

All messages are JSON text frames.

Helper → worker:

```json
{"type": "job", "id": "<uuid>", "username": "…", "password": "…", "url": "https://access.pokemon.com/…"}
{"type": "cancel", "id": "<uuid>", "status": "TIMEOUT | CANCELLED | ERROR"}
```

- `url` is always `https://access.pokemon.com/…`. Workers must still check this
  and answer `ERROR` otherwise.
- `cancel`: the helper gave up on the job. Close its page. Do **not** send a
  result for it.

Worker → helper:

```json
{"type": "result", "id": "<uuid>", "status": "SUCCESS | INVALID | BANNED | ERROR", "login_code": "<code, SUCCESS only>"}
{"type": "challenge", "id": "<uuid>"}
```

- `challenge`: a captcha/bot check needs a human. Only used for stats; the job
  stays open until a `result` or a `cancel`.
- If the connection drops while a job is in progress, keep the result and send it
  right after reconnecting. The helper accepts results for jobs from a dropped
  connection for 30s, then fails them with `ERROR`.

## Running a login (what a worker does)

1. Check `url` (https, host exactly `access.pokemon.com`).
2. Open it in a fresh page/WebView.
3. Inject `extension/lib/pageLogic.js`. On each DOM change, classify the page with
   `classifyPage(html, visibleText)`:
   - `login`: fill `input[name=email]` / `input[name=password]` with React-style
     value setters (see `extension/content.js`), and submit once per page load.
   - `consent`: click `[name=allow_submit]` once per page load.
   - `challenge`: tell the user (focus the tab, or a notification on Android),
     send `challenge` once, then keep watching.
   - `invalid` → result `INVALID`. `banned` → result `BANNED`.
   - `success` → result `SUCCESS` with `extractLoginCode(html)`.
4. Also watch top-level navigations. A `pokemongo://…,code=<code>` deep link, or
   any URL **not** on `access.pokemon.com` with `?code=` / `&code=`, is the login
   code: send `SUCCESS` and close the page.
5. If the page cannot be opened, send `ERROR`.
