// swift-tools-version:5.9
import PackageDescription

let package = Package(
    name: "BridgeMac",
    platforms: [.macOS(.v13)],
    targets: [
        .executableTarget(name: "BridgeMac", path: "Sources/BridgeMac")
    ]
)
