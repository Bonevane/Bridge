//! The phone's health (what the phone reports as wrong, worded here) and
//! updates (this PC's, via updater.rs, and the phone app's, driven over
//! Bluetooth). Same behaviour as the Mac's PhoneIssue/MacUpdater.

use std::sync::atomic::Ordering;
use std::time::Duration;

use slint::{ComponentHandle, ModelRc, VecModel};

use crate::ble::Event;
use crate::updater::{self, Checked, State};
use crate::{App, AppState, HealthItem, SettingsState, UpdateButton};

/// One problem the phone reports ("issues=keylost,battery"), worded for this PC.
pub struct PhoneIssue {
    pub kind: String,
    pub title: String,
    pub detail: &'static str,
    pub error: bool,
    /// "usb", "renew" or "" (fixed on the phone).
    pub action: &'static str,
}

impl PhoneIssue {
    /// It stops something working, or is about to: worth a notification.
    fn worth_notifying(&self) -> bool { self.error || self.kind == "keyexp" || self.kind == "keycheck" }
}

pub fn phone_issue(code: &str) -> Option<PhoneIssue> {
    let (kind, arg) = code.split_once(':').map(|(k, a)| (k, a.parse::<i64>().ok())).unwrap_or((code, None));
    let (title, detail, error, action): (String, &'static str, bool, &'static str) = match kind {
        "keylost" => ("The phone lost Bridge's debugging permission".into(),
            "Mirroring and Phone files can't start. Plug the phone in and use Set up over USB, then tap Always allow on the phone.", true, "usb"),
        "nogrant" => ("The phone can't switch USB debugging".into(),
            "Bridge's permission on the phone is gone, usually after reinstalling it. Plug the phone in and use Set up over USB.", true, "usb"),
        // -1: probably lapsed already; 0: today; N: in N+1 days.
        "keyexp" => (match arg {
                Some(d) if d > 0 => format!("Bridge's permission on the phone expires in {} days", d + 1),
                Some(d) if d < 0 => "Bridge's permission on the phone may have expired".into(),
                _ => "Bridge's permission on the phone expires today".into(),
            },
            "Android forgets permissions that go unused. The phone renews it by itself on Wi-Fi it knows; Renew now does it straight away.", false, "renew"),
        "keycheck" => ("The phone needs to check Bridge's permission".into(),
            "Android forgets debugging permissions that go unused. With the phone on its usual Wi-Fi, click Renew now (or tap Check now on the phone); after that it keeps it fresh by itself.", false, "renew"),
        "nolistener" => ("The phone isn't sending notifications".into(),
            "Notification access is on, but Android isn't running Bridge's listener. Open Bridge on the phone and tap Fix (on Xiaomi, turn on Autostart).", true, ""),
        "notifoff" => ("Notification access is off on the phone".into(),
            "Allow it in Bridge on the phone for notifications to appear here.", false, ""),
        "autostart" => ("Autostart is off on the phone".into(),
            "Xiaomi phones stop Bridge in the background without it. Turn it on from Bridge on the phone.", false, ""),
        "battery" => ("Battery use is restricted on the phone".into(),
            "Android may pause Bridge while the screen is off, which drops the link. Allow it from Bridge on the phone.", false, ""),
        _ => return None,
    };
    Some(PhoneIssue { kind: kind.to_string(), title, detail, error, action })
}

fn mb(bytes: u64) -> String { format!("{:.1} MB", bytes as f64 / 1_048_576.0) }

fn button(label: &str, action: &str, filled: bool) -> UpdateButton {
    UpdateButton { label: label.into(), action: action.into(), filled }
}

fn model<T: Clone + 'static>(items: Vec<T>) -> ModelRc<T> { ModelRc::new(VecModel::from(items)) }

impl App {
    /// The phone's problems, worst first.
    pub fn phone_problems(&self) -> Vec<PhoneIssue> {
        let mut v: Vec<PhoneIssue> = self.phone_status.get("issues").map(|s| s.as_str()).unwrap_or("")
            .split(',').filter(|c| !c.is_empty()).filter_map(phone_issue).collect();
        v.sort_by_key(|p| !p.error);
        v
    }

