# Poliswag Passport

Poliswag Passport logs in to Pokémon Trainer Club (PTC) for Dragonite, using a
real browser that you control. Because the login happens in a normal browser, it
gets past the bot checks that block server-side login tools.

## How it works

There are two parts:

1. **The helper** — a small Docker container that runs next to Dragonite.
   Dragonite asks it for PTC logins.
2. **A worker** — the thing that actually does the login. Pick one, or use both:
   - the **browser extension**, in a browser on any PC, or
   - the **Android app**, on a phone or Android box.

```text
Dragonite  ->  helper (Docker, port 5090)  <-  worker: browser extension and/or Android app
```

Dragonite asks the helper for a login code. The helper passes the job to a
connected worker. The worker logs in to PTC and sends the code back.

## What you need

- A server with Docker (normally the one that runs Dragonite).
- For the worker, one of:
  - a PC with Chrome, Edge, Brave or another Chromium browser, or
  - an Android 8.0+ phone or box.
- From the project's **Releases** page: the extension zip and/or the Android APK.

## Setup

### 1. Start the helper

First make a password for your workers. Run this and keep the result:

```bash
openssl rand -hex 32
```

That value is your `WORKER_TOKEN`. Then start the helper, with your value in
place of the dots:

```bash
docker run -d --name poliswag-passport --restart unless-stopped \
  -e WORKER_TOKEN=... \
  -v passport-data:/data -p 5090:5090 \
  ghcr.io/rubendgpedrosa/poliswagptcauth:latest
```

If you use Docker Compose, copy
[`deploy/docker-compose.example.yml`](deploy/docker-compose.example.yml) instead.

### 2. Point Dragonite at the helper

In Dragonite's config, then restart Dragonite:

```toml
[auth.ptc]
remote_auth_url = "http://poliswag-passport:5090/api/v1/login-code"
```

This address works when Dragonite and the helper are on the same Docker network.
Otherwise use the server's IP address in place of `poliswag-passport`.

### 3. Connect a worker

Both workers need the same two things:

- **Helper address:** `ws://<server-ip>:5090/ws`, for example
  `ws://192.168.1.10:5090/ws`.
- **Worker token:** your `WORKER_TOKEN` from step 1.

#### Option A — browser extension

1. Download `poliswag-passport-extension.zip` from the release and unzip it.
2. Open `chrome://extensions` and turn on **Developer mode** (top right).
3. Click **Load unpacked** and pick the unzipped `extension` folder.
4. A **Poliswag Passport** control tab opens (or click the extension's toolbar
   icon). In its **Helper** panel, type the helper address and the worker token,
   then click **Save**.

Keep the control tab open. It should say **connected**.

