import AppKit

/// The "Phone (Bridge)" window: shows the video and turns mouse and keyboard
/// into touches and key events on the phone.
///
/// Shortcuts (⌘ so they don't collide with typing into the phone):
///   ⌘B back · ⌘H home · ⌘R recent apps · ⌘N notifications · ⌘P power ·
///   ⌘O phone screen off/on (mirroring continues) · right-click = back
final class SessionWindow: NSWindow, NSWindowDelegate {
    let player = H264Player(frame: NSRect(x: 0, y: 0, width: 360, height: 780))
    var session: Session?
    var onClose: (() -> Void)?
    private var screenOff = false
    /// Back, Home, volume…: a slim bar beside the picture (never over it).
    private(set) lazy var controlBar = ControlBar(owner: self)
    private var videoAspect: CGFloat = 0

    init() {
        super.init(contentRect: NSRect(x: 0, y: 0, width: 360 + ControlBar.width, height: 780),
                   styleMask: [.titled, .closable, .miniaturizable, .resizable],
                   backing: .buffered, defer: false)
        title = "Phone (Bridge)"
        let container = NSView(frame: NSRect(x: 0, y: 0, width: 360 + ControlBar.width, height: 780))
        let input = InputView(player: player, owner: self)
        input.frame = NSRect(x: 0, y: 0, width: 360, height: 780)
        input.autoresizingMask = [.width, .height]
        controlBar.frame = NSRect(x: 360, y: 0, width: ControlBar.width, height: 780)
        controlBar.autoresizingMask = [.minXMargin, .height]
        container.addSubview(input)
        container.addSubview(controlBar)
        contentView = container
        delegate = self
        isReleasedWhenClosed = false
        acceptsMouseMovedEvents = true   // for hover
        center()
    }

    /// Keeps the picture at the phone's aspect while resizing; the bar is a
    /// fixed strip beside it, so a plain contentAspectRatio can't do this.
    func windowWillResize(_ sender: NSWindow, to frameSize: NSSize) -> NSSize {
        guard videoAspect > 0 else { return frameSize }
        let content = contentRect(forFrameRect: NSRect(origin: .zero, size: frameSize)).size
        let width = content.height * videoAspect + ControlBar.width
        return frameRect(forContentRect: NSRect(x: 0, y: 0, width: width, height: content.height)).size
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
        videoAspect = CGFloat(videoWidth) / CGFloat(videoHeight)
        setContentSize(NSSize(width: width + ControlBar.width, height: height))
    }

    // MARK: - Actions

    func pressKey(_ keycode: Int32, meta: Int32 = 0) {
        session?.send(ScrcpyProtocol.key(down: true, keycode: keycode, meta: meta))
        session?.send(ScrcpyProtocol.key(down: false, keycode: keycode, meta: meta))
    }

    /// One of the bar's buttons.
    func barAction(_ action: ControlBar.Action) {
        guard let session = session else { return }
        switch action {
        case .back: pressKey(AndroidKey.back)
        case .home: pressKey(AndroidKey.home)
        case .recents: pressKey(AndroidKey.appSwitch)
        case .volumeUp: pressKey(24)
        case .volumeDown: pressKey(25)
        case .rotate: session.send(ScrcpyProtocol.simple(11))          // scrcpy ROTATE_DEVICE
        case .screenshot: pressKey(120)                               // KEYCODE_SYSRQ
        case .notifications: session.send(ScrcpyProtocol.simple(ScrcpyProtocol.expandNotificationPanel))
        case .files: Task { @MainActor in BridgeController.shared.openPhoneFiles() }
        case .screenOff:
            screenOff.toggle()
            session.send(ScrcpyProtocol.displayPower(on: !screenOff))
        }
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
        for url in urls where !url.hasDirectoryPath {
            let name = url.lastPathComponent
            session.pushFile(url, progress: { [weak self] done, total in
                DispatchQueue.main.async {
                    self?.transferBar.show("Sending \(name) · \(PhoneFiles.humanSize(done)) of \(PhoneFiles.humanSize(total))",
                                           progress: total > 0 ? Double(done) / Double(total) : 1)
                }
            }, completion: { [weak self] message, _ in
                DispatchQueue.main.async {
                    session.onLog?(message)
                    self?.transferBar.finish(message)
                }
            })
        }
        return true
    }

    /// A Blip-style bar over the bottom of the phone while a file is moving.
    private lazy var transferBar: TransferBar = {
        let bar = TransferBar(frame: .zero)
        bar.isHidden = true
        addSubview(bar)
        return bar
    }()

