//! Set up over USB: the Mac's flow, step for step (BridgeController.swift).
//!
//! Plug the phone in with USB debugging on, click once. Bridge starts the
//! phone app, reads the ticket and pairing secret from its content provider
//! (shell uid only, so this needs the cable), grants WRITE_SECURE_SETTINGS
//! so the phone can switch USB debugging itself, gets adbd to trust the
//! phone app's own ADB key (once per install), starts the helper over the
//! cable, and hands the phone back to Nearby mode.
//!
//! adb comes from Android's platform-tools; the CI build puts it next to
//! Bridge (on Windows with its two DLLs). It is only ever used over the cable.
//!
//! Linux only lets a normal user open a USB device that a udev rule allows,
//! and has none for phones out of the box: adb then lists the phone as "no
//! permissions". Bridge installs the rule itself, once, behind the system's
//! own password prompt (see [allow_usb]).

use crate::ble::Event;
use crate::store::Credentials;
use crate::tunnel::NoWindow;
use anyhow::{anyhow, bail, Result};
use std::process::{Command, Stdio};
use std::sync::mpsc::Sender;
use std::time::Duration;

const PACKAGE: &str = "com.bonevane.bridge";
const ACTIVITY: &str = "com.bonevane.bridge/.MainActivity";
const TICKET_URI: &str = "content://com.bonevane.bridge.ticket/ticket";

/// Beside Bridge (CI bundle), then the SDK's usual place, then PATH.
fn adb() -> Result<std::path::PathBuf> {
    let name = format!("adb{}", std::env::consts::EXE_SUFFIX);
    let beside = crate::updater::install_path().with_file_name(&name);
    if beside.exists() {
        return Ok(beside);
    }
    #[cfg(windows)]
    let sdk = std::env::var_os("LOCALAPPDATA").map(|l| std::path::PathBuf::from(l).join("Android").join("Sdk"));
    #[cfg(target_os = "linux")]
    let sdk = std::env::var_os("HOME").map(|h| std::path::PathBuf::from(h).join("Android").join("Sdk"));
    if let Some(sdk) = sdk.map(|s| s.join("platform-tools").join(&name)).filter(|s| s.exists()) {
        return Ok(sdk);
    }
    Ok(std::path::PathBuf::from(name))
}

/// Runs adb with the arguments, returns combined stdout+stderr.
fn run(adb: &std::path::Path, args: &[&str]) -> String {
    let out = Command::new(adb).args(args).stdin(Stdio::null()).creation_flags_no_window().output();
    match out {
        Ok(o) => {
            let mut s = String::from_utf8_lossy(&o.stdout).into_owned();
            s.push_str(&String::from_utf8_lossy(&o.stderr));
            s
        }
        Err(e) => format!("adb: {e}"),
    }
}

struct Phone {
    serial: String,
    state: String,
    name: String,
}

/// The state `adb devices` gives a phone it isn't allowed to open: the line
/// reads "no permissions (…)", and the first word is all [usb_devices] keeps.
const NO_PERMISSIONS: &str = "no";

/// The udev rule (assets/51-bridge-android.rules, which says what it matches).
const UDEV_RULE: &str = include_str!("../assets/51-bridge-android.rules");
const UDEV_RULE_PATH: &str = "/etc/udev/rules.d/51-bridge-android.rules";

