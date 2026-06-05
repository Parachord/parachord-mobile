import SwiftUI
import JavaScriptCore
import Shared

@main
struct ParachordApp: App {

    init() {
        // Wire the .axe plugin host's NativeBridge installer at STARTUP, before
        // any view (or resolution) runs. IosJsRuntime.initialize() only loads
        // resolver-loader.js + the plugins when this installer is present;
        // otherwise it stands up a bare JSContext with no window.__resolverLoader.
        // initialize() is idempotent, so if any production resolution
        // (badges, tap-to-play, background pre-resolution) ran before this was
        // set, the host stayed plugin-less for the whole session. Previously it
        // was only set in the Dev tab, so resolving before visiting Dev broke
        // the entire resolver pipeline. Setting it here guarantees full mode.
        IosContainer.companion.shared.pluginJsRuntime.nativeBridgeInstaller = { ctx in
            JsPolyfills.installNativeBridge(to: ctx)
        }
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
                .task {
                    // Pre-warm the plugin host so the first resolve isn't a
                    // cold start (loading 19 .axe files into JSC). The installer
                    // above is already set, so this initializes in full mode.
                    _ = try? await IosContainer.companion.shared.loadPluginsAndList()
                }
        }
    }
}
