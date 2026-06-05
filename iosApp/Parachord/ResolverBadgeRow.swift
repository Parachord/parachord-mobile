import SwiftUI
import Shared

// MARK: - Resolver badge row (playback-loop phase)
//
// Confidence-aware resolver badges per CLAUDE.md "Resolver Badge Display":
//   1. filter noMatch / confidence < 0.60 from display
//   2. dim icons with confidence <= 0.80 to 0.6 alpha
//   3. sort by resolver priority (canonical order), then confidence desc —
//      the leftmost badge is the source that will actually play
//
// iOS has no resolver icon assets yet, so each badge is a small branded
// capsule (SF Symbol + per-resolver color). Reads ranked sources from
// IosTrackResolverCache, so badges appear as background resolution lands.

struct ResolverBadgeRow: View {
    let sources: [ResolvedSource]

    // Canonical priority order (matches ResolverScoring.CANONICAL_RESOLVER_ORDER).
    private static let order = ["spotify", "applemusic", "bandcamp", "soundcloud", "localfiles", "youtube"]

    private var visible: [ResolvedSource] {
        sources
            .filter { !$0.noMatch && ($0.confidence?.doubleValue ?? 0) >= 0.60 }
            .sorted { a, b in
                let pa = Self.order.firstIndex(of: a.resolver) ?? Self.order.count
                let pb = Self.order.firstIndex(of: b.resolver) ?? Self.order.count
                if pa != pb { return pa < pb }
                return (a.confidence?.doubleValue ?? 0) > (b.confidence?.doubleValue ?? 0)
            }
    }

    var body: some View {
        HStack(spacing: 4) {
            ForEach(Array(visible.enumerated()), id: \.offset) { _, source in
                badge(for: source)
            }
        }
    }

    @ViewBuilder
    private func badge(for source: ResolvedSource) -> some View {
        let conf = source.confidence?.doubleValue ?? 0
        HStack(spacing: 3) {
            Image(systemName: icon(source.resolver))
                .font(.system(size: 9, weight: .semibold))
            Text(label(source.resolver))
                .font(.system(size: 10, weight: .medium))
        }
        .foregroundStyle(color(source.resolver))
        .padding(.horizontal, 6)
        .padding(.vertical, 2)
        .background(color(source.resolver).opacity(0.15), in: Capsule())
        // Dim low-confidence (<=0.80) matches, full opacity for strong matches.
        .opacity(conf <= 0.80 ? 0.6 : 1.0)
    }

    private func label(_ resolver: String) -> String {
        switch resolver {
        case "applemusic": return "Apple"
        case "soundcloud": return "SoundCloud"
        case "localfiles": return "Local"
        default: return resolver.prefix(1).uppercased() + resolver.dropFirst()
        }
    }

    private func icon(_ resolver: String) -> String {
        switch resolver {
        case "localfiles": return "internaldrive"
        case "youtube": return "play.rectangle.fill"
        default: return "music.note"
        }
    }

    private func color(_ resolver: String) -> Color {
        switch resolver {
        case "spotify": return Color(red: 0.11, green: 0.73, blue: 0.33)   // green
        case "applemusic": return Color(red: 0.98, green: 0.18, blue: 0.33) // pink/red
        case "soundcloud": return Color(red: 1.0, green: 0.47, blue: 0.0)   // orange
        case "bandcamp": return Color(red: 0.0, green: 0.6, blue: 0.71)     // cyan
        case "youtube": return Color(red: 0.94, green: 0.13, blue: 0.13)    // red
        default: return Color(red: 0.49, green: 0.23, blue: 0.93)           // brand purple
        }
    }
}
