# Poliswag Passport — Android app

This app turns an Android phone or box into a **worker** for Poliswag Passport. The
helper sends it PTC logins; the app does each login in a built-in browser and sends
the login code back.

It runs in the background, so it can share a device with another app (for example a
scanner device that is running the game).

## What you need

- An Android 8.0 or newer device.
- A running Poliswag Passport helper with `WORKER_TOKEN` set. See the
  [main README](../README.md#setup).
- The APK from the project's **Releases** page.

## Setup

1. Install the APK and open **Poliswag Passport**.
2. Allow notifications when asked.
3. Fill in the two boxes:
   - **Helper address:** the helper's address, for example
     `ws://192.168.1.10:5090/ws`.
   - **Worker Token:** the same value as `WORKER_TOKEN` on the helper.
4. Tap **Allow background page window** and switch on "Display over other apps".
   This is optional but recommended (see below).
5. Tap **Turn off battery saving for this app** so Android does not put the app to sleep.
6. Tap **Start**.

The status at the top should say **Connected**, with a green dot. That's it: leave the app alone and it does
its job. A permanent notification shows that it is running.

If it does not connect, the status says why (wrong token, helper not running,
wrong address).

> [!WARNING]
> Logins contain PTC passwords. Use a `ws://` address only on your own network or
> over a VPN such as Tailscale. Otherwise put the helper behind HTTPS and use a
> `wss://` address.

## Take the phone with you (Tailscale)

The phone does not have to stay at home. Install [Tailscale](https://tailscale.com)
on the phone and on the server that runs the helper, sign both in to the same
account, and use the server's Tailscale address as the helper address, for example
`ws://100.101.102.103:5090/ws`.

The app then stays connected on mobile data or any Wi-Fi. When PTC shows a captcha
you get the notification wherever you are and can solve it on the spot. Logins go
out through the phone's own connection.

## Good to know

- **It restarts by itself.** After you tap **Start**, the app comes back on its own
  after a reboot or an app update, until you tap **Stop**. Some phones (Xiaomi,
  Samsung, OnePlus, Huawei) have their own "autostart" or battery setting that you
  must also allow.
- **One login at a time.** If several arrive together, they wait in line.
- **Why the "background page window"?** With that permission, the login page lives
  in a 1-pixel window in a corner of the screen. You will not notice it, and touches
  still go to the app you are using. It makes the login page behave like a page in a
  normal, open browser, which should mean fewer bot checks. Without the permission
  the app still works, with the page fully hidden.
- **Accounts never mix.** Before each login the app wipes the browser's cookies and
  saved site data, so one account cannot end up signed in as another.
- **The bot check remembers the device.** The app keeps only the cookies of the
  site's bot check, and keeps a separate set for each proxy (and one for direct
  logins). To the site, every proxy then looks like its own returning visitor. A set
  is thrown away when a login through that proxy fails or is cancelled, so a block
  picked up on one proxy never follows the device to another.

## If logins keep failing

- **The page shows an error instead of the login form** (often "Error 15"): PTC's
  bot check is blocking the connection the phone is using. Switch the phone to
  another connection (mobile data usually works) or add proxies below.
- **Some logins take longer** (around 15 seconds against the usual 5): that is the
  bot check treating the phone as a new visitor. It happens on the first login
  through each proxy, and on the one after a failed login.

## Captchas

Sometimes PTC shows a captcha or another bot check that a person has to solve.

1. The app shows a notification: **PTC challenge needs attention**.
2. Tap it (or tap **Open captcha to solve** in the app).
3. Solve the captcha on screen.

The screen closes by itself when the login finishes.

## Proxies (optional)

To send PTC logins through proxies, type them in the **Proxies** box, one per line.
Each login uses the next proxy on the list, in turn. Leave the box empty to connect
directly.

```text
host:port
host:port:user:pass
user:pass@host:port
socks5://host:port
```

- A line without `http://`, `https://` or `socks5://` in front is treated as an HTTP
  proxy.
- A SOCKS5 proxy with a username and password does not work (Android does not
  support it). Use an HTTP proxy, or a SOCKS5 proxy that allows your IP address.
- If a proxy is down or rejects the password, that login fails. The app never
  quietly connects without the proxy.
- Only the PTC login goes through the proxy. The connection to the helper, and
  every other app on the device, are not affected.
- Proxies and the worker token are saved on the device without encryption.

## Building it yourself

You need JDK 17, 21 or 25.

```bash
cd android
./gradlew assembleDebug testDebugUnitTest
```

The APK ends up in `app/build/outputs/apk/debug/`.

The build copies `../extension/lib/pageLogic.js` into the app, so the browser
extension and the Android app read the login page with the same code.

For a signed release build, set `POLISWAG_KEYSTORE_FILE`,
`POLISWAG_KEYSTORE_PASSWORD`, `POLISWAG_KEY_ALIAS` and `POLISWAG_KEY_PASSWORD`, then
run `./gradlew assembleRelease`.
