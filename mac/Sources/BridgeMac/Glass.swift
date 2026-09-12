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
            let glass = tint.map { Glass.regular.tint($0) } ?? .regular
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
