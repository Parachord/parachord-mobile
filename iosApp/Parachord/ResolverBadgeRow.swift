import SwiftUI
import Foundation
import Shared

// MARK: - Resolver icon squares (matches Android's ResolverIconSquare/Row)
//
// Small brand-colored rounded squares (NOT text capsules) — the same visual
// language as Android/desktop. Confidence-aware per CLAUDE.md "Resolver Badge
// Display":
//   1. filter noMatch / confidence < 0.60
//   2. dim icons with confidence <= 0.80 to 0.6 alpha
//   3. sort by resolver priority (canonical order), then confidence desc —
//      the leftmost square is the source that will actually play
//
// Display-only, like Android (the track ROW handles playback). iOS has no
// brand-logo assets, so each square shows the resolver initial in white; the
// brand COLOR is the primary identifier, matching ResolverIconColors.

struct ResolverIconSquare: View {
    let resolver: String
    var size: CGFloat = 18
    var confidence: Double = 1

    // ResolverIconColors.forResolver (Android), verbatim.
    private var color: Color {
        switch resolver.lowercased() {
        case "spotify":    return Color(uiColor: UIColor(hex: 0x1DB954))
        case "applemusic": return Color(uiColor: UIColor(hex: 0xFA243C))
        case "soundcloud": return Color(uiColor: UIColor(hex: 0xFF5500))
        case "bandcamp":   return Color(uiColor: UIColor(hex: 0x629AA9))
        case "youtube":    return Color(uiColor: UIColor(hex: 0xFF0000))
        case "localfiles": return Color(uiColor: UIColor(hex: 0xA855F7))
        default:           return Color(uiColor: UIColor(hex: 0x7c3aed))
        }
    }
    private var glyph: String {
        switch resolver.lowercased() {
        case "applemusic": return "A"
        case "soundcloud": return "S"
        case "spotify":    return "S"
        case "bandcamp":   return "B"
        case "youtube":    return "Y"
        case "localfiles": return "L"
        default:           return resolver.prefix(1).uppercased()
        }
    }

    var body: some View {
        RoundedRectangle(cornerRadius: 4, style: .continuous)
            .fill(color)
            .frame(width: size, height: size)
            .overlay(
                Text(glyph).font(.system(size: size * 0.55, weight: .bold)).foregroundStyle(.white)
            )
            .opacity(confidence > 0.8 ? 1 : 0.6)
    }
}

struct ResolverBadgeRow: View {
    let sources: [ResolvedSource]
    var size: CGFloat = 18

    // Fallback when the user has no explicit order yet (matches
    // ResolverScoring.CANONICAL_RESOLVER_ORDER).
    private static let canonical = ["spotify", "applemusic", "bandcamp", "soundcloud", "localfiles", "youtube"]

    private var visible: [ResolvedSource] {
        // Sort by the user's CONFIGURED resolver priority order (Android parity:
        // ResolverIconRow reads LocalResolverOrder), falling back to canonical.
        // Reading ResolverPrefs.shared here makes the row re-sort reactively when
        // the user reorders/enables resolvers in Settings.
        let userOrder = ResolverPrefs.shared.order
        let order = userOrder.isEmpty ? Self.canonical : userOrder
        return sources
            .filter { !$0.noMatch && ($0.confidence?.doubleValue ?? 0) >= 0.60 }
            .sorted { a, b in
                let pa = order.firstIndex(of: a.resolver) ?? order.count
                let pb = order.firstIndex(of: b.resolver) ?? order.count
                if pa != pb { return pa < pb }
                return (a.confidence?.doubleValue ?? 0) > (b.confidence?.doubleValue ?? 0)
            }
    }

    @State private var shown = false

    var body: some View {
        HStack(spacing: 3) {
            ForEach(Array(visible.enumerated()), id: \.offset) { _, source in
                ResolverIconSquare(resolver: source.resolver, size: size,
                                   confidence: source.confidence?.doubleValue ?? 1)
            }
        }
        // Fade the badges IN when resolution lands instead of hard-popping in.
        .opacity(shown ? 1 : 0)
        .onAppear { withAnimation(.easeInOut(duration: 0.3)) { shown = true } }
    }
}