/// Installs the udev rule as root through pkexec, which shows the desktop's
/// own password prompt (Bridge never sees the password), then has udev apply
/// it to what's already plugged in. The rule goes in on stdin, so nothing
/// but fixed text reaches the root shell.
fn allow_usb() -> Result<()> {
    use std::io::Write;
    let script = format!(
        "cat > {UDEV_RULE_PATH} && chmod 644 {UDEV_RULE_PATH} && udevadm control --reload-rules && udevadm trigger --subsystem-match=usb --action=change"
    );
    let manual = format!(
        "Or run this in a terminal, then unplug the phone and plug it back in:\n\
         echo '{}' | sudo tee {UDEV_RULE_PATH} && sudo udevadm control --reload-rules",
        UDEV_RULE.lines().filter(|l| !l.starts_with('#')).collect::<String>()
    );
    let mut child = match Command::new("pkexec").args(["/bin/sh", "-c", &script]).stdin(Stdio::piped()).spawn() {
        Ok(child) => child,
        Err(_) => bail!("Linux needs to allow Bridge to use phones over USB, and there's no password prompt (pkexec) on this computer. {manual}"),
    };
    if let Some(mut stdin) = child.stdin.take() {
        let _ = stdin.write_all(UDEV_RULE.as_bytes());
    }
    let status = child.wait()?;
    match status.code() {
        Some(0) => {
            crate::log!("usb", "installed {UDEV_RULE_PATH}");
            Ok(())
        }
        // pkexec: 126 = the prompt was dismissed, 127 = not authorised.
        Some(126) | Some(127) => bail!("Bridge needs your password once to use phones over USB. Run Set up over USB again and enter it when asked. {manual}"),
        _ => bail!("Couldn't allow USB access ({status}). {manual}"),
    }
}

/// After the rule: adb opened the phone while it wasn't allowed to, so start
/// it afresh, and give udev a few seconds to apply the rule.
fn wait_for_access(adb: &std::path::Path) -> Vec<Phone> {
    run(adb, &["kill-server"]);
    run(adb, &["start-server"]);
    let mut phones = usb_devices(&run(adb, &["devices", "-l"]));
    for _ in 0..10 {
        if !phones.iter().any(|p| p.state == NO_PERMISSIONS) {
            break;
        }
        std::thread::sleep(Duration::from_secs(1));
        phones = usb_devices(&run(adb, &["devices", "-l"]));
    }
    phones
}

/// Parses `adb devices -l`, keeping only phones on USB (not TCP endpoints).
fn usb_devices(listing: &str) -> Vec<Phone> {
    listing
        .lines()
        .filter_map(|line| {
            let parts: Vec<&str> = line.split_whitespace().collect();
            if parts.len() < 2 || parts[0].contains(':') || parts[0] == "List" || parts[0] == "*" {
                return None;
            }
            let name = parts.iter().find_map(|p| p.strip_prefix("model:")).map(|m| m.replace('_', " ")).unwrap_or_else(|| parts[0].to_string());
            Some(Phone { serial: parts[0].to_string(), state: parts[1].to_string(), name })
        })
        .collect()
}

fn extract(field: &str, output: &str) -> Option<String> {
    let start = output.find(&format!("{field}="))? + field.len() + 1;
    let rest = &output[start..];
    let end = rest.find([',', '\n', '\r']).unwrap_or(rest.len());
    Some(rest[..end].trim().to_string())
}

/// The whole flow, on a thread; progress and the result come back as events.
pub fn set_up(events: Sender<Event>) {
    std::thread::spawn(move || {
        let result = flow(&events);
        let _ = events.send(Event::UsbDone(result.map_err(|e| format!("{e:#}"))));
    });
}

fn progress(events: &Sender<Event>, text: &str) {
    crate::log!("usb", "{text}");
    let _ = events.send(Event::UsbProgress(text.to_string()));
}

