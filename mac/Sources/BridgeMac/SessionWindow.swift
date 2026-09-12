import AppKit

/// The "Phone (Bridge)" window: shows the video and turns mouse and keyboard
/// into touches and key events on the phone.
///
/// Shortcuts (⌘ so they don't collide with typing into the phone):
///   ⌘B back · ⌘H home · ⌘R recent apps · ⌘N notifications · ⌘P power ·
///   ⌘O phone screen off/on (mirroring continues) · right-click = back
final class SessionWindow: NSWindow {
    let player = H264Player(frame: NSRect(x: 0, y: 0, width: 360, height: 780))
    var session: Session?
    var onClose: (() -> Void)?
    private var screenOff = false

    init() {
        super.init(contentRect: NSRect(x: 0, y: 0, width: 360, height: 780),
                   styleMask: [.titled, .closable, .miniaturizable, .resizable],
                   backing: .buffered, defer: false)
        title = "Phone (Bridge)"
        contentView = InputView(player: player, owner: self)
        isReleasedWhenClosed = false
        acceptsMouseMovedEvents = true   // for hover
        center()
    }

    override func close() {
        super.close()
        onClose?()
    }

    /// Resize to the video's aspect ratio, at most 85% of the screen height.
    func apply(videoWidth: Int, videoHeight: Int) {
        guard videoWidth > 0, videoHeight > 0 else { return }
        let screenHeight = (screen ?? NSScreen.main)?.visibleFrame.height ?? 900
        let height = min(CGFloat(videoHeight), screenHeight * 0.85)
        let width = height * CGFloat(videoWidth) / CGFloat(videoHeight)
        contentAspectRatio = NSSize(width: videoWidth, height: videoHeight)
        setContentSize(NSSize(width: width, height: height))
    }

    // MARK: - Actions

    func pressKey(_ keycode: Int32, meta: Int32 = 0) {
        session?.send(ScrcpyProtocol.key(down: true, keycode: keycode, meta: meta))
        session?.send(ScrcpyProtocol.key(down: false, keycode: keycode, meta: meta))
    }

    override func keyDown(with event: NSEvent) {
        guard let session = session else { return }
        let flags = event.modifierFlags
        if flags.contains(.command) {
            switch event.charactersIgnoringModifiers {
            case "b": pressKey(AndroidKey.back)
            case "h": pressKey(AndroidKey.home)
            case "r": pressKey(AndroidKey.appSwitch)
            case "p": pressKey(AndroidKey.power)
            case "n": session.send(ScrcpyProtocol.simple(ScrcpyProtocol.expandNotificationPanel))
            case "o":
                screenOff.toggle()
                session.send(ScrcpyProtocol.displayPower(on: !screenOff))
            default: super.keyDown(with: event)
            }
            return
        }

        var meta: Int32 = 0
        if flags.contains(.shift) { meta |= AndroidKey.metaShift }
        if flags.contains(.control) { meta |= AndroidKey.metaCtrl }
        if flags.contains(.option) { meta |= AndroidKey.metaAlt }

        if let special = specialKey(event.keyCode) {
            session.send(ScrcpyProtocol.key(down: true, keycode: special, meta: meta))
            return
        }
        guard let chars = event.charactersIgnoringModifiers, let c = chars.first else { return }
        if let code = AndroidKey.keycode(forCharacter: c) {
            if c.isUppercase { meta |= AndroidKey.metaShift }
            session.send(ScrcpyProtocol.key(down: true, keycode: code, meta: meta))
        } else if let text = event.characters, !flags.contains(.control) {
            session.send(ScrcpyProtocol.text(text))
        }
    }

    override func keyUp(with event: NSEvent) {
        guard let session = session, !event.modifierFlags.contains(.command) else { return }
        var meta: Int32 = 0
        if event.modifierFlags.contains(.shift) { meta |= AndroidKey.metaShift }
        if event.modifierFlags.contains(.control) { meta |= AndroidKey.metaCtrl }
        if let special = specialKey(event.keyCode) {
            session.send(ScrcpyProtocol.key(down: false, keycode: special, meta: meta))
        } else if let c = event.charactersIgnoringModifiers?.first, let code = AndroidKey.keycode(forCharacter: c) {
            if c.isUppercase { meta |= AndroidKey.metaShift }
            session.send(ScrcpyProtocol.key(down: false, keycode: code, meta: meta))
        }
    }