    /// Extra handling of a phone status line: notifications for new
    /// problems, and a fast reconnect when its app restarts for an update.
    pub fn on_phone_status_extra(&mut self) {
        if self.phone_status.get("upd").map(|u| u.starts_with("installing")).unwrap_or(false) {
            if let Some(b) = &self.ble { b.phone_is_restarting(); }
        }
        let problems = self.phone_problems();
        let current: std::collections::BTreeSet<String> = problems.iter().map(|p| p.kind.clone()).collect();
        self.notified_issues.retain(|k| current.contains(k));
        for p in problems.iter().filter(|p| p.worth_notifying()) {
            if self.notified_issues.insert(p.kind.clone()) {
                crate::notify::show("Bridge", &p.title, p.detail);
            }
        }
    }

    // MARK: - Checking

    /// From the 5-second tick: a check once a day, if allowed.
    pub fn maybe_check_updates(&mut self) {
        let now = chrono::Utc::now().timestamp_millis();
        if !self.settings.update_checks || now < self.settings.update_retry_after { return; }
        if now - self.settings.update_checked_at < 24 * 3_600_000 { return; }
        if self.started_at.elapsed() < Duration::from_secs(30) { return; }   // let startup settle first
        self.check_updates(false);
    }

    pub fn check_updates(&mut self, manual: bool) {
        if matches!(self.update_state, State::Checking | State::Downloading { .. } | State::Verifying(_) | State::Installing(_)) {
            return;
        }
        self.update_manual = manual;
        // Counted as checked now, so a failure doesn't retry every tick.
        self.settings.update_checked_at = chrono::Utc::now().timestamp_millis();
        self.settings.save();
        if manual { self.update_state = State::Checking; self.refresh_maintenance(); }
        updater::check(self.events_tx.clone());
    }

    pub fn on_update_checked(&mut self, checked: Checked) {
        let manual = std::mem::take(&mut self.update_manual);
        match checked {
            Checked::Newer(r) => {
                // A download already under way for this version carries on as it is.
                let same = self.update_state.release().map(|x| x.version == r.version).unwrap_or(false);
                if !same || matches!(self.update_state, State::Checking | State::Idle | State::UpToDate | State::Failed(..)) {
                    self.update_state = updater::state_for(r.clone());
                }
                if !manual && self.settings.update_announced != r.version {
                    self.settings.update_announced = r.version.clone();
                    self.settings.save();
                    crate::notify::show("Bridge", &format!("Bridge {} is available", r.version),
                                        "Open Bridge's settings, Updates, to see what's new and update.");
                }
            }
            Checked::Current => self.update_state = State::UpToDate,
            Checked::RateLimited(until) => {
                self.settings.update_retry_after = until;
                self.settings.save();
                if manual {
                    let minutes = ((until - chrono::Utc::now().timestamp_millis()) / 60_000).max(1);
                    self.update_state = State::Failed(format!("GitHub is limiting checks right now. Try again in {minutes} min."), None);
                }
            }
            Checked::Failed(why) => {
                crate::log!("update", "check failed: {why}");
                if manual {
                    self.update_state = State::Failed("Couldn't reach GitHub. Check the connection and try again.".into(), None);
                } else if matches!(self.update_state, State::Checking) {
                    self.update_state = State::Idle;
                }
            }
        }
        self.refresh_maintenance();
    }

    pub fn on_update_state(&mut self, s: State) {
        self.update_state = s;
        self.refresh_maintenance();
    }

    // MARK: - Buttons

    /// The Updates card's buttons ("download", "pause", "cancel", "install",
    /// "check", "github").
    pub fn update_action(&mut self, action: &str) {
        match action {
            "check" => self.check_updates(true),
            "download" => {
                if let Some(r) = self.update_state.release().cloned() {
                    self.update_state = State::Downloading { release: r.clone(), done: 0, total: r.size, per_sec: 0 };
                    updater::download(r, self.update_control.clone(), self.events_tx.clone());
                }
            }
            "pause" => self.update_control.store(1, Ordering::SeqCst),
            "cancel" => {
                if matches!(self.update_state, State::Downloading { .. }) {
                    self.update_control.store(2, Ordering::SeqCst);
                } else if let Some(r) = self.update_state.release().cloned() {
                    let _ = std::fs::remove_file(updater::zip_file(&r));
                    let _ = std::fs::remove_file(updater::dir().join(format!("Bridge-{}-windows.zip.part", r.version)));
                    self.update_state = State::Available(r);
                }
            }
            "install" => self.install_update(),
            "github" => {
                let _ = std::process::Command::new("explorer").arg("https://github.com/Bonevane/Bridge/releases/latest").spawn();
            }
            _ => {}
        }
        self.refresh_maintenance();
    }