Firefox and Zen also work, with two limits: the extension is removed every time
the browser restarts, and they only accept a `wss://` helper address (see
[Using it away from home](#using-it-away-from-home-tailscale)). To load it there,
open `about:debugging#/runtime/this-firefox`, click **Load Temporary Add-on**
and pick `manifest.json` in the unzipped `extension` folder.

#### Option B — Android app

1. Install `poliswag-passport-android.apk` from the release.
2. Open the app, type the helper address and the worker token, and tap **Start**.

It should say **Connected**. The app keeps working in the background and starts
again after a reboot. The full guide, including captchas and proxies, is in
[`android/README.md`](android/README.md).

### 4. Done

Once a worker says connected, Dragonite's PTC logins go through it. You can
connect several workers (more control tabs, more phones); the helper shares the
logins between them.

## Keep these running

- The helper container (it restarts by itself).
- At least one worker: the extension's **control tab**, or the Android app.

Each control tab is its own worker. If you **duplicate** a control tab, the copy
shares its identity with the original and one of them stops with "connection
replaced"; close the duplicate.

## Captchas

Sometimes PTC shows a captcha that a person has to solve. The extension brings
the login tab to the front; the Android app shows a notification. Solve it and
the login continues.

## Troubleshooting

**The worker says disconnected / not connected.** Check, in this order:

1. The helper is running: `docker ps` should list `poliswag-passport`.
2. The helper address is right, and uses the server's IP, not `127.0.0.1`.
3. The worker token is exactly the `WORKER_TOKEN` you started the helper with.
4. Port `5090` on the server is reachable from the worker's device (firewall).

The control tab and the Android app both say which of these is the likely cause.

**Dragonite can't connect.** Check that `remote_auth_url` points at the helper
and that Dragonite can reach port `5090`. This should print `"status":"OK"`:

```bash
curl http://<server-ip>:5090/health
```

It also shows how many workers are connected.

**The login page never fills in (extension).** Keep the control tab open and
make sure the extension is allowed to access `access.pokemon.com`.

**Logins time out.** With no worker connected, a login waits until one connects
or 10 minutes pass. Check that a worker is connected.

## Using it away from home (Tailscale)

Workers do not have to be on the same network as the helper. The easy way is a
private VPN such as [Tailscale](https://tailscale.com) (WireGuard or ZeroTier
work the same way):

1. Install Tailscale on the server that runs the helper, and on the phone or PC
   that runs the worker. Sign both in to the same account.
2. In the worker, use the server's Tailscale address as the helper address, for
   example `ws://100.101.102.103:5090/ws`. The Tailscale app shows each
   device's `100.x.y.z` address.

That is all. The worker now connects from anywhere: home Wi-Fi, mobile data, a
friend's house. Tailscale encrypts the connection, so a plain `ws://` address
is fine and no port has to be opened to the internet.

This is handy with the Android app: carry the phone with you, and when PTC
shows a captcha you get a notification and can solve it wherever you are. The
logins then go out through the phone's own connection (for example mobile
data), not through your home network.

Firefox and Zen need a `wss://` address for anything except `127.0.0.1`. With
Tailscale you can get one by running `tailscale serve --bg 5090` on the server
and using `wss://<server-name>.<your-tailnet>.ts.net/ws`.

> [!WARNING]
> Logins contain PTC passwords. A `ws://` address sends them unencrypted, so use
> it only on your own network or over a VPN like Tailscale. Never open port
> `5090` to the internet as it is. Without a VPN, put a reverse proxy with HTTPS
> in front of the helper and use a `wss://` address.

## When PTC blocks your connection

PTC's bot check sometimes blocks a whole internet connection. The login page
then shows an error (often "Error 15") instead of the login form, and every
login from that connection fails, whatever worker you use.

- **Android app:** put the phone on another connection (mobile data usually
  works), or add proxies in the app. See
  [`android/README.md`](android/README.md#proxies-optional).
- **Browser extension:** it uses your browser's connection. Use a browser that
  goes out through a connection PTC accepts.

## What has been tested

With real PTC logins, against a real Dragonite:

- The browser extension in Chrome, connected to the Docker helper.
- The Android app on a real phone, connected to the Docker helper, with logins
  going through a proxy. It also reconnected by itself after a reboot.

Not yet seen with a real login: solving a captcha in the Android app, and the
Android app without a proxy.

---

## Optional: run the helper without Docker

The helper is also available as a single program (a Go binary) on the Releases
page. Use it if you would rather run everything on your browser PC, with no
Docker and no token: the extension on the same PC connects by itself.

```text
Dragonite  ->  helper program on your PC (port 5090)  <-  extension in your browser
```

1. Download and extract the archive for your system, open a terminal in that
   folder, and start it:

   ```bash
   ./poliswag-passport chrome          # Windows: .\poliswag-passport.exe chrome
   ```

   For Firefox/Zen use `./poliswag-passport firefox`, which also sets up the
   local certificate those browsers need. Leave the terminal open; stop with
   `Ctrl+C`.

2. Install the extension as in [Option A](#option-a--browser-extension). Leave
   the **Helper** panel empty: the control tab finds the helper on this PC and
   says **connected**.

3. Find this PC's address and point Dragonite at it:

   ```bash
   ./poliswag-passport doctor
   ```

   ```toml
   [auth.ptc]
   remote_auth_url = "http://<this-pc-ip>:5090/api/v1/login-code"
   ```

Dragonite must be able to reach this PC on port `5090`. Pick one:

- **Open the port to the Dragonite machine only.** On Linux, if Dragonite is at
  `192.168.1.50`:

  ```bash
  sudo ufw allow from 192.168.1.50 to any port 5090 proto tcp
  ```

- **Or use an SSH tunnel, with no firewall change.** Run this on the browser PC,
  then use `http://127.0.0.1:5090/api/v1/login-code` in Dragonite:

  ```bash
  ssh -N -R 127.0.0.1:5090:127.0.0.1:5090 <user>@192.168.1.50
  ```

**Firefox/Zen says disconnected although the helper runs.** Stop the helper, run
`./poliswag-passport cert`, start it again and reload the control tab. The
command creates a local certificate and tries to make Firefox trust it; if that
fails it prints the certificate path so you can import it by hand.

The program can also serve the Android app or an extension on another PC: set
`WORKER_TOKEN` before starting it, and `./poliswag-passport doctor` lists the
addresses to use.

## For developers

### Settings

Settings are environment variables. `./poliswag-passport env` prints the current
values.

| Setting | Default | What it does |
|---|---|---|
| `WORKER_TOKEN` | not set | Password for workers on other machines. Required in Docker. |
| `AUTH_SECRET` | not set | Optional password for Dragonite's side. If set, every login request must carry it in an `x-auth-secret` header. |
| `HOST` | `0.0.0.0` | Network address the helper listens on. |
| `PORT` | `5090` | Port the helper listens on. |
| `JOB_TIMEOUT_MS` | `600000` | How long a login may take (10 minutes) before the helper gives up. |
| `LOCAL_WORKERS` | `true` (`false` in Docker) | Lets the extension on the helper's own PC connect without the token. |
| `TLS_KEY`, `TLS_CERT`, `WSS_PORT` | made by `cert` | Your own certificate for secure connections, and its port (`5443`). |

Set `LOCAL_WORKERS=false` if anything on the helper's machine passes outside
connections on to it (a reverse proxy, `socat`, an SSH tunnel to this port).
Otherwise those connections would count as local and skip the token.

### Security notes

- Only workers can connect to the helper: the extension on the helper's own PC
  (binary only), or anything that presents `WORKER_TOKEN`. Ordinary websites are
  always refused.
- Requests sent by a web browser are refused, so websites you visit cannot
  submit login jobs. Job URLs must be `https://access.pokemon.com/...`.
- Without `AUTH_SECRET`, anyone who can reach port `5090` can ask the helper for
  PTC logins. Keep the port on a private network.

### Build from source

Needs Go, Node.js (extension tests) and JDK 17+ (Android app).

```bash
(cd server && go test ./... && go build -o ../poliswag-passport)
(cd server && docker build -t poliswag-passport .)
(cd extension && node --test --test-force-exit test/*.js)
(cd android && ./gradlew assembleDebug testDebugUnitTest)
```

Pushing a version tag (for example `v1.0.0`) makes GitHub build and publish the
Docker image, the binaries, the extension zip and the APK.

### Project layout

```text
server/      Helper (Go) and its Dockerfile
extension/   Browser extension (worker)
android/     Android app (worker)
deploy/      Docker Compose example
docs/        How helper and workers talk to each other
```

The messages between helper and workers are described in
[`docs/worker-protocol.md`](docs/worker-protocol.md).

Generated files (the binary, `dist/`, local cert/key files, `server/.env`) are
git-ignored — don't commit them.
