# LeanVPN for Android

A Proton VPN client with no screens at all: it's a **Quick Settings tile**. Tap it to connect,
tap again to disconnect. LeanVPN puts you on the fastest server that isn't overloaded, and moves
you to another one when it fills up or stops answering.

> **Unofficial.** LeanVPN is an independent project. It is not made, endorsed or supported by
> Proton AG. "Proton" and "Proton VPN" are trademarks of Proton AG. You need your own Proton
> account. Use it at your own risk (see [Disclaimer](#disclaimer)).

**Status: alpha.** It has been tested on one phone. See [Status and testing](#status-and-testing).

Website: <https://zephyrsai.github.io/LeanVPN-Android/>. There is also a Mac version: [LeanVPN for macOS](https://github.com/ZephyrSai/LeanVPN).

## Contents

- [Why](#why)
- [Footprint](#footprint)
- [Requirements](#requirements)
- [Install](#install)
- [First run](#first-run)
- [Everyday use](#everyday-use)
- [Always-on VPN and a kill switch](#always-on-vpn-and-a-kill-switch)
- [How it picks a server](#how-it-picks-a-server)
- [How it stays connected](#how-it-stays-connected)
- [Privacy and security](#privacy-and-security)
- [Troubleshooting](#troubleshooting)
- [Updating and uninstalling](#updating-and-uninstalling)
- [Status and testing](#status-and-testing)
- [Building from source](#building-from-source)
- [Deliberately not included](#deliberately-not-included)
- [Disclaimer](#disclaimer)
- [License and credits](#license-and-credits)

## Why

Free Proton VPN servers get congested, and a busy server means a connection that stalls until you
open the app and pick another one. LeanVPN does that job for you. It measures real latency, picks
the best server, keeps checking it, and switches before you notice. Everything else the official
app does is left out, including the app itself: what remains is one tile in your Quick Settings.

## Footprint

| | |
|---|---|
| Download and installed size | **3.4 MB**. 3.3 MB of that is the WireGuard engine; all Java code is 64 KB compressed. The engine is stored uncompressed and mapped in place, so nothing is extracted on install. |
| Permissions | **Two**: internet and network state. No notifications, location, storage, contacts or phone. |
| Screens | **None**. You'll only ever see Android's own VPN prompt, the sign-in web page and a small long-press menu. |
| Background work | **None while disconnected.** While connected, a counter check every 10 seconds when the phone is awake. It never wakes the phone. |
| Libraries | Android's built-in classes and WireGuard's official tunnel library. No AndroidX, Kotlin, Compose, Google Play Services, analytics or crash reporting. |

## Requirements

- Android 10 or later, on a 64-bit ARM phone (practically every phone from the last several years)
- A Proton account. **Free works** (free servers only); paid plans can use every non-Secure-Core server.

## Install

### Download the APK

1. On your phone, open the [Releases page](https://github.com/ZephyrSai/LeanVPN-Android/releases/latest)
   and download `LeanVPN-<version>.apk`.
2. Open the downloaded file. Android asks you to allow installing apps from your browser (or Files
   app) once; allow it, go back and tap **Install**.
3. Google Play Protect may say the app is from an unknown developer. That's because it isn't from
   the Play Store; choose **Install anyway**. The source is all here if you'd rather build it
   yourself.

Every release is signed with the same key. Its certificate's SHA-256 fingerprint is

```
337d457a61be6e17dc516dcc523c86c06bcf313e286ba4683868f6ac125c4a67
```

and each release lists its APK's SHA-256, so you can check that what you downloaded is genuine
(apps like AppVerifier do this on the phone).

### Or build it yourself

See [Building from source](#building-from-source). It takes a minute, no Android Studio needed.

## First run

1. **Open LeanVPN once** from your app drawer. Android asks *"Add LeanVPN tile to Quick
   Settings?"* Tap **Add tile**. (On Android 12 and older, edit your Quick Settings and drag the
   LeanVPN tile in yourself.)
2. **Android asks to allow the VPN connection** ("LeanVPN wants to set up a VPN connection…").
   Tap **OK**. This happens once.
3. **The sign-in page opens** in your browser at `account.protonvpn.com/appletv`, and an
   8-character code is already on your clipboard (a message shows it too, and the tile's second
   line says *Code XXXX XXXX*). Sign in to Proton on that page if asked, paste the code and
   approve.
4. **Pull down Quick Settings.** Within a few seconds the tile switches to a server name and its
   load, for example *NL-FREE#93 · 54%*. You're connected.

LeanVPN never sees your password. It uses Proton's "enter a code on the web" sign-in, the same
flow the Proton VPN TV apps use.

**If the sign-in page won't load** (some networks block `protonvpn.com`): switch the phone to mobile
data for a moment, or open `account.protonvpn.com/appletv` on any other device, a computer for
example, and type the code shown on the tile there. The code is valid for about 10 minutes; tap the
tile for a fresh one.

## Everyday use

Tap the tile to connect or disconnect. When the phone is locked, Android asks you to unlock first.

| Tile | Second line | Meaning |
|---|---|---|
| Off | *Tap to sign in* | Not signed in yet. Tap to start. |
| Off | *Code XXXX XXXX* | Waiting for you to enter the code on the web. Tap to reopen the page. |
| Off | *Off* | Signed in, disconnected. Tap to connect. |
| Off | *Tap to allow the VPN* | Android needs your OK again (or another VPN app took over). Tap. |
| On | *NL-FREE#93…* | Connecting to that server. |
| On | *NL-FREE#93 · 54%* | Connected: server and its current load. |
| On | *→ NL-FREE#41* | Switching servers, because the last one stalled or filled up. |
| On | *Waiting for network* | No internet right now. It continues when it's back. |
| On | *Retrying…* | The last attempt failed; it retries by itself, and right away when your network comes back. |

**Long-press the tile** for a small menu: where you're connected (server, city, load, latency),
**Switch server** (jump to the next best), **Disconnect** and **Sign out**. Opening the app from
the app drawer shows the same menu once the tile is added.

## Always-on VPN and a kill switch

LeanVPN has no kill switch of its own, because Android has a better one built in:

- **Settings → Network & internet → VPN → LeanVPN (⚙) → Always-on VPN.** Android starts LeanVPN at
  boot and keeps it running; LeanVPN connects by itself when it does.
- **Block connections without VPN** (same screen) is the kill switch. Nothing goes out unless the
  tunnel is up, including during server switches.

The exact menu names vary a little between phone makers.

## How it picks a server

1. **Shortlist.** From Proton's server list for your plan (skipping Secure Core, Tor, restricted
   servers and servers in maintenance): the 10 best by Proton's own score (distance and load from
   where you are), plus the 6 least loaded of the 60 nearest.
2. **Measure.** Real latency to every candidate at the same time, a TCP connect to each over your
   normal connection (never through the tunnel). About a third of a second in total.
3. **Score.** Lowest `latency_ms + 300 × load²` wins (load from 0 to 1). Load hardly matters
   while it's low, but it dominates as a server nears saturation: a 40 ms server at 90% load
   scores 283 and loses to a 90 ms server at 30% load, which scores 117.
4. **Verify.** A WireGuard handshake isn't proof a server works (overloaded servers often complete
   one), so LeanVPN also needs an answer from Proton's in-tunnel DNS resolver before it calls itself
   connected. If there's no answer, it tries the next server, up to four, rotating through
   Proton's alternative WireGuard ports in case your network blocks the default one.

## How it stays connected

While connected, every 10 seconds (only while the phone is awake) LeanVPN reads two counters from
WireGuard.

- **Stalled** (sending but receiving nothing for 20 s), or no handshake for over 200 s: it asks
  the in-tunnel resolver. No answer means it switches to the next best server and avoids the dead
  one for 30 minutes.
- **Every 15 minutes:** it refreshes server loads. If yours is 85% full or more and one at least
  20 points lighter exists, it moves. A server going into maintenance is left right away.
- **If a switch fails**, the tunnel is taken down rather than left routing traffic nowhere. A
  switch you asked for, or one made because of load, goes back to the server that was working.
- **Network changes** (Wi-Fi ↔ mobile data) are handled by WireGuard's roaming; if traffic stalls
  anyway, the checks above catch it.
- **Certificates and sign-in tokens** renew themselves. If Proton's API can't be reached, a
  still-valid certificate and the cached server list are enough to connect.

## Privacy and security

- **No password** passes through LeanVPN. The session tokens and the WireGuard key live in the
  app's private storage, which other apps can't read; backups of it are switched off.
- LeanVPN only talks to `vpn-api.proton.me` and the VPN servers. No analytics, crash reporting,
  ads, Google services or update checks.
- While connected, all traffic goes through the tunnel and DNS goes to Proton's resolver. IPv6 is
  carried on servers that support it; on the others it's routed into the tunnel and dropped, so it
  can't leak around it.
- LeanVPN's own requests to Proton's API, and its latency probes, deliberately go **around** the
  tunnel, so a dead tunnel can never cut LeanVPN off from Proton. Proton sees your real IP address
  on those requests, just as it does when the official app signs in.
- **It identifies itself as Proton's Apple TV app.** Proton's code page only approves codes for
  that app, so LeanVPN uses that app's identifier to sign in.
- **Restrictive networks.** Some networks silently drop the TLS handshake to Proton's API.
  LeanVPN sends the first handshake packet in two pieces (at a different point on each retry),
  which gets past the filters it has met so far.

## Troubleshooting

| Problem | What to do |
|---|---|
| No LeanVPN tile | Open LeanVPN from the app drawer (it offers to add the tile), or edit Quick Settings and drag it in. |
| Sign-in page is blank or won't load | Your network blocks `protonvpn.com`. Use mobile data, or open `account.protonvpn.com/appletv` on another device and type the code from the tile. |
| Tile still says *Code …* after you approved | Pull down Quick Settings and wait a few seconds; that wakes LeanVPN to finish. If the code expired (about 10 min), tap the tile for a new one. |
| *Tap to allow the VPN* | Tap the tile and accept Android's prompt. It appears again whenever another VPN app has been used. |
| *Retrying…* for a long time | Long-press the tile to see the reason. Usually no network, or a network that blocks VPNs; it keeps retrying by itself. |
| Disconnects after a while (some OnePlus, Xiaomi, Samsung phones) | Settings → Apps → LeanVPN → Battery → **Unrestricted**, and turn on Always-on VPN. |
| Another VPN app stops working, or LeanVPN does | Android allows one VPN at a time. Starting one stops the other. |
| Connected but slow | Long-press → **Switch server**. If it keeps picking the same far server, the closer ones are probably busy at the moment. |

**Logs**, with the phone connected to a computer (USB debugging on):

```sh
adb logcat -s LeanVPN
```

They show the candidates considered at each connect (`candidates: NL-FREE#93 129ms 54%, …`), each
switch and its reason (`leaving …`), and any errors. Please include them in bug reports.

## Updating and uninstalling

- **Update:** install the newer APK over the old one. Your sign-in is kept (both must be signed
  with the same key, which every official release is).
- **Uninstall:** long-press the tile → **Sign out** first (this ends the session on Proton's
  side), then uninstall LeanVPN as usual. Uninstalling removes everything it stored.

## Status and testing

Alpha. What has actually been verified:

- On one OnePlus phone: install, adding the tile, the VPN permission, the code sign-in (code entered
  from a computer), and connecting automatically afterwards.
- On a computer, against the same code: the keys are byte-for-byte identical to Apple CryptoKit's
  (the Mac version's), server list parsing and caching, the parallel latency probe, and live HTTPS to
  Proton's API through a network that blocks it.
- The same server choice and failover logic has been in daily use in the Mac version.

Not yet tested on Android: failover between servers, sessions of days, Wi-Fi ↔ mobile data while
connected, Always-on VPN at boot, battery use, Android 10–15, other phone makers, paid plans. Bug
reports with logs are very welcome.

## Building from source

You need a JDK and the Android SDK command-line tools: platform 35 and build-tools 35. No Gradle,
no Android Studio. On a Mac:

```sh
brew install openjdk@21 && brew install --cask android-commandlinetools
sdkmanager --sdk_root="$HOME/Library/Android/sdk" "platforms;android-35" "build-tools;35.0.0" "platform-tools"
git clone https://github.com/ZephyrSai/LeanVPN-Android.git
cd LeanVPN-Android
make            # → build/LeanVPN.apk
make install    # installs on a phone connected with USB debugging (adb)
```

| Variable | Default | |
|---|---|---|
| `SDK` | `~/Library/Android/sdk` | Android SDK location |
| `JAVA_HOME` | from `/usr/libexec/java_home`, else Homebrew's openjdk@21 | JDK 17 or newer |
| `ABI` | `arm64-v8a` | `armeabi-v7a` for old 32-bit phones |
| `KEYSTORE` / `KS_PASS` | `~/.android/leanvpn.keystore` / `android` | Signing key; created on first build |

**Making a release:** bump `VERSION` and `CODE` in the Makefile, then `make release`. It writes
`build/LeanVPN-<version>.apk` and prints the two fingerprints for the release notes.

The build downloads WireGuard's Android library (`com.wireguard.android:tunnel`) from Maven Central
and checks its SHA-256 before using it. The first build creates a signing key; **keep it**, because
Android only installs updates signed with the same key. For your own public builds, use your own key
with a real password (`make KEYSTORE=… KS_PASS=…`).

| Path | What |
|---|---|
| `src/com/leanvpn/QuickTile.java` | The tile: the whole interface |
| `src/com/leanvpn/Shim.java` | Invisible helper activity: VPN prompt, sign-in page, add-tile prompt, long-press menu |
| `src/com/leanvpn/Engine.java` | Session, server choice, health checks and failover (one background thread) |
| `src/com/leanvpn/Servers.java` | Server list parsing, shortlist, parallel latency probe |
| `src/com/leanvpn/Api.java` | The handful of Proton API calls |
| `src/com/leanvpn/Http.java` | Minimal HTTPS client: bypasses the tunnel, splits the ClientHello |
| `src/com/leanvpn/Keys.java` | Ed25519 identity and the matching WireGuard key |
| `src/com/leanvpn/Service.java` | WireGuard's VPN service, owned by the app |
| `docs/` | The website (GitHub Pages) |

## Deliberately not included

Screens, settings, a country or server picker, Secure Core, NetShield, split tunneling, port
forwarding, profiles, notifications, widgets, a built-in kill switch (Android's is better),
analytics and auto-update. Keeping it small is the point.

## Disclaimer

LeanVPN is provided as is, without warranty of any kind. It is not affiliated with Proton AG. It uses
Proton's API the way Proton's own apps do, including the Apple TV app's identifier for sign-in.
Proton could change or block that at any time, and using an unofficial client may be against Proton's
terms of service. You are responsible for how you use it.

## License and credits

LeanVPN is free software under the [GNU General Public License v3.0](LICENSE).

- WireGuard engine: [wireguard-android](https://git.zx2c4.com/wireguard-android)'s tunnel library
  (Apache-2.0), with wireguard-go (MIT).
- API behaviour follows Proton's open-source apps
  ([ProtonVPN/android-app](https://github.com/ProtonVPN/android-app), GPLv3).
- Sibling project: [LeanVPN for macOS](https://github.com/ZephyrSai/LeanVPN).
