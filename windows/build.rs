fn main() {
    // Icons and the window icon are compiled into the binary: Bridge.exe is
    // copied around as a single file (plus dumbpipe/adb), so nothing may be
    // loaded from this source tree at run time. fluent-dark is only for the
    // std-widgets we still use (ScrollView, LineEdit); everything visible is
    // ui/theme.slint.
    let config = slint_build::CompilerConfiguration::new()
        .with_style("fluent-dark".into())
        .embed_resources(slint_build::EmbedResourcesKind::EmbedFiles);
    slint_build::compile_with_config("ui/main.slint", config).expect("compiling ui/main.slint");

    // The exe's own icon (Explorer, taskbar, Alt-Tab). Needs the Windows SDK's
    // rc.exe, which the CI runner has; skipped elsewhere.
    if std::env::var("CARGO_CFG_TARGET_OS").as_deref() == Ok("windows") {
        println!("cargo:rerun-if-changed=assets/icon.ico");
        if let Err(e) = winresource::WindowsResource::new().set_icon("assets/icon.ico").compile() {
            println!("cargo:warning=no exe icon: {e}");
        }
    }
}