// MARK: - ResolverPrefs (global, live mirror of resolver order + active set)
//
// iOS equivalent of Android's `LocalResolverOrder` CompositionLocal + the
// active-resolver set. Kept live from the shared SettingsStore flows so
// ResolverBadgeRow re-sorts reactively (#4) and resolver enable/disable can
// drive additive re-resolution (#1). Lives here (not its own file) to avoid
// Xcode project-file surgery.
@Observable
final class ResolverPrefs {
    static let shared = ResolverPrefs()

    /// User-configured resolver priority order (leftmost = highest priority).
    var order: [String] = []
    /// Enabled ("active") resolver ids.
    var active: Set<String> = []
    /// Suppress additive re-resolution on the FIRST active-flow emit (initial
    /// load isn't a user "enable").
    @ObservationIgnored private var activeInitialized = false

    private let container = IosContainer.companion.shared
    private let watcher = FlowWatcher(scope: IosContainer.companion.shared.appScope)
    private var subs: [Any] = []

    private init() {}

    /// Idempotent — begin watching the store flows. Call once at app start.
    func start() {
        guard subs.isEmpty else { return }
        subs.append(watcher.watch(flow: container.settingsStore.getResolverOrderFlow()) { [weak self] v in
            if let list = v as? [String] { self?.order = list }
        })
        subs.append(watcher.watch(flow: container.settingsStore.getActiveResolversFlow()) { [weak self] v in
            guard let self, let list = v as? [String] else { return }
            let newSet = Set(list)
            // Additive re-resolution (#1): when a resolver is newly enabled,
            // resolve just it for already-cached tracks and merge the result.
            // Skip the first emit (initial load isn't a user enable).
            if self.activeInitialized {
                for r in newSet.subtracting(self.active) {
                    Task { @MainActor in IosTrackResolverCache.shared.resolverEnabled(r) }
                }
            }
            self.active = newSet
            self.activeInitialized = true
        })
    }
}

// MARK: - ArtistImageCache (#187: header + related-artist images, cached)
//
// iOS has no DB, so artist images would re-fetch every session. This in-memory
// + disk cache maps artist name → image URL, populated via the shared
// Apple-Music-first getArtistImage. Observed by views so images fill in as they
// land. Used by the Artist hero header and the Related Artists grid.
@MainActor
@Observable
final class ArtistImageCache {
    static let shared = ArtistImageCache()

    private let container = IosContainer.companion.shared
    /// lowercased artist name → image URL.
    private(set) var images: [String: String] = [:]
    private var inFlight: Set<String> = []

    private let persistURL: URL = {
        let dir = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
        return dir.appendingPathComponent("artist-images.json")
    }()
    private var saveScheduled = false

    init() { loadFromDisk() }

    func image(for name: String) -> String? { images[name.lowercased()] }

    /// Fetch + cache an artist's image (no-op if cached or in flight).
    func fetch(_ name: String) {
        let key = name.lowercased()
        guard !key.isEmpty, images[key] == nil, !inFlight.contains(key) else { return }
        inFlight.insert(key)
        Task { @MainActor in
            let url = try? await container.getArtistImage(artistName: name)
            if let u = url, !u.isEmpty { images[key] = u; scheduleSave() }
            inFlight.remove(key)
        }
    }

    private func loadFromDisk() {
        guard let data = try? Data(contentsOf: persistURL),
              let map = try? JSONDecoder().decode([String: String].self, from: data) else { return }
        images = map
    }

    private func scheduleSave() {
        guard !saveScheduled else { return }
        saveScheduled = true
        Task { @MainActor in
            try? await Task.sleep(nanoseconds: 2_000_000_000)
            saveScheduled = false
            if let data = try? JSONEncoder().encode(images) {
                try? data.write(to: persistURL, options: .atomic)
            }
        }
    }
}
