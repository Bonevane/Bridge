import SwiftUI

/// Liquid Glass helpers.
///
/// The glass APIs arrived in macOS 26. Bridge still builds for macOS 13, so
/// every use goes through these wrappers: real glass on 26 and later, a
/// material-backed shape before that. Keeping the availability checks in one
/// place stops `if #available` from cluttering the views.
extension View {

    /// Floating panel surface: glass in a rounded rectangle.
    @ViewBuilder
    func glassPanel(cornerRadius: CGFloat = 16, tint: Color? = nil) -> some View {
        if #available(macOS 26.0, *) {
            // A full-strength tint turns the panel into a solid slab of colour;
            // glass is meant to be a hint of it, so the colour is heavily diluted.
            let glass = tint.map { Glass.regular.tint($0.opacity(0.22)) } ?? .regular
            self.glassEffect(glass, in: .rect(cornerRadius: cornerRadius))
        } else {
            self.background(
                RoundedRectangle(cornerRadius: cornerRadius, style: .continuous)
                    .fill(.ultraThinMaterial)
            )
        }
    }

    /// An interactive glass surface, for things that respond to the pointer.
    @ViewBuilder
    func glassInteractive(cornerRadius: CGFloat = 10, tint: Color? = nil) -> some View {
        if #available(macOS 26.0, *) {
            // One expression: a ViewBuilder body can hold declarations, but an
            // `if` statement inside it is read as a view, not as control flow.
            let glass = tint.map { Glass.regular.interactive().tint($0) }
                ?? Glass.regular.interactive()
            self.glassEffect(glass, in: .rect(cornerRadius: cornerRadius))
        } else {
            self.background(
                RoundedRectangle(cornerRadius: cornerRadius, style: .continuous)
                    .fill(.quaternary)
            )
        }
    }

    /// The main call to action.
    @ViewBuilder
    func glassProminentButton() -> some View {
        if #available(macOS 26.0, *) {
            self.buttonStyle(.glassProminent)
        } else {
            self.buttonStyle(.borderedProminent)
        }
    }

    /// A secondary button that sits on glass.
    @ViewBuilder
    func glassButton() -> some View {
        if #available(macOS 26.0, *) {
            self.buttonStyle(.glass)
        } else {
            self.buttonStyle(.bordered)
        }
    }
}

/// Groups nearby glass shapes so they blend into each other instead of each
/// carrying its own separate edge. A plain `VStack` before macOS 26.
struct GlassGroup<Content: View>: View {
    var spacing: CGFloat = 10
    @ViewBuilder var content: Content

    var body: some View {
        if #available(macOS 26.0, *) {
            GlassEffectContainer(spacing: spacing) { content }
        } else {
            content
        }
    }
}

/// Rounds the corners of the window a `MenuBarExtra(.window)` lives in.
///
/// SwiftUI gives that panel square corners, which looks wrong next to the
/// system's own menus. There's no API for it, so this reaches the hosting
/// NSWindow once the view is in a window and rounds it directly.
struct RoundedPanelWindow: NSViewRepresentable {
    var cornerRadius: CGFloat = 14

    func makeNSView(context: Context) -> NSView {
        let view = NSView(frame: .zero)
        roundRepeatedly(view)
        return view
    }

    func updateNSView(_ view: NSView, context: Context) {
        roundRepeatedly(view)
    }

    private func round(_ window: NSWindow?) {
        guard let window = window else { return }
        window.isOpaque = false
        window.backgroundColor = .clear
        window.hasShadow = true
        // The square corners come from the panel's backdrop, an
        // NSVisualEffectView that SwiftUI inserts and that ignores the content
        // view's own rounding. Round every view in the hierarchy that draws a
        // background, plus the chain above the content view.
        if let root = window.contentView?.superview ?? window.contentView {
            roundBackdrops(in: root)
        }
        // Whatever SwiftUI paints behind the panel is opaque and square. Clear it
        // so the rounded background drawn in the view itself is what shows.
        window.contentView?.layer?.backgroundColor = NSColor.clear.cgColor
        var view = window.contentView
        while let current = view {
            current.wantsLayer = true
            current.layer?.cornerRadius = cornerRadius
            current.layer?.cornerCurve = .continuous
            current.layer?.masksToBounds = true
            view = current.superview
        }
    }

    private func roundBackdrops(in view: NSView) {
        if let effect = view as? NSVisualEffectView {
            effect.wantsLayer = true
            effect.layer?.cornerRadius = cornerRadius
            effect.layer?.cornerCurve = .continuous
            effect.layer?.masksToBounds = true
            // maskImage is what NSVisualEffectView actually honours for shape.
            effect.maskImage = Self.roundedMask(radius: cornerRadius)
        }
        view.subviews.forEach { roundBackdrops(in: $0) }
    }

    /// A resizable rounded-rectangle mask, the documented way to shape a
    /// visual-effect view.
    private static func roundedMask(radius: CGFloat) -> NSImage {
        let edge = radius * 2 + 1
        let image = NSImage(size: NSSize(width: edge, height: edge), flipped: false) { rect in
            NSColor.black.setFill()
            NSBezierPath(roundedRect: rect, xRadius: radius, yRadius: radius).fill()
            return true
        }
        image.capInsets = NSEdgeInsets(top: radius, left: radius, bottom: radius, right: radius)
        image.resizingMode = .stretch
        return image
    }

    /// The window is rebuilt each time the menu opens, so keep at it briefly.
    private func roundRepeatedly(_ view: NSView) {
        for delay in [0.0, 0.05, 0.2, 0.5] {
            DispatchQueue.main.asyncAfter(deadline: .now() + delay) { round(view.window) }
        }
    }
}