fn flow(events: &Sender<Event>) -> Result<Credentials> {
    let adb = adb()?;
    progress(events, "Looking for your phone on USB…");
    let probe = run(&adb, &["start-server"]);
    if probe.contains("not recognized") || probe.starts_with("adb: ") {
        if cfg!(windows) {
            bail!("adb.exe isn't available. Put Android's platform-tools next to Bridge.exe (adb.exe, AdbWinApi.dll, AdbWinUsbApi.dll).");
        }
        bail!("adb isn't available. Keep the adb file that came with Bridge next to it.");
    }
    let mut phones = usb_devices(&run(&adb, &["devices", "-l"]));
    if cfg!(target_os = "linux") && phones.iter().any(|p| p.state == NO_PERMISSIONS) {
        progress(events, "Linux needs your password once to let Bridge use phones over USB…");
        allow_usb()?;
        progress(events, "Looking for your phone on USB…");
        phones = wait_for_access(&adb);
    }
    let phone = match phones.len() {
        0 => bail!("No phone found on USB. Plug it in, unlock it, and turn on USB debugging."),
        1 => &phones[0],
        _ => bail!("More than one phone is plugged in. Leave just the one to set up."),
    };
    match phone.state.as_str() {
        "device" => {}
        NO_PERMISSIONS => bail!("This computer still won't let Bridge use the phone over USB. Unplug it, plug it back in, and run Set up over USB again."),
        "unauthorized" => bail!("The phone is waiting for you: accept \"Allow USB debugging?\" on it (tick Always allow), then run Set up over USB again."),
        other => bail!("The phone is {other}; unlock it and check USB debugging is on."),
    }
    let serial = phone.serial.as_str();
    crate::log!("usb", "setting up {} ({serial})", phone.name);

    progress(events, "Starting Bridge on your phone…");
    let launch = run(&adb, &["-s", serial, "shell", "am", "start", "-n", ACTIVITY, "-a", "com.bonevane.bridge.START"]);
    if launch.contains("does not exist") {
        bail!("The Bridge app isn't installed on the phone yet.");
    }

    progress(events, "Waiting for the phone's ticket…");
    let mut creds: Option<Credentials> = None;
    for _ in 0..25 {
        let q = run(&adb, &["-s", serial, "shell", "content", "query", "--uri", TICKET_URI]);
        if q.contains("Unknown authority") || q.contains("Could not find provider") {
            bail!("The Bridge app isn't installed on the phone yet.");
        }
        if q.contains("ready=1") {
            if let (Some(ticket), Some(secret)) = (extract("ticket", &q), extract("secret", &q)) {
                if ticket.starts_with("endpoint") && secret.len() == 64 {
                    creds = Some(Credentials { ticket, secret });
                    break;
                }
            }
        }
        std::thread::sleep(Duration::from_secs(1));
    }
    let creds = creds.ok_or_else(|| anyhow!("The phone didn't report a ticket. Open Bridge on the phone and check its log."))?;
    crate::log!("usb", "got the ticket and pairing secret from the phone");

    // Lets the phone app switch USB debugging on and off by itself (AdbToggle.kt).
    let grant = run(&adb, &["-s", serial, "shell", "pm", "grant", PACKAGE, "android.permission.WRITE_SECURE_SETTINGS"]);
    if !grant.trim().is_empty() {
        crate::log!("usb", "pm grant: {}", grant.trim());
    }
    // Xiaomi (MIUI/HyperOS) refuses this unless a second switch is on (see the Mac).
    if grant.contains("GRANT_RUNTIME_PERMISSIONS") {
        return Err(anyhow!("This phone blocks the last setup step. In Developer options, turn on \"USB debugging (Security settings)\" (Xiaomi asks for a SIM and a Mi account), then Set up over USB again."));
    }

    // Get adbd to trust the phone app's own ADB key (see the Mac for why):
    // TCP for a moment over the cable, the app connects to itself, the
    // "Allow USB debugging?" dialog appears for its key, then back to USB-only.
    let key_known = run(&adb, &["-s", serial, "shell", "content", "query", "--uri", TICKET_URI]).contains("keyOk=1");
    if !key_known {
        progress(events, "On the phone: tap \"Always allow\" for USB debugging…");
        run(&adb, &["-s", serial, "tcpip", "5555"]);
        std::thread::sleep(Duration::from_millis(2500)); // adbd restarts
        run(&adb, &["-s", serial, "shell", "am", "start", "-n", ACTIVITY, "-a", "com.bonevane.bridge.AUTHORIZE"]);
        let mut accepted = false;
        for _ in 0..60 {
            std::thread::sleep(Duration::from_secs(2));
            if run(&adb, &["-s", serial, "shell", "content", "query", "--uri", TICKET_URI]).contains("keyOk=1") {
                accepted = true;
                break;
            }
        }
        run(&adb, &["-s", serial, "usb"]);
        std::thread::sleep(Duration::from_millis(2500));
        if !accepted {
            bail!("The phone didn't accept the key. Run Set up over USB again and tap \"Always allow\" when the phone asks.");
        }
        crate::log!("usb", "the phone trusts its own key now; later restarts of the helper will work");
    }

    // Start the helper over the cable so the first Mirror works right away.
    // Any helper already there goes first: a leftover from an older install
    // (or an older pairing) holds the port and knows the wrong secret, and
    // the new one couldn't bind next to it. ([.] so pkill's own shell, whose
    // command line contains the pattern, doesn't match itself.)
    progress(events, "Starting the phone's helper over USB…");
    run(&adb, &["-s", serial, "shell", "pkill -f 'bridge[.]Daemon'; sleep 0.5"]);
    let spawn = format!(
        "apk=$(pm path {PACKAGE} | head -1 | cut -d: -f2); \
         (BRIDGE_SECRET={} CLASSPATH=$apk exec setsid app_process / com.bonevane.bridge.Daemon \
         </dev/null >/data/local/tmp/bridge-daemon.out 2>&1) & sleep 1",
        creds.secret
    );
    let out = run(&adb, &["-s", serial, "shell", &spawn]);
    if !out.trim().is_empty() {
        crate::log!("usb", "helper: {}", out.trim());
    }
    // Make sure it's actually up and answering with this secret; "it printed
    // nothing" told us nothing before.
    let mut up = false;
    for _ in 0..12 {
        if run(&adb, &["-s", serial, "shell", "content", "query", "--uri", TICKET_URI]).contains("daemon=1") {
            up = true;
            break;
        }
        std::thread::sleep(Duration::from_millis(500));
    }
    if up {
        crate::log!("usb", "the helper is running");
    } else {
        let tail = run(&adb, &["-s", serial, "shell", "tail -n 3 /data/local/tmp/bridge-daemon.out"]);
        crate::log!("usb", "the helper didn't come up; its log ends with: {}", tail.trim());
    }
    // Setup switched the phone's tunnel on to mint the ticket; back to Nearby.
    run(&adb, &["-s", serial, "shell", "am", "start", "-n", ACTIVITY, "-a", "com.bonevane.bridge.NEARBY"]);
    Ok(creds)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_device_listing() {
        let listing = "List of devices attached\n4B121FDAP000UK         device usb:1-1 product:caiman model:Pixel_9_Pro device:caiman transport_id:3\n127.0.0.1:7555   device product:caiman\n";
        let phones = usb_devices(listing);
        assert_eq!(phones.len(), 1);
        assert_eq!(phones[0].serial, "4B121FDAP000UK");
        assert_eq!(phones[0].name, "Pixel 9 Pro");
        assert_eq!(phones[0].state, "device");
    }

    #[test]
    fn spots_a_phone_linux_wont_open() {
        let listing = "List of devices attached\n4B121FDAP000UK         no permissions (missing udev rules? user is in the plugdev group); see [http://developer.android.com/tools/device.html] usb:1-1\n";
        let phones = usb_devices(listing);
        assert_eq!(phones.len(), 1);
        assert_eq!(phones[0].state, NO_PERMISSIONS);
    }

    #[test]
    fn extracts_columns() {
        let row = "Row: 0 ticket=endpointabc, ready=1, status=Listening, secret=0123, keyOk=1\n";
        assert_eq!(extract("ticket", row).as_deref(), Some("endpointabc"));
        assert_eq!(extract("secret", row).as_deref(), Some("0123"));
        assert_eq!(extract("keyOk", row).as_deref(), Some("1"));
    }
}
