# Bridge
### **Your Android phone, on your Mac, from anywhere**

<div><img src="assets/bridge-banner.png"></div>

Your phone is in a bag across town, locked, on cellular. Click **Connect** on your Mac and it's on your screen: unlock it, read the message, tap the thing, done. No cable, no same-Wi-Fi, no cloud service holding your screen, and nothing left listening on the network when you're finished.

---

## 🧩 The Problem

Remote-controlling your own Android phone is oddly hard for a device you own:

- 🔌 **scrcpy** is excellent, but it's tied to ADB: a cable, or the phone on the same Wi-Fi with a port that changes on every reboot.
- 🔒 **Screen-mirroring apps** can't show the lock screen. Android 14+ asks for consent every session and stops capture when the phone locks, so you can't unlock from afar.
- 🌐 **KDE Connect** finds devices by broadcasting on the LAN; a VPN or a cellular connection breaks it.
- 🏦 **Banking apps** refuse to run while USB debugging is on, so "just leave adb enabled" isn't an option for a daily phone.
- ☁️ **Vendor clouds** (Samsung, etc.) work, when they work, by routing your screen through their servers.

**Nothing does "locked phone, any network, no exposure, no monthly fee."**

---

## 💡 How Bridge Does It

🔑 **Devices, not accounts.** Each phone has a permanent key. The Mac dials it by that key over [iroh](https://iroh.computer): direct when possible, through an encrypted relay when not. Works on cellular and with VPNs on both ends.

🛡️ **Privileges only when needed.** The phone starts its own shell-level helper through a one-second wireless-debugging window on localhost, using ADB's own protocol and its own already-trusted key. No pairing code, no computer, no Shizuku.

🎥 **scrcpy's server, our client.** The phone runs scrcpy's battle-tested capture and input code; the Mac runs a small native viewer (hardware H.264 decode, AAC audio), so no `adb` ever listens on your network during a session.

🏦 **Locked down by default.** Disconnect turns USB debugging off again. Or keep it ready for cellular and pause it with a Quick Settings tile when a banking app complains. Your choice.

### What Makes Bridge Different

**Lock screen included** 🔓
Because the helper runs with shell privileges, the lock screen is just another screen. Type the PIN from your keyboard.

**Verified zero network exposure** 🔍
During a session the phone has no ADB listener on any interface (we port-scanned it to be sure). The only window is ~1 s of wireless debugging on a Wi-Fi network you've approved, at bootstrap.

**Survives reboots without a cable** 🔁
After a restart the phone bootstraps itself the next time it sees Wi-Fi. Cellular-only after a reboot is the one case that needs Wi-Fi first: a deliberate trade-off for security.

**Adapts to the link** 📶
The viewer watches how far behind the stream falls and steps the bitrate down (and back up) by restarting the encoder. Relayed cellular sessions stay usable.

---

## 🎥 Demo

<!-- ![Bridge demo](assets/demo.gif) -->

**[🎬 Watch the demo](#)** *(coming)*

---

## ✨ Features

### 📱 Phone (Android 11+, tested on Pixel 9 Pro / Android 17)
- Foreground service holding the iroh tunnel (bundled `dumbpipe`, permanent identity)
- Built-in ADB client (RSA auth and TLS) that talks to the phone's own adbd over loopback
- Shell-uid daemon started with `app_process`; spawns scrcpy-server and relays its sockets
- Wi-Fi bootstrap after reboot via mDNS + TLS, no pairing
- Lock-down / keep-ready setting, "Pause for banking" button and Quick Settings tile
- Self-update through the tunnel (`pm install` by the daemon)

### 💻 Mac (macOS 13+, menu-bar app)
- **Connect** / **Disconnect**, ticket pairing over USB once
- Native viewer: `AVSampleBufferDisplayLayer` H.264, `AudioToolbox` AAC
- Touch, drag, hover, scroll, keyboard, ⌘B back · ⌘H home · ⌘R recents · ⌘N notifications · ⌘P power · ⌘O screen off
- Options: bitrate, max size, phone screen off, mute phone while mirroring, keep ready
- Dock icon only while a phone window is open

### 📋 Control protocol (one port, one ticket)
```
START            bring the phone up (USB debugging on, daemon spawned)   → OK daemon running
STOP             end of session: USB debugging off (unless keep-ready / on cellular)
PAUSE 15         off for 15 minutes, auto-resume on Wi-Fi if keep-ready
MODE keep|lock   the user's choice
VIDEO <opts>     raw scrcpy video stream        AUDIO <scid>    AAC stream
CTRL <scid>      scrcpy control messages        INSTALL <n>     APK bytes → pm install
```

---

## 🛠️ Tech Stack

### Phone
- **Kotlin**, no third-party libraries
- **dumbpipe / iroh** — QUIC, hole punching, relay fallback (prebuilt musl binary)
- **scrcpy-server** — capture, encode, input (Apache-2.0, bundled as-is)
- ADB client adapted from **Shizuku** (Apache-2.0)

### Mac
- **Swift Package**, SwiftUI `MenuBarExtra` + AppKit
- **AVFoundation / AudioToolbox** for decoding
- **dumbpipe** (Homebrew) for the tunnel; `adb` only for the one-time USB setup

---

## 🏗️ Architecture

<!-- ![Architecture](assets/architecture.png) -->

```
 Mac                                      Phone
┌──────────────────┐                     ┌─────────────────────────────────────┐
│ Bridge.app       │                     │ Bridge app (normal uid)             │
│  viewer + input  │                     │  TunnelService ── dumbpipe listen   │
│        │         │   iroh / QUIC       │        │                            │
│  dumbpipe ───────┼══ direct or relay ══┼──> ControlProxy :5580               │
│  connect-tcp     │                     │     ├─ START/STOP/PAUSE/MODE        │
└──────────────────┘                     │     └─ VIDEO/AUDIO/CTRL/INSTALL ─┐  │
                                         │                                  │  │
                                         │ Daemon (shell uid, app_process)  │  │
                                         │  :5577 <──────────────────────────┘  │
                                         │   └─ scrcpy-server ── localabstract  │
                                         │                                     │
                                         │ adbd: alive during a session,       │
                                         │       USB only, no TCP listener     │
                                         └─────────────────────────────────────┘
```

**Bootstrap** (once per boot, or after a lock-down): app sets `adb_enabled=1` and `adb_wifi_enabled=1` → finds adbd's port via mDNS → TLS with its own trusted key → `app_process … Daemon` → `adb_wifi_enabled=0`. About one second.

---

## 🚀 Getting Started

```bash
brew install dumbpipe && brew install --cask android-platform-tools
```

**Phone**
```bash
cd android && ./scripts/fetch-dumbpipe.sh && ./scripts/fetch-scrcpy-server.sh
echo "sdk.dir=$HOME/Library/Android/sdk" > local.properties
./gradlew installDebug
```

**Mac**
```bash
cd mac && ./make-app.sh && open build/Bridge.app
```

Then: enable USB debugging, plug in, **Set up over USB** in the menu (grants the phone app the one permission it needs and copies the ticket), accept "Allow USB debugging" for `bridge@phone` with *Always allow*. Unplug. **Connect.**

First time on each Wi-Fi network, Android asks "Allow wireless debugging on this network?" once; tick *Always allow on this network*.

---

## 🔐 Security Model

| | |
|---|---|
| **Who can connect** | Anyone holding the ticket. Treat it like a password. (Device-key pairing with a phone-side prompt is on the roadmap.) |
| **On the network during a session** | Nothing from ADB. adbd runs USB-only; the daemon and proxy bind to loopback. |
| **At bootstrap** | Wireless debugging on a random port for ~1 s, only on Wi-Fi networks you approved, gated by adbd's key check. |
| **When idle (default)** | USB debugging off. Banking apps see a normal phone. |
| **Relay** | End-to-end encrypted QUIC; the relay forwards ciphertext. Public n0 relays for now; self-hosting is a config change. |
| **Known gap** | Other apps on the phone can reach the loopback ports (5580/5577). A shared secret between app and daemon will close this. |

---

## 🔮 Roadmap

**Milestone 1** ✅ Tunnel + scrcpy over adb *(superseded)*
**Milestone 1.5** ✅ USB debugging off by default, remote wake, reboot without cable
**Milestone 2** ✅ adb-free sessions: native viewer, audio, adaptive bitrate, self-update
**Milestone 3** 🚧 Clipboard sync, file drop, notification mirroring, auto-pause for listed apps
**Milestone 4** 📋 Device-key pairing with on-phone approval, self-hosted relay, iroh in-app (drop the dumbpipe binary), Linux/Windows clients

---

## 🙏 Acknowledgments

- [scrcpy](https://github.com/Genymobile/scrcpy) — the server does the real work on the phone
- [iroh](https://github.com/n0-computer/iroh) and [dumbpipe](https://github.com/n0-computer/dumbpipe) — connectivity that just works
- [Shizuku](https://github.com/RikkaApps/Shizuku) — the in-app ADB client is adapted from theirs
- [thedjchi's Shizuku fork](https://github.com/thedjchi/Shizuku/wiki) — for showing the intent/TCP-mode trick that unlocked the banking-app problem

See `NOTICE` for licenses.

---
