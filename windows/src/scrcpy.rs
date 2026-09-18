//! Encoders for scrcpy's control messages (client → server), matching
//! server/src/main/java/com/genymobile/scrcpy/control/ControlMessageReader.java
//! in scrcpy 4.1. Everything is big-endian. Port of ScrcpyProtocol.swift.

pub const INJECT_KEYCODE: u8 = 0;
pub const INJECT_TEXT: u8 = 1;
pub const INJECT_TOUCH: u8 = 2;
pub const INJECT_SCROLL: u8 = 3;
pub const BACK_OR_SCREEN_ON: u8 = 4;
pub const EXPAND_NOTIFICATION_PANEL: u8 = 5;
pub const COLLAPSE_PANELS: u8 = 7;
pub const GET_CLIPBOARD: u8 = 8;
pub const SET_CLIPBOARD: u8 = 9;
pub const SET_DISPLAY_POWER: u8 = 10;

pub const ACTION_DOWN: u8 = 0;
pub const ACTION_UP: u8 = 1;
pub const ACTION_MOVE: u8 = 2;
pub const ACTION_HOVER_MOVE: u8 = 7;

/// "Generic finger": the server injects a touchscreen event, no mouse cursor.
const POINTER_FINGER: i64 = -2;
/// The mouse pointer: used for hover, so apps show their hover effects.
const POINTER_MOUSE: i64 = -1;

#[derive(Clone, Copy)]
pub struct Position {
    pub x: i32,
    pub y: i32,
    /// The video size the coordinates refer to.
    pub width: u16,
    pub height: u16,
}

fn position(p: Position, out: &mut Vec<u8>) {
    out.extend(p.x.to_be_bytes());
    out.extend(p.y.to_be_bytes());
    out.extend(p.width.to_be_bytes());
    out.extend(p.height.to_be_bytes());
}

pub fn touch(action: u8, p: Position, pressed: bool) -> Vec<u8> {
    let mut m = vec![INJECT_TOUCH, action];
    m.extend(POINTER_FINGER.to_be_bytes());
    position(p, &mut m);
    m.extend((if pressed { 0xffffu16 } else { 0 }).to_be_bytes()); // pressure, 16-bit fixed point
    m.extend((if pressed { 1i32 } else { 0 }).to_be_bytes()); // action button (primary)
    m.extend((if pressed { 1i32 } else { 0 }).to_be_bytes()); // buttons
    m
}

pub fn hover(p: Position) -> Vec<u8> {
    let mut m = vec![INJECT_TOUCH, ACTION_HOVER_MOVE];
    m.extend(POINTER_MOUSE.to_be_bytes());
    position(p, &mut m);
    m.extend(0u16.to_be_bytes());
    m.extend(0i32.to_be_bytes());
    m.extend(0i32.to_be_bytes());
    m
}

pub fn scroll(p: Position, h: f32, v: f32) -> Vec<u8> {
    let mut m = vec![INJECT_SCROLL];
    position(p, &mut m);
    m.extend(fixed_point16(h / 16.0).to_be_bytes());
    m.extend(fixed_point16(v / 16.0).to_be_bytes());
    m.extend(0i32.to_be_bytes());
    m
}

pub fn key(down: bool, keycode: i32, meta: i32) -> Vec<u8> {
    let mut m = vec![INJECT_KEYCODE, if down { ACTION_DOWN } else { ACTION_UP }];
    m.extend(keycode.to_be_bytes());
    m.extend(0i32.to_be_bytes()); // repeat
    m.extend(meta.to_be_bytes());
    m
}

pub fn text(s: &str) -> Vec<u8> {
    let mut m = vec![INJECT_TEXT];
    m.extend((s.len() as i32).to_be_bytes());
    m.extend(s.as_bytes());
    m
}

/// Push text to the phone's clipboard (paste=false: set only, don't auto-paste).
pub fn clipboard(text: &str) -> Vec<u8> {
    let mut m = vec![SET_CLIPBOARD];
    m.extend(0u64.to_be_bytes()); // sequence
    m.push(0);
    m.extend((text.len() as u32).to_be_bytes());
    m.extend(text.as_bytes());
    m
}

/// Ask the phone to send its current clipboard back (arrives as a device message).
pub fn get_clipboard() -> Vec<u8> {
    vec![GET_CLIPBOARD, 0]
}

pub fn back(down: bool) -> Vec<u8> {
    vec![BACK_OR_SCREEN_ON, if down { ACTION_DOWN } else { ACTION_UP }]
}

pub fn display_power(on: bool) -> Vec<u8> {
    vec![SET_DISPLAY_POWER, if on { 1 } else { 0 }]
}

pub fn simple(kind: u8) -> Vec<u8> {
    vec![kind]
}

/// Float in [-1, 1] → signed 16-bit fixed point, as the server decodes it.
fn fixed_point16(f: f32) -> i16 {
    let v = (f.clamp(-1.0, 1.0) * 32768.0) as i32;
    v.clamp(-32768, 32767) as i16
}

/// Android KeyEvent keycodes we map from the PC keyboard.
pub mod android_key {
    pub const BACK: i32 = 4;
    pub const HOME: i32 = 3;
    pub const APP_SWITCH: i32 = 187;
    pub const POWER: i32 = 26;
    pub const ENTER: i32 = 66;
    pub const DEL: i32 = 67;
    pub const FORWARD_DEL: i32 = 112;
    pub const ESCAPE: i32 = 111;
    pub const TAB: i32 = 61;
    pub const SPACE: i32 = 62;
    pub const DPAD_UP: i32 = 19;
    pub const DPAD_DOWN: i32 = 20;
    pub const DPAD_LEFT: i32 = 21;
    pub const DPAD_RIGHT: i32 = 22;
    pub const MOVE_HOME: i32 = 122;
    pub const MOVE_END: i32 = 123;
    pub const PAGE_UP: i32 = 92;
    pub const PAGE_DOWN: i32 = 93;

    pub const META_SHIFT: i32 = 0x1 | 0x40;
    pub const META_CTRL: i32 = 0x1000 | 0x2000;
    pub const META_ALT: i32 = 0x2 | 0x10;

    /// Letters and digits have keycodes (a=29…z=54, 0=7…9=16); use them so
    /// shortcuts like ctrl+a work and the lock screen PIN pad accepts input.
    pub fn for_char(c: char) -> Option<i32> {
        match c {
            'a'..='z' => Some(c as i32 - 'a' as i32 + 29),
            'A'..='Z' => Some(c as i32 - 'A' as i32 + 29),
            '0'..='9' => Some(c as i32 - '0' as i32 + 7),
            _ => None,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn touch_message_layout() {
        let m = touch(ACTION_DOWN, Position { x: 10, y: 20, width: 1080, height: 2400 }, true);
        assert_eq!(m.len(), 2 + 8 + 12 + 2 + 4 + 4);
        assert_eq!(&m[..2], &[INJECT_TOUCH, ACTION_DOWN]);
        assert_eq!(&m[2..10], &(-2i64).to_be_bytes());
        assert_eq!(&m[10..14], &10i32.to_be_bytes());
    }

    #[test]
    fn fixed_point_clamps() {
        assert_eq!(fixed_point16(2.0), 32767);
        assert_eq!(fixed_point16(-2.0), -32768);
        assert_eq!(fixed_point16(0.0), 0);
    }
}
