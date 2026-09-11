# Bridge

Click **Connect** on your Mac and see your Android phone's screen from any network, even with the phone locked in your pocket.

This is Milestone 1: an app version of the setup you already tested by hand.

| Manual test | Bridge |
|---|---|
| Termux + `dumbpipe listen-tcp` on the phone | **Android app** (`android/`): runs the same dumbpipe binary in the background with a permanent identity |
| Copying the ticket over USB | **Set up over USB** button in the Mac menu |
| Terminal 1: `dumbpipe connect-tcp` | **Connect** button (Mac app, `mac/`) |
| Terminal 2: `adb connect` + `scrcpy` | Same button |

Still manual: after each phone restart, plug in once and click **Set up over USB**.

---

## 0. Before you start

1. **Stop dumbpipe in Termux** (Ctrl+C). You can uninstall Termux later.
2. **Mac tools** (you already have these):
   ```bash
   brew install scrcpy dumbpipe
   brew install --cask android-platform-tools
   ```
3. **Java 17 or 21** for the Android build. Check with `java -version`.
   - If Android Studio is installed, use its bundled Java:
     ```bash
     export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
     ```
   - Otherwise:
     ```bash
     brew install openjdk@21
     export JAVA_HOME="$(brew --prefix openjdk@21)/libexec/openjdk.jdk/Contents/Home"
     ```
   - Tip: `flutter doctor -v` shows which Java your Flutter setup already uses.
4. **Swift** for the Mac app. Check with `swift --version` (needs 5.9 or newer). If it's missing, run `xcode-select --install`.

## 1. Build and install the phone app

```bash
cd ~/Documents/Projects/Bridge/android
./scripts/fetch-dumbpipe.sh
echo "sdk.dir=$HOME/Library/Android/sdk" > local.properties
```

Plug in the phone, then:

```bash
./gradlew installDebug
```

The first build downloads Gradle and the Android plugins, which takes a few minutes. You can also open the `android` folder in Android Studio and press Run.

On the phone, open **Bridge**:

1. Allow notifications.
2. Tap **Allow Bridge to run in the background**.

## 2. Build and open the Mac app

```bash
cd ~/Documents/Projects/Bridge/mac
./make-app.sh
open build/Bridge.app
```

A phone icon appears in the menu bar. Optionally, move `Bridge.app` to Applications and add it under System Settings → General → Login Items.

## 3. Pair (and again after every phone restart)

1. Plug in the phone and unlock it.
2. Click the menu-bar icon → **Set up over USB**.

This opens Bridge on the phone, reads its ticket and runs `adb tcpip 5555`. When it says "Set up", unplug.

## 4. Connect

Click **Connect**. To end the session, close the mirroring window or click **Disconnect**.

---

## How the code is organized

**Android** (`android/app/src/main/java/com/bonevane/bridge/`)

| File | What it does |
|---|---|
| `TunnelService.kt` | Foreground service that runs `libdumbpipe.so listen-tcp --host 127.0.0.1:5555`, reads the ticket from its output and restarts it if it exits |
| `Prefs.kt` | Stores the permanent secret key (passed as `IROH_SECRET`), the last ticket and your settings |
| `TunnelState.kt` | Shared live state and log, used by the screen and the provider |
| `MainActivity.kt` | The single screen, built in code |
| `TicketProvider.kt` | Lets the Mac read the ticket via `adb shell content query`; refuses every other app |
| `BootReceiver.kt` | Turns the tunnel back on after a reboot if it was on before |
| `scripts/fetch-dumbpipe.sh` | Downloads the arm64 dumbpipe and saves it as `jniLibs/arm64-v8a/libdumbpipe.so` |

The binary is named `lib…so` because Android only allows apps to run programs from the folder where it installs native libraries.

**Mac** (`mac/Sources/BridgeMac/`)

| File | What it does |
|---|---|
| `BridgeController.swift` | All the logic: Set up over USB, then Connect (tunnel → `adb connect` with retries → scrcpy) and Disconnect |
| `Shell.swift` | Finds `adb`, `dumbpipe` and `scrcpy`, and runs commands with timeouts |
| `MenuView.swift` | The menu-bar panel |
| `BridgeApp.swift` | App entry point (menu-bar only, no Dock icon) |
| `make-app.sh` | Builds and ad-hoc signs `Bridge.app` |

## Troubleshooting

- **Gradle: "SDK location not found"**: create `local.properties` as in step 1.
- **Gradle: Java or "class file version" errors**: set `JAVA_HOME` to Java 17 or 21 (step 0).
- **Phone app says "dumbpipe is missing"**: run `./scripts/fetch-dumbpipe.sh`, then `./gradlew installDebug` again.
- **Phone log shows "Warning: no relay"**: only same-network connections will work. Try with the VPN on or off.
- **Mac: "Couldn't reach the phone"**: either the phone restarted since the last setup (run Set up over USB again), or the tunnel is off in the phone app.
- **Anything else**: copy the log (Mac: Log → Copy log; phone: bottom of the screen) and share it. Replace any `endpoint…` ticket with `endpoint…` first.

## Security notes

- The ticket lets a device reach your phone's adb port. adb still only accepts computers you've approved, but keep the ticket private.
- Until the next restart, `adb tcpip` also listens on port 5555 on whatever Wi-Fi the phone is on. Use trusted networks.
- Stop the tunnel any time from the Bridge notification.

## Next: Milestone 2

Replace `adb tcpip` + adbd with Shizuku and your own capture service (based on scrcpy's server), streamed over iroh inside the app. That removes the USB step after restarts.
