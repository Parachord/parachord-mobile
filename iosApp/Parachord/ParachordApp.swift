import SwiftUI
import JavaScriptCore
import Shared

@main
struct ParachordApp: App {
    // NOTE: deliberately NO container init() work. Touching IosContainer.companion.shared
    // here forced Koin + plugin-host init SYNCHRONOUSLY before SwiftUI could
    // commit its first frame, so the system's (white) launch screen lingered for
    // seconds. All container access now happens inside RootGate's async warm-up,
    // AFTER the splash's first frame is on screen.
    init() {
        // BGTaskScheduler.register MUST run before the app finishes launching. It
        // only installs a closure — it does NOT touch IosContainer (the handler
        // does, but that runs later), so it's safe here without blocking the first frame.
        BackgroundSync.register()
        // #322: read-only audio-session event logging (Console filter: PCAUDIO).
        AudioSessionDiagnostics.install()
    }
    var body: some Scene {
        WindowGroup {
            RootGate()
        }
    }
}

/// Shows an animated branded splash over the app while the plugin host warms up
/// (loading 19 .axe files into JSC is the slow part of cold start). The splash
/// fades out once warm-up finishes (or a short floor elapses, so it never just
/// blinks). Mirrors the desktop's loading screen.
private struct RootGate: View {
    @State private var ready = false
    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        ZStack {
            // Defer ContentView (and its heavy container/plugin init) until the
            // warm-up finishes, so the FIRST committed frame is the cheap splash
            // — otherwise the system's white launch screen lingers through the
            // cold start and the brand splash never shows.
            if ready { ContentView().transition(.opacity) }
            if !ready { PCSplash().transition(.opacity).zIndex(100) }
        }
        .task {
            async let warm: () = {
                // Install the .axe NativeBridge polyfill BEFORE plugins load —
                // moved here from App.init so the container's first-access init
                // (Koin + plugin host) runs AFTER the splash is on screen, not
                // before the first frame. IosJsRuntime.initialize() only loads
                // the resolvers when this installer is present.
                IosContainer.companion.shared.pluginJsRuntime.nativeBridgeInstaller = { ctx in
                    JsPolyfills.installNativeBridge(to: ctx)
                }
                _ = try? await IosContainer.companion.shared.loadPluginsAndList()
                // Marketplace plugin sync (24h-debounced) so playback-telemetry
                // plugins like achordion download + hot-reload/register without a
                // manual Settings trigger (Android parity: MainViewModel.syncIfNeeded).
                // Fire-and-forget — never blocks the splash.
                Task { _ = try? await IosContainer.companion.shared.syncPluginsIfNeeded() }
            }()
            // Minimum on-screen time so the splash doesn't flash on a warm start.
            async let floor: () = { _ = try? await Task.sleep(nanoseconds: 900_000_000) }()
            _ = await (warm, floor)
            withAnimation(.easeOut(duration: 0.35)) { ready = true }
            BackgroundSync.schedule()   // queue a first background run
            // Generate the 2×2 album-art mosaic for any playlist without one
            // (ListenBrainz weeklies, hosted XSPF, …). Idempotent — only touches
            // playlists whose art isn't already a file:// mosaic. Android parity:
            // ParachordApplication.onCreate. Fire-and-forget.
            Task { try? await IosContainer.companion.shared.regeneratePlaylistMosaics() }
        }
        // Re-queue the background sync whenever we leave the foreground (Apple's
        // recommended scheduling point).
        .onChange(of: scenePhase) { _, phase in
            if phase == .background { BackgroundSync.schedule() }
        }
    }
}

/// Branded launch animation: the Parachord mark on the brand background with a
/// gentle pulse + a spinner, so the long cold start reads as "loading", not hung.
struct PCSplash: View {
    @State private var pulse = false

    var body: some View {
        ZStack {
            Color(uiColor: UIColor(hex: 0x273441)).ignoresSafeArea()
            VStack(spacing: 22) {
                Image("AppLogo")
                    .resizable().scaledToFit()
                    .frame(width: 132, height: 132)
                    .clipShape(RoundedRectangle(cornerRadius: 28, style: .continuous))
                    .scaleEffect(pulse ? 1.04 : 0.96)
                    .opacity(pulse ? 1 : 0.85)
                    .shadow(color: .black.opacity(0.35), radius: 24, y: 12)
                ProgressView()
                    .progressViewStyle(.circular)
                    .tint(.white.opacity(0.85))
            }
        }
        .onAppear {
            withAnimation(.easeInOut(duration: 1.0).repeatForever(autoreverses: true)) { pulse = true }
        }
    }
}
