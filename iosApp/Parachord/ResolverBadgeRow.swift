import SwiftUI
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

    var body: some View {
        HStack(spacing: 3) {
            ForEach(Array(visible.enumerated()), id: \.offset) { _, source in
                ResolverIconSquare(resolver: source.resolver, size: size,
                                   confidence: source.confidence?.doubleValue ?? 1)
            }
        }
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
            if let list = v as? [String] { self?.active = Set(list) }
        })
    }
}