    /// The phone app's update, over Bluetooth ("install", "pause", "cancel", "check").
    pub fn phone_update_action(&mut self, verb: &str) {
        match &self.ble {
            Some(b) if self.linked => {
                let _ = b.send_command(&format!("update {verb}"));
                crate::log!("update", "asked the phone to {verb} its update");
            }
            _ => crate::log!("update", "phone update: the phone isn't linked over Bluetooth"),
        }
    }

    pub fn renew_phone_key(&mut self) {
        if let Some(b) = &self.ble {
            let _ = b.send_command("renew key");
            crate::log!("phone", "asked the phone to renew its debugging permission");
        }
    }

    // MARK: - Installing

    /// Why Install can't run right now.
    fn install_blocker(&self) -> Option<&'static str> {
        if self.mirroring || self.connecting || self.files_session {
            return Some("Close the phone window and Phone files first: installing restarts Bridge.");
        }
        None
    }

    /// Stops everything cleanly, swaps the files, starts the new version and
    /// waits for it; the outcome arrives as `Event::UpdateLaunched`.
    fn install_update(&mut self) {
        let State::Ready(r) = self.update_state.clone() else { return };
        if let Some(why) = self.install_blocker() {
            self.update_state = State::Failed(why.into(), Some(r));
            return;
        }
        self.update_state = State::Installing(r.clone());
        self.refresh_maintenance();
        crate::log!("update", "installing {}: stopping Bluetooth, the tunnel and adb", r.version);
        // In order: the link (so the phone sees a clean disconnect), the
        // tunnel, then anything left running from Bridge's own folder (its
        // adb server). The clipboard watcher is only polled by this process,
        // so it stops with it.
        if let Some(mut b) = self.ble.take() { b.stop(); }
        self.linked = false;
        if let Some(mut t) = self.tunnel.take() { t.stop(); }
        updater::stop_own_helpers();
        match updater::swap_in(&r) {
            Ok(swap) => self.update_swap = Some(swap),
            Err(e) => {
                crate::log!("update", "couldn't put the new files in place: {e:#}");
                self.update_state = State::Failed(format!("Couldn't replace Bridge's files: {e:#}."), Some(r));
                self.resume_after_failed_update();
                return;
            }
        }
        // The new copy has to be able to claim "the one running Bridge".
        crate::release_instance();
        let tx = self.events_tx.clone();
        std::thread::spawn(move || {
            let _ = tx.send(Event::UpdateLaunched(updater::launch_and_wait().map_err(|e| format!("{e:#}"))));
        });
    }

    pub fn on_update_launched(&mut self, result: Result<(), String>) {
        match result {
            Ok(()) => {
                crate::log!("update", "the new version is running; this one is leaving");
                let _ = slint::quit_event_loop();
                // The tray icon and windows go with the process.
                std::thread::spawn(|| { std::thread::sleep(Duration::from_millis(500)); std::process::exit(0); });
            }
            Err(why) => {
                crate::log!("update", "{why}; putting the previous version back");
                if let Some(swap) = self.update_swap.take() { swap.undo(); }
                crate::reacquire_instance();
                let r = self.update_state.release().cloned();
                self.update_state = State::Failed(format!("The new version didn't start ({why}), so this one was kept."), r);
                self.resume_after_failed_update();
            }
        }
        self.refresh_maintenance();
    }

    /// Everything [install_update] stopped, started again.
    fn resume_after_failed_update(&mut self) {
        if self.settings.use_bluetooth { self.start_bluetooth(); }
        self.refresh();
    }

    // MARK: - State → UI

    pub fn refresh_maintenance(&self) {
        let ui = self.window.global::<AppState>();
        let st = self.settings_window.global::<SettingsState>();

        // The phone's worst problem on the main window, under the hero: only
        // what breaks something (or is about to); the rest is in Settings › Phone.
        let problems = if self.linked { self.phone_problems() } else { vec![] };
        if let Some(p) = problems.iter().find(|p| p.worth_notifying()) {
            ui.set_attention_visible(true);
            ui.set_attention_title(p.title.clone().into());
            ui.set_attention_detail(p.detail.into());
            ui.set_attention_error(p.error);
            ui.set_attention_action(p.action.into());
            ui.set_attention_more((problems.len() - 1) as i32);
        } else {
            ui.set_attention_visible(false);
        }

        // Settings › Phone › Health.
        st.set_health_linked(self.linked);
        st.set_health(model(problems.iter().map(|p| HealthItem {
            title: p.title.clone().into(), detail: p.detail.into(), error: p.error, action: p.action.into(),
        }).collect()));
        let issues = self.phone_status.get("issues").cloned().unwrap_or_default();
        let expires = self.phone_status.get("keyexp").and_then(|v| v.parse::<i64>().ok()).unwrap_or(0);
        let now = chrono::Utc::now().timestamp_millis();
        st.set_key_status(if issues.contains("keylost") { "Lost: set up over USB again".into() }
            else if expires <= 0 { "Doesn't expire".into() }
            else if expires < now { "May have expired".into() }
            else {
                let days = (expires - now) / 86_400_000;
                if days < 1 { "Lapses today unless renewed".into() } else { format!("Lapses in {days} day{} unless renewed", if days == 1 { "" } else { "s" }).into() }
            });
        st.set_key_ok(!issues.contains("keylost") && !issues.contains("keyexp"));

        // This PC's update.
        st.set_auto_update(self.settings.update_checks);
        st.set_last_checked(if self.settings.update_checked_at == 0 { "Not checked yet".into() } else {
            let mins = (now - self.settings.update_checked_at) / 60_000;
            (if mins < 1 { "Last checked just now".to_string() } else if mins < 60 { format!("Last checked {mins} min ago") }
             else if mins < 48 * 60 { format!("Last checked {} h ago", mins / 60) } else { format!("Last checked {} days ago", mins / 1440) }).into()
        });
        let (title, detail, progress, buttons): (String, String, f32, Vec<UpdateButton>) = match &self.update_state {
            State::Idle | State::UpToDate => (format!("Bridge {}", updater::current_version()), "Up to date".into(), -1.0, vec![]),
            State::Checking => ("Checking for updates…".into(), String::new(), -2.0, vec![]),
            State::Available(r) => (format!("Bridge {} is available", r.version), format!("You have {}. Installing restarts Bridge for a few seconds.", updater::current_version()),
                -1.0, vec![button("Download update", "download", true)]),
            State::Downloading { release, done, total, per_sec } => (format!("Downloading Bridge {}", release.version),
                format!("{} of {}{}", mb(*done), mb(*total), if *per_sec > 0 { format!(" · {}/s", mb(*per_sec)) } else { String::new() }),
                if *total > 0 { *done as f32 / *total as f32 } else { 0.0 },
                vec![button("Pause", "pause", false), button("Cancel", "cancel", false)]),
            State::Paused { release, done, total, reason } => (format!("Bridge {} · paused", release.version),
                reason.clone().unwrap_or_else(|| format!("Paused at {} of {}", mb(*done), mb(*total))),
                if *total > 0 { *done as f32 / *total as f32 } else { 0.0 },
                vec![button("Resume", "download", true), button("Cancel", "cancel", false)]),
            State::Verifying(r) => (format!("Checking Bridge {}", r.version), "Making sure the download is intact…".into(), -2.0, vec![]),
            State::Ready(r) => (format!("Bridge {} is ready", r.version),
                self.install_blocker().unwrap_or("Downloaded and checked. Installing closes Bridge, replaces it and opens the new version; it reconnects to the phone by itself.").into(),
                -1.0, vec![button("Install and restart", "install", true)]),
            State::Installing(r) => (format!("Installing Bridge {}", r.version), "Bridge closes and opens again by itself.".into(), -2.0, vec![]),
            State::Failed(msg, r) => (r.as_ref().map(|r| format!("Bridge {} didn't install", r.version)).unwrap_or_else(|| "Couldn't check for updates".into()),
                msg.clone(), -1.0,
                if r.is_some() { vec![button("Try again", "download", true), button("Get it from GitHub", "github", false)] }
                else { vec![button("Try again", "check", true)] }),
        };
        st.set_upd_title(title.into());
        st.set_upd_detail(detail.into());
        st.set_upd_progress(progress);
        st.set_upd_buttons(model(buttons));
        st.set_upd_notes(self.update_state.release().map(|r| r.notes.clone()).unwrap_or_default().into());
        ui.set_update_row(match &self.update_state {
            State::Available(r) | State::Paused { release: r, .. } => format!("Update Bridge to {}", r.version),
            State::Downloading { release, done, total, .. } => format!("Downloading Bridge {} · {}%", release.version, if *total > 0 { done * 100 / total } else { 0 }),
            State::Ready(r) => format!("Install Bridge {}", r.version),
            _ => String::new(),
        }.into());

        // The phone app's update.
        let phone_ver = self.phone_status.get("ver").cloned().unwrap_or_default();
        let upd = self.phone_status.get("upd").cloned().unwrap_or_else(|| "none".into());
        let parts: Vec<&str> = upd.split(':').collect();
        let (stage, version, pct) = (parts.first().copied().unwrap_or("none"), parts.get(1).copied().unwrap_or(""),
                                     parts.get(2).and_then(|p| p.parse::<f32>().ok()));
        let have = if phone_ver.is_empty() { "Unknown until the phone links".to_string() } else { phone_ver.clone() };
        let (ptitle, pdetail, pprogress, pbuttons): (String, String, f32, Vec<UpdateButton>) = if !self.linked {
            (format!("Bridge on the phone: {have}"), "Shown while the phone is nearby.".into(), -1.0, vec![])
        } else { match stage {
            "avail" => (format!("Bridge {version} for the phone"), format!("It has {have}. The phone downloads it itself."), -1.0, vec![button("Update phone", "install", true)]),
            "dl" => (format!("Downloading on the phone · {}%", pct.unwrap_or(0.0) as i32), format!("Bridge {version}"), pct.unwrap_or(0.0) / 100.0,
                     vec![button("Pause", "pause", false), button("Cancel", "cancel", false)]),
            "paused" => (format!("Paused on the phone at {}%", pct.unwrap_or(0.0) as i32), format!("Bridge {version}"), pct.unwrap_or(0.0) / 100.0,
                     vec![button("Resume", "install", true), button("Cancel", "cancel", false)]),
            "verify" => ("The phone is checking the download…".into(), format!("Bridge {version}"), -2.0, vec![]),
            "perm" => (format!("Bridge {version} is waiting on the phone"), "Allow Bridge to install apps on the phone: open Bridge there and it shows the switch.".into(), -1.0, vec![]),
            "confirm" => (format!("Bridge {version} is waiting on the phone"), "Confirm the install on the phone.".into(), -1.0, vec![]),
            "installing" => (format!("Installing Bridge {version} on the phone"), "It reconnects in a moment.".into(), -2.0, vec![]),
            "failed" => ("The phone's update didn't install".into(), "Open Bridge on the phone to see why.".into(), -1.0, vec![button("Try again", "install", true)]),
            _ => (format!("Bridge on the phone: {have}"), "Up to date as far as the phone knows.".into(), -1.0, vec![button("Check the phone for updates", "check", false)]),
        }};
        st.set_phone_upd_title(ptitle.into());
        st.set_phone_upd_detail(pdetail.into());
        st.set_phone_upd_progress(pprogress);
        st.set_phone_upd_buttons(model(pbuttons));
        ui.set_phone_update_row(if self.linked && stage == "avail" { format!("Update phone app to {version}") }
            else if self.linked && matches!(stage, "dl" | "verify" | "installing") { "Phone app updating…".into() } else { String::new() }.into());
    }
}
