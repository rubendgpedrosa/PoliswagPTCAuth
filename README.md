# Poliswag Passport

Poliswag Passport logs in to Pokémon Trainer Club (PTC) for Dragonite, using your
own real browser. Because the login happens in a normal, trusted browser, it gets
past the bot checks that block server-side login tools.

## How it works

You run two things on one PC (the PC that has your browser):

1. **The helper** — a single program that listens on port `5090`.
2. **The browser extension** — it does the actual PTC login in your browser.

Dragonite asks the helper for a login code. The helper asks the extension. The
extension logs in and sends the code back.

```text
Dragonite  ->  helper (port 5090)  ->  browser extension  ->  PTC login  ->  code back to Dragonite
```

Dragonite can run on the same PC or a different one. Nothing extra runs on the
Dragonite machine.

## What you need

- A PC with a browser you stay logged in to (Chrome is easiest — see below).
- The Poliswag Passport binary archive for your operating system.
- The browser extension.

Both files are on the project's **Releases** page.

## Quick start

**Chrome / Chromium / Edge / Brave:** load the unzipped `extension` folder, then
run:

```bash
./poliswag-passport chrome
```

**Firefox / Zen:** load `manifest.json` from the unzipped `extension` folder,
then run:

```bash
./poliswag-passport firefox
```

Firefox/Zen installs are temporary and must be loaded again after browser
restart.

## Setup

### 1. Start the helper

Download and extract the binary archive, open a terminal in its folder, and run
it:

```bash
./poliswag-passport chrome          # Windows: .\poliswag-passport.exe chrome
```

For Firefox/Zen, use `./poliswag-passport firefox` instead. It prints that it is
listening on port `5090`. Leave this terminal open. Stop it any time with
`Ctrl+C`.

### 2. Install the extension

Download `poliswag-passport-extension.zip` from the release and unzip it.

**Chrome / Chromium / Edge / Brave (recommended):**

1. Open `chrome://extensions`.
2. Turn on **Developer mode** (top right).
3. Click **Load unpacked** and pick the unzipped `extension` folder.

That's it — it stays installed after you restart the browser.

**Firefox / Zen:**

1. Open `about:debugging#/runtime/this-firefox`.
2. Click **Load Temporary Add-on** and pick `manifest.json` in the unzipped
   `extension` folder.

When asked, allow access to `access.pokemon.com`, `127.0.0.1`, and
`www.pokemongolive.com`.

> ⚠️ On Firefox and Zen this install is **removed when you restart the browser**,
> so you have to add it again each time. If you want an install that just stays
> put, use Chrome or another Chromium browser.

### 3. Open the control tab

After installing, a **Poliswag Passport** control tab opens (or click the
extension's toolbar icon). It should say **connected** once the helper is running.
Keep this tab open while Dragonite is running.

### 4. Point Dragonite at this PC

Find this PC's address on your network:

```bash
./poliswag-passport doctor
```

Put that address in Dragonite's config, then restart Dragonite:

```toml
[general]
remote_auth_url = "http://<this-pc-ip>:5090/api/v1/login-code"
```

## Let Dragonite reach the helper

Dragonite needs to reach this PC on port `5090`. Pick one:

**Option A — open the port to the Dragonite PC only.** Example on Linux if
Dragonite is at `192.168.1.253`:

```bash
sudo ufw allow from 192.168.1.253 to any port 5090 proto tcp
```

**Option B — no firewall change: use an SSH tunnel.** Run this on the browser PC
(replace the user and address with your Dragonite machine):

```bash
ssh -N -R 127.0.0.1:5090:127.0.0.1:5090 <user>@192.168.1.253
```

Then point Dragonite at itself:

```toml
[general]
remote_auth_url = "http://127.0.0.1:5090/api/v1/login-code"
```

> 🔒 Never open port `5090` to the internet — requests contain account passwords.

## Keep these running

While Dragonite is working, keep open:

- the helper (the terminal),
- the extension **control tab**,
- the SSH tunnel, if you used Option B.

Only keep one Poliswag Passport control tab open. If you open it in another
browser or profile, the newest one takes over.

## Troubleshooting

The control tab's warning banner shows the next command to try when it cannot
connect to the helper.

**Control tab says `disconnected`** — make sure the helper is running
(`./poliswag-passport chrome` or `./poliswag-passport firefox`), then reload or
reopen the control tab.

**Firefox/Zen says `disconnected` even though the helper runs** — your browser
profile may require secure WebSockets for extension pages. The
`./poliswag-passport firefox` command runs certificate setup automatically. If
you started with another command, stop the helper, run:

```bash
./poliswag-passport cert
```

Then start the helper again and reload the control tab. The command creates a
local `127.0.0.1` certificate, tries to trust it in Firefox-family profiles when
NSS `certutil` is available, and prints the certificate path for manual import
if automatic trust does not work.

**The login page never fills in** — keep the control tab open, use your normal
logged-in browser profile, and (Firefox/Zen) make sure you allowed
`access.pokemon.com`.

**Dragonite can't connect** — check the address with `./poliswag-passport doctor`,
confirm Dragonite uses `http://<this-pc-ip>:5090/api/v1/login-code`, and check
your firewall or SSH tunnel.

---

## Advanced

### Build from source

Needs Go installed.

```bash
cd server
go test ./...
go build -o ../poliswag-passport
```

Cross-compile for other systems:

```bash
GOOS=windows GOARCH=amd64 go build -o ../poliswag-passport.exe
GOOS=darwin  GOARCH=arm64 go build -o ../poliswag-passport-macos-arm64
```

Pushing a version tag (e.g. `v1.0.0`) makes GitHub build the binaries and the
extension zip automatically.

### Settings

The helper works with no setup. To see or change settings:

```bash
./poliswag-passport env
```

Common ones: `HOST` (`0.0.0.0`), `PORT` (`5090`), `JOB_TIMEOUT_MS`, and
`AUTH_SECRET` (if set, callers must send it in an `x-auth-secret` header).
For Firefox/Zen profiles that require secure WebSockets, run
`./poliswag-passport firefox` or `./poliswag-passport cert`. The helper uses
those generated files automatically on later starts. To use your own certificate
instead, set `TLS_KEY`, `TLS_CERT`, and optionally `WSS_PORT` (`5443`).

### Tests

```bash
(cd server && go test ./...)
(cd extension && node --test --test-force-exit test/*.js)
```

### Project layout

```text
extension/   Browser extension
server/      Go helper source
```

Generated files (the binary, `dist/`, local cert/key files, `server/.env`) are
git-ignored — don't commit them.
