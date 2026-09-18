# Bridge for Windows

Work in progress. Milestone 1 covers the Bluetooth features (notifications,
clipboard, open-app twins); mirroring comes next.

## Build

Built by GitHub Actions on every push (`.github/workflows/windows.yml`);
download `Bridge-windows` from the workflow run. To build locally you need
Rust and the Visual Studio Build Tools (C++ workload), then `cargo build --release`.

## Test

1. On the phone, tap **Copy** under Ticket.
2. Get that text onto the PC clipboard (message yourself, etc.).
3. Run `Bridge.exe`, right-click the tray icon → **Pair from clipboard**.
4. Accept the Bluetooth pairing on both screens.

Everything it does is in `%APPDATA%\Bridge\Bridge.log` (tray → Open log folder).
