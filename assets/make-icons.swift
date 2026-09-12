// Turns assets/bridge-mark.png (the black bridge on transparent) into every
// icon both apps need. Run it through make-icons.sh, not by hand.
//
// The mark is treated purely as a *shape*: a mask is built from the source
// pixels (alpha × darkness), so it can be recoloured white for the icons and
// the source can be black-on-transparent or black-on-white, whichever you have.
//
//   macOS    → mac/Bridge.icns            white mark on a navy rounded square
//   Android  → mipmap-*/ic_launcher_fg    white mark, transparent (adaptive foreground)
//              mipmap-*/ic_launcher_mono  same shape (themed icons; system tints it)
//              drawable-*/ic_stat_bridge  white mark at 24 dp (notification small icon)
//              values/ic_launcher_background.xml  the navy
import AppKit

let args = CommandLine.arguments
guard args.count == 4 else {
    print("usage: make-icons <mark.png> <mac dir> <android res dir>"); exit(1)
}
let (source, macDir, resDir) = (args[1], args[2], args[3])

// The brand navy: a gradient on the macOS icon, and its darker end as the flat
// Android background (the launcher adds its own lighting). #122048 → #0A1432.
func rgb(_ r: Int, _ g: Int, _ b: Int) -> NSColor {
    NSColor(calibratedRed: CGFloat(r) / 255, green: CGFloat(g) / 255, blue: CGFloat(b) / 255, alpha: 1)
}
let navyTop = rgb(0x12, 0x20, 0x48)
let navyBottom = rgb(0x0A, 0x14, 0x32)

// MARK: - The mark as a mask

guard let src = NSImage(contentsOfFile: source),
      let cg = src.cgImage(forProposedRect: nil, context: nil, hints: nil) else {
    print("can't read \(source)"); exit(1)
}
let w = cg.width, h = cg.height
var rgba = [UInt8](repeating: 0, count: w * h * 4)
let ctx = CGContext(data: &rgba, width: w, height: h, bitsPerComponent: 8, bytesPerRow: w * 4,
                    space: CGColorSpaceCreateDeviceRGB(),
                    bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)!
ctx.draw(cg, in: CGRect(x: 0, y: 0, width: w, height: h))

// alpha = source alpha × how dark the pixel is, and find the shape's bounds
// so it can be centred regardless of how much padding the source has.
var mask = [UInt8](repeating: 0, count: w * h)
var minX = w, minY = h, maxX = 0, maxY = 0
for y in 0..<h {
    for x in 0..<w {
        let i = (y * w + x) * 4
        let a = Int(rgba[i + 3])
        guard a > 0 else { continue }
        // Un-premultiply, then darkness = 1 - luminance.
        let lum = (Int(rgba[i]) * 299 + Int(rgba[i + 1]) * 587 + Int(rgba[i + 2]) * 114) / 1000 * 255 / a
        let v = a * (255 - min(lum, 255)) / 255
        mask[y * w + x] = UInt8(v)
        if v > 16 { minX = min(minX, x); maxX = max(maxX, x); minY = min(minY, y); maxY = max(maxY, y) }
    }
}
let bounds = CGRect(x: minX, y: h - 1 - maxY, width: maxX - minX + 1, height: maxY - minY + 1)
print("mark bounds in source: \(Int(bounds.width))×\(Int(bounds.height)) px")

let maskImage: CGImage = {
    let provider = CGDataProvider(data: Data(mask) as CFData)!
    return CGImage(width: w, height: h, bitsPerComponent: 8, bitsPerPixel: 8, bytesPerRow: w,
                   space: CGColorSpaceCreateDeviceGray(), bitmapInfo: CGBitmapInfo(rawValue: 0),
                   provider: provider, decode: nil, shouldInterpolate: true, intent: .defaultIntent)!
}()

/// Draws the mark in `color`, fitted into `rect` (aspect kept, centred).
func drawMark(_ c: CGContext, in rect: CGRect, color: NSColor) {
    let scale = min(rect.width / bounds.width, rect.height / bounds.height)
    let size = CGSize(width: bounds.width * scale, height: bounds.height * scale)
    let origin = CGPoint(x: rect.midX - size.width / 2, y: rect.midY - size.height / 2)
    // Clip to the mask, positioned so the shape's own bounds land in `rect`.
    let full = CGRect(x: origin.x - bounds.minX * scale, y: origin.y - bounds.minY * scale,
                      width: CGFloat(w) * scale, height: CGFloat(h) * scale)
    c.saveGState()
    c.clip(to: full, mask: maskImage)
    c.setFillColor(color.cgColor)
    c.fill(full)
    c.restoreGState()
}

func png(_ size: Int, _ draw: (CGContext, CGFloat) -> Void) -> Data {
    let s = CGFloat(size)
    let c = CGContext(data: nil, width: size, height: size, bitsPerComponent: 8, bytesPerRow: 0,
                      space: CGColorSpaceCreateDeviceRGB(),
                      bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)!
    c.interpolationQuality = .high
    draw(c, s)
    let rep = NSBitmapImageRep(cgImage: c.makeImage()!)
    return rep.representation(using: .png, properties: [:])!
}