    override func layout() {
        super.layout()
        transferBar.frame = NSRect(x: 12, y: bounds.height - 66, width: bounds.width - 24, height: 54)
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
        owner.controlBar.wake()   // the pointer's here: bring the bar back
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


/// "Sending photo.jpg · 3 MB of 8 MB" and a bar, on a dark rounded plate.
final class TransferBar: NSView {
    private let label = NSTextField(labelWithString: "")
    private let bar = NSProgressIndicator()
    private var hideWork: DispatchWorkItem?

    override init(frame: NSRect) {
        super.init(frame: frame)
        wantsLayer = true
        layer?.backgroundColor = NSColor.black.withAlphaComponent(0.8).cgColor
        layer?.cornerRadius = 14
        label.textColor = .white
        label.font = .systemFont(ofSize: 12)
        label.lineBreakMode = .byTruncatingMiddle
        bar.isIndeterminate = false
        bar.minValue = 0
        bar.maxValue = 1
        bar.style = .bar
        addSubview(label)
        addSubview(bar)
    }

    required init?(coder: NSCoder) { fatalError() }
    override var isFlipped: Bool { true }

    override func layout() {
        super.layout()
        label.frame = NSRect(x: 12, y: 8, width: bounds.width - 24, height: 18)
        bar.frame = NSRect(x: 12, y: 30, width: bounds.width - 24, height: 14)
    }

    // Clicks go through to the phone underneath.
    override func hitTest(_ point: NSPoint) -> NSView? { nil }

    func show(_ text: String, progress: Double) {
        hideWork?.cancel()
        label.stringValue = text
        bar.doubleValue = progress
        isHidden = false
    }

    /// Leaves the result up for a few seconds, then goes.
    func finish(_ text: String) {
        show(text, progress: 1)
        let work = DispatchWorkItem { [weak self] in self?.isHidden = true }
        hideWork = work
        DispatchQueue.main.asyncAfter(deadline: .now() + 3, execute: work)
    }
}


/// The slim bar beside the phone picture: Back, Home, Recents, volume,
/// rotate, screenshot, notifications, phone files, screen off. It fades to a
/// faint strip after a few seconds without the pointer, and comes back when
/// the pointer moves over the window. Tooltips name each button and its
/// shortcut.
final class ControlBar: NSView {
    static let width: CGFloat = 40

    enum Action { case back, home, recents, volumeUp, volumeDown, rotate, screenshot, notifications, files, screenOff }

    private unowned let owner: SessionWindow
    private var fadeWork: DispatchWorkItem?
    private var pointerInside = false

    init(owner: SessionWindow) {
        self.owner = owner
        super.init(frame: .zero)
        wantsLayer = true
        layer?.backgroundColor = NSColor.windowBackgroundColor.cgColor
        let items: [(String, String, Action)?] = [
            ("chevron.backward", "Back · ⌘B", .back),
            ("circle", "Home · ⌘H", .home),
            ("square.on.square", "Recent apps · ⌘R", .recents),
            nil,
            ("speaker.wave.3", "Volume up", .volumeUp),
            ("speaker.wave.1", "Volume down", .volumeDown),
            nil,
            ("rotate.right", "Rotate", .rotate),
            ("camera", "Screenshot", .screenshot),
            ("bell", "Notifications · ⌘N", .notifications),
            ("folder", "Phone files", .files),
            nil,
            ("power", "Screen off · ⌘O", .screenOff),
        ]
        let stack = NSStackView()
        stack.orientation = .vertical
        stack.spacing = 6
        stack.alignment = .centerX
        for item in items {
            guard let (symbol, tip, action) = item else {
                let line = NSBox(); line.boxType = .separator
                line.widthAnchor.constraint(equalToConstant: 18).isActive = true
                stack.addArrangedSubview(line)
                continue
            }
            let button = NSButton(image: NSImage(systemSymbolName: symbol, accessibilityDescription: tip) ?? NSImage(),
                                  target: self, action: #selector(tapped(_:)))
            button.isBordered = false
            button.toolTip = tip
            button.tag = Self.order.firstIndex(of: action) ?? 0
            button.symbolConfiguration = NSImage.SymbolConfiguration(pointSize: 14, weight: .regular)
            button.contentTintColor = .labelColor
            button.widthAnchor.constraint(equalToConstant: 28).isActive = true
            button.heightAnchor.constraint(equalToConstant: 26).isActive = true
            stack.addArrangedSubview(button)
        }
        stack.translatesAutoresizingMaskIntoConstraints = false
        addSubview(stack)
        NSLayoutConstraint.activate([
            stack.centerXAnchor.constraint(equalTo: centerXAnchor),
            stack.centerYAnchor.constraint(equalTo: centerYAnchor),
        ])
        addTrackingArea(NSTrackingArea(rect: .zero, options: [.mouseEnteredAndExited, .activeAlways, .inVisibleRect],
                                       owner: self, userInfo: nil))
        wake()
    }

    required init?(coder: NSCoder) { fatalError() }

    private static let order: [Action] = [.back, .home, .recents, .volumeUp, .volumeDown, .rotate, .screenshot, .notifications, .files, .screenOff]

    @objc private func tapped(_ sender: NSButton) {
        owner.barAction(Self.order[sender.tag])
        wake()
    }

    override func mouseEntered(with event: NSEvent) { pointerInside = true; wake() }
    override func mouseExited(with event: NSEvent) { pointerInside = false; wake() }

    /// Full strength now, faint again after 3 s unless the pointer is on the bar.
    func wake() {
        fadeWork?.cancel()
        NSAnimationContext.runAnimationGroup { $0.duration = 0.15; animator().alphaValue = 1 }
        guard !pointerInside else { return }
        let work = DispatchWorkItem { [weak self] in
            guard let self = self, !self.pointerInside else { return }
            NSAnimationContext.runAnimationGroup { $0.duration = 0.4; self.animator().alphaValue = 0.3 }
        }
        fadeWork = work
        DispatchQueue.main.asyncAfter(deadline: .now() + 3, execute: work)
    }
}