    /// Mac virtual keycodes → Android keycodes for non-character keys.
    private func specialKey(_ code: UInt16) -> Int32? {
        switch code {
        case 36, 76: return AndroidKey.enter
        case 51: return AndroidKey.del
        case 117: return AndroidKey.forwardDel
        case 53: return AndroidKey.escape
        case 48: return AndroidKey.tab
        case 49: return AndroidKey.space
        case 126: return AndroidKey.dpadUp
        case 125: return AndroidKey.dpadDown
        case 123: return AndroidKey.dpadLeft
        case 124: return AndroidKey.dpadRight
        case 115: return AndroidKey.moveHome
        case 119: return AndroidKey.moveEnd
        case 116: return AndroidKey.pageUp
        case 121: return AndroidKey.pageDown
        default: return nil
        }
    }
}

/// The view that receives mouse events. Flipped so (0,0) is top-left like the phone.
final class InputView: NSView {
    private let player: H264Player
    private unowned let owner: SessionWindow

    init(player: H264Player, owner: SessionWindow) {
        self.player = player
        self.owner = owner
        super.init(frame: player.frame)
        autoresizesSubviews = true
        player.autoresizingMask = [.width, .height]
        addSubview(player)
        registerForDraggedTypes([.fileURL])   // drop files to send them to the phone
    }

    // MARK: - Dropping files onto the phone

    override func draggingEntered(_ sender: NSDraggingInfo) -> NSDragOperation {
        sender.draggingPasteboard.canReadObject(forClasses: [NSURL.self], options: nil) ? .copy : []
    }

    override func performDragOperation(_ sender: NSDraggingInfo) -> Bool {
        guard let urls = sender.draggingPasteboard.readObjects(forClasses: [NSURL.self], options: nil) as? [URL],
              let session = owner.session else { return false }
        for url in urls {
            session.pushFile(url) { reply in
                DispatchQueue.main.async { session.onLog?(reply) }
            }
        }
        return true
    }

    required init?(coder: NSCoder) { fatalError() }
    override var isFlipped: Bool { true }
    override var acceptsFirstResponder: Bool { true }
    override func acceptsFirstMouse(for event: NSEvent?) -> Bool { true }

    /// View point → phone video coordinates (the window keeps the video's aspect, so it's a plain scale).
    private func position(for event: NSEvent) -> ScrcpyProtocol.Position? {
        guard let session = owner.session, session.videoWidth > 0 else { return nil }
        let p = convert(event.locationInWindow, from: nil)
        let x = Int32((p.x / bounds.width * CGFloat(session.videoWidth)).rounded())
        let y = Int32((p.y / bounds.height * CGFloat(session.videoHeight)).rounded())
        return ScrcpyProtocol.Position(x: x, y: y, width: session.videoWidth, height: session.videoHeight)
    }

    override func mouseDown(with event: NSEvent) {
        guard let p = position(for: event) else { return }
        owner.session?.send(ScrcpyProtocol.touch(action: ScrcpyProtocol.actionDown, at: p, pressed: true))
    }

    override func mouseDragged(with event: NSEvent) {
        guard let p = position(for: event) else { return }
        owner.session?.send(ScrcpyProtocol.touch(action: ScrcpyProtocol.actionMove, at: p, pressed: true))
    }

    override func mouseUp(with event: NSEvent) {
        guard let p = position(for: event) else { return }
        owner.session?.send(ScrcpyProtocol.touch(action: ScrcpyProtocol.actionUp, at: p, pressed: false))
    }

    override func mouseMoved(with event: NSEvent) {
        guard let p = position(for: event) else { return }
        owner.session?.send(ScrcpyProtocol.hover(at: p))
    }

    override func rightMouseDown(with event: NSEvent) {
        owner.session?.send(ScrcpyProtocol.back(down: true))
    }

    override func rightMouseUp(with event: NSEvent) {
        owner.session?.send(ScrcpyProtocol.back(down: false))
    }

    override func scrollWheel(with event: NSEvent) {
        guard let p = position(for: event) else { return }
        // Trackpads report pixel deltas; wheels report lines. Either way Android
        // wants "notches": scale so a normal flick is a couple of units.
        let scale: CGFloat = event.hasPreciseScrollingDeltas ? 1.0 / 20 : 1.0
        let h = Float(event.scrollingDeltaX * scale)
        let v = Float(event.scrollingDeltaY * scale)
        guard h != 0 || v != 0 else { return }
        owner.session?.send(ScrcpyProtocol.scroll(at: p, h: h, v: v))
    }
}