func write(_ data: Data, _ path: String) {
    try! FileManager.default.createDirectory(atPath: (path as NSString).deletingLastPathComponent,
                                             withIntermediateDirectories: true)
    try! data.write(to: URL(fileURLWithPath: path))
}

// MARK: - macOS

// Apple's grid: the rounded square is 824/1024 of the canvas, corner 185/1024.
// The mark sits at about 60% of the square's width, which is where a wide mark
// stops looking cramped without going thin.
func macIcon(_ c: CGContext, _ s: CGFloat) {
    let inset = s * (1 - 824.0 / 1024) / 2
    let square = CGRect(x: inset, y: inset, width: s - inset * 2, height: s - inset * 2)
    c.saveGState()
    c.addPath(CGPath(roundedRect: square, cornerWidth: s * 185 / 1024, cornerHeight: s * 185 / 1024, transform: nil))
    c.clip()
    let gradient = CGGradient(colorsSpace: CGColorSpaceCreateDeviceRGB(),
                              colors: [navyTop.cgColor, navyBottom.cgColor] as CFArray, locations: [0, 1])!
    c.drawLinearGradient(gradient, start: CGPoint(x: 0, y: square.maxY), end: CGPoint(x: 0, y: square.minY), options: [])
    c.restoreGState()
    drawMark(c, in: square.insetBy(dx: square.width * 0.20, dy: square.height * 0.20), color: .white)
}

let iconset = "\(macDir)/Bridge.iconset"
for (name, size) in [("16x16", 16), ("16x16@2x", 32), ("32x32", 32), ("32x32@2x", 64),
                     ("128x128", 128), ("128x128@2x", 256), ("256x256", 256), ("256x256@2x", 512),
                     ("512x512", 512), ("512x512@2x", 1024)] {
    write(png(size, macIcon), "\(iconset)/icon_\(name).png")
}
print("wrote \(iconset)")

// Menu-bar icon: a template image, black on transparent, 18 pt tall with the
// mark's own aspect. AppKit recolours templates for light/dark menu bars.
for (suffix, scale) in [("", 1), ("@2x", 2)] {
    // The image is 18 pt tall like every other item; the mark itself is drawn
    // 9 pt tall so its width (it is 2.2:1) stays close to its neighbours.
    let ht = 18 * scale, wd = Int((CGFloat(ht) * 0.50 * bounds.width / bounds.height).rounded())
    let c = CGContext(data: nil, width: wd, height: ht, bitsPerComponent: 8, bytesPerRow: 0,
                      space: CGColorSpaceCreateDeviceRGB(),
                      bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)!
    c.interpolationQuality = .high
    let box = CGRect(x: 0, y: 0, width: wd, height: ht).insetBy(dx: 0, dy: CGFloat(ht) * 0.25)
    drawMark(c, in: box, color: .black)
    let rep = NSBitmapImageRep(cgImage: c.makeImage()!)
    write(rep.representation(using: .png, properties: [:])!, "\(macDir)/MenuBarIcon\(suffix).png")
}
print("wrote menu-bar template images")

// MARK: - Android

let densities: [(String, CGFloat)] = [("mdpi", 1), ("hdpi", 1.5), ("xhdpi", 2), ("xxhdpi", 3), ("xxxhdpi", 4)]

// Adaptive icons are 108 dp with a 66 dp safe zone in the middle; anything
// outside can be cropped by the launcher's shape. The mark is fitted to the
// safe zone with a little air.
for (name, scale) in densities {
    let size = Int(108 * scale)
    let fg = png(size) { c, s in
        let safe = s * 66 / 108
        // Optically the mark sits high (the arches are empty space below the
        // slab), so it's nudged down a little from the geometric centre.
        let rect = CGRect(x: (s - safe) / 2, y: (s - safe) / 2 - safe * 0.04, width: safe, height: safe)
        drawMark(c, in: rect.insetBy(dx: safe * 0.11, dy: safe * 0.11), color: .white)
    }
    write(fg, "\(resDir)/mipmap-\(name)/ic_launcher_fg.png")
    // The monochrome layer is only read for its alpha; the system supplies the colour.
    write(fg, "\(resDir)/mipmap-\(name)/ic_launcher_mono.png")

    // Notification small icons are 24 dp, white on transparent, or Android
    // paints a grey square. 2 dp of padding keeps it inside the status bar's optical box.
    let stat = png(Int(24 * scale)) { c, s in
        drawMark(c, in: CGRect(x: 0, y: 0, width: s, height: s).insetBy(dx: s * 2 / 24, dy: s * 2 / 24), color: .white)
    }
    write(stat, "\(resDir)/drawable-\(name)/ic_stat_bridge.png")
}

write(Data("""
<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/ic_launcher_background"/>
    <foreground android:drawable="@mipmap/ic_launcher_fg"/>
    <monochrome android:drawable="@mipmap/ic_launcher_mono"/>
</adaptive-icon>

""".utf8), "\(resDir)/mipmap-anydpi-v26/ic_launcher.xml")

let hex = String(format: "#%02X%02X%02X", Int(navyBottom.redComponent * 255), Int(navyBottom.greenComponent * 255), Int(navyBottom.blueComponent * 255))
write(Data("""
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <color name="ic_launcher_background">\(hex)</color>
</resources>

""".utf8), "\(resDir)/values/ic_launcher_background.xml")
print("wrote Android icons into \(resDir)")
