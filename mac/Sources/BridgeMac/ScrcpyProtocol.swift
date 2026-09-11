import Foundation

/// Encoders for scrcpy's control messages (client → server), matching
/// server/src/main/java/com/genymobile/scrcpy/control/ControlMessageReader.java
/// in scrcpy 4.1. Everything is big-endian.
enum ScrcpyProtocol {
    // Message types
    static let injectKeycode: UInt8 = 0
    static let injectText: UInt8 = 1
    static let injectTouch: UInt8 = 2
    static let injectScroll: UInt8 = 3
    static let backOrScreenOn: UInt8 = 4
    static let expandNotificationPanel: UInt8 = 5
    static let collapsePanels: UInt8 = 7
    static let setDisplayPower: UInt8 = 10

    // Android MotionEvent actions
    static let actionDown: UInt8 = 0
    static let actionUp: UInt8 = 1
    static let actionMove: UInt8 = 2
    static let actionHoverMove: UInt8 = 7

    /// "Generic finger": the server injects a touchscreen event, no mouse cursor.
    static let pointerFinger: Int64 = -2
    /// The mouse pointer: used for hover, so apps show their hover effects.
    static let pointerMouse: Int64 = -1

    struct Position {
        var x: Int32, y: Int32
        var width: UInt16, height: UInt16   // the video size the coordinates refer to
    }

    static func touch(action: UInt8, at p: Position, pressed: Bool) -> [UInt8] {
        var m = [injectTouch, action]
        m += be(pointerFinger)
        m += position(p)
        m += be(UInt16(pressed ? 0xffff : 0))   // pressure, 16-bit fixed point
        m += be(Int32(pressed ? 1 : 0))         // action button (primary)
        m += be(Int32(pressed ? 1 : 0))         // buttons
        return m
    }

    static func hover(at p: Position) -> [UInt8] {
        var m = [injectTouch, actionHoverMove]
        m += be(pointerMouse)
        m += position(p)
        m += be(UInt16(0)) + be(Int32(0)) + be(Int32(0))
        return m
    }

    static func scroll(at p: Position, h: Float, v: Float) -> [UInt8] {
        var m = [injectScroll]
        m += position(p)
        m += be(fixedPoint16(h / 16))
        m += be(fixedPoint16(v / 16))
        m += be(Int32(0))
        return m
    }

    static func key(down: Bool, keycode: Int32, meta: Int32 = 0) -> [UInt8] {
        var m = [injectKeycode, down ? actionDown : actionUp]
        m += be(keycode)
        m += be(Int32(0))       // repeat
        m += be(meta)
        return m
    }

    static func text(_ s: String) -> [UInt8] {
        let bytes = Array(s.utf8)
        return [injectText] + be(Int32(bytes.count)) + bytes
    }

    static func back(down: Bool) -> [UInt8] { [backOrScreenOn, down ? actionDown : actionUp] }
    static func displayPower(on: Bool) -> [UInt8] { [setDisplayPower, on ? 1 : 0] }
    static func simple(_ type: UInt8) -> [UInt8] { [type] }

    // MARK: - Helpers

    private static func position(_ p: Position) -> [UInt8] {
        be(p.x) + be(p.y) + be(p.width) + be(p.height)
    }

    /// Float in [-1, 1] → signed 16-bit fixed point, as the server decodes it.
    private static func fixedPoint16(_ f: Float) -> Int16 {
        let clamped = max(-1, min(1, f))
        let v = Int32(clamped * 32768)
        return Int16(max(-32768, min(32767, v)))
    }

    private static func be<T: FixedWidthInteger>(_ v: T) -> [UInt8] {
        withUnsafeBytes(of: v.bigEndian) { Array($0) }
    }
}

/// Android KeyEvent keycodes we map from the Mac keyboard.
enum AndroidKey {
    static let back: Int32 = 4
    static let home: Int32 = 3
    static let appSwitch: Int32 = 187
    static let power: Int32 = 26
    static let enter: Int32 = 66
    static let del: Int32 = 67
    static let forwardDel: Int32 = 112
    static let escape: Int32 = 111
    static let tab: Int32 = 61
    static let space: Int32 = 62
    static let dpadUp: Int32 = 19, dpadDown: Int32 = 20, dpadLeft: Int32 = 21, dpadRight: Int32 = 22
    static let moveHome: Int32 = 122, moveEnd: Int32 = 123
    static let pageUp: Int32 = 92, pageDown: Int32 = 93
    static let volumeUp: Int32 = 24, volumeDown: Int32 = 25

    static let metaShift: Int32 = 0x1 | 0x40
    static let metaCtrl: Int32 = 0x1000 | 0x2000
    static let metaAlt: Int32 = 0x2 | 0x10

    /// Letters and digits have keycodes (a=29…z=54, 0=7…9=16); use them so
    /// shortcuts like ctrl+a work and the lock screen PIN pad accepts input.
    static func keycode(forCharacter c: Character) -> Int32? {
        guard let scalar = c.unicodeScalars.first, c.unicodeScalars.count == 1 else { return nil }
        switch scalar.value {
        case 0x61...0x7a: return Int32(scalar.value - 0x61 + 29)   // a-z
        case 0x41...0x5a: return Int32(scalar.value - 0x41 + 29)   // A-Z (caller adds shift)
        case 0x30...0x39: return Int32(scalar.value - 0x30 + 7)    // 0-9
        default: return nil
        }
    }
}
