import SwiftUI
import Shared

// MARK: - Theme mode (#234 — Settings › General light/dark/system override)
//
// The `PC` palette below is already dark-aware via `dyn(light, dark)`, but `dyn`
// keys off `UITraitCollection.userInterfaceStyle` — the SYSTEM appearance — so
// the in-app Theme picker (system/light/dark, persisted as `themeMode`) was
// stored but never applied. ThemeObserver watches that setting and resolves a
// SwiftUI `ColorScheme?` the root applies via `.preferredColorScheme`; forcing
// the scheme there ALSO flips `dyn` (SwiftUI overrides the trait), so the whole
// palette follows the user's choice. Mirrors Android's `ParachordTheme.isDark`
// (which likewise overrides, not `isSystemInDarkTheme()`).
@MainActor
@Observable
final class ThemeObserver {
    private let container = IosContainer.companion.shared
    /// nil = follow system; .light / .dark = explicit override.
    var scheme: ColorScheme?
    private var sub: Cancellable?

    /// Idempotent; appScope-backed watcher lives with the (root-persistent) view.
    func start() {
        guard sub == nil else { return }
        sub = FlowWatcher(scope: container.appScope).watch(flow: container.settingsStore.themeMode) { [weak self] v in
            switch v as? String {
            case "light": self?.scheme = .light
            case "dark": self?.scheme = .dark
            default: self?.scheme = nil   // "system" / unknown → follow device
            }
        }
    }
}

// MARK: - Parachord design system
//
// The token VALUES live once, in the shared `DesignTokens`
// (shared/commonMain/.../ui/DesignTokens.kt), which Android's Color.kt reads
// too; docs/DESIGN_SYSTEM.md lists them. This file only turns them into
// SwiftUI colours: change a value there, not here. Light + dark variants adapt
// automatically via dynamic UIColors; player surfaces are always dark.

private let tokens = DesignTokens.shared

/// A themed token: light/dark resolved from the trait collection.
private func dyn(_ c: DesignTokens.ThemeColor) -> Color {
    Color(uiColor: UIColor { tc in
        tc.userInterfaceStyle == .dark
            ? UIColor(hex: UInt32(c.dark)).withAlphaComponent(CGFloat(c.darkAlpha))
            : UIColor(hex: UInt32(c.light))
    })
}

/// A constant `0xRRGGBB` token (Kotlin `Int`).
func pcToken(_ rgb: Int32) -> Color { Color(uiColor: UIColor(hex: UInt32(rgb))) }

extension UIColor {
    convenience init(hex: UInt32) {
        self.init(
            red: CGFloat((hex >> 16) & 0xFF) / 255,
            green: CGFloat((hex >> 8) & 0xFF) / 255,
            blue: CGFloat(hex & 0xFF) / 255,
            alpha: 1
        )
    }
}

/// Color tokens. Use `PC.accent`, `PC.fg1`, etc. Player-surface tokens are
/// constant (never adapt) — the mini-player + Now Playing are always dark.
enum PC {
    // Accent (purple). `accent` is for text, icons, outlines and selected
    // text; it's #a78bfa in dark, where white on it fails contrast. Anything
    // FILLED that carries white text uses `fillAccent`, in both themes.
    static let accent        = dyn(tokens.accent)
    static let accentHover   = dyn(tokens.accentHover)
    static let accentSoft    = pcToken(tokens.ACCENT_SOFT)
    static let accentSurface = dyn(tokens.accentSurface)
    static let fillAccent    = pcToken(tokens.FILL_ACCENT)

    // Backgrounds
    static let bgPrimary   = dyn(tokens.bgPrimary)
    static let bgSecondary = dyn(tokens.bgSecondary)
    static let bgElevated  = dyn(tokens.bgElevated)
    static let bgInset     = dyn(tokens.bgInset)

    // Text (fg3 is for placeholders only)
    static let fg1 = dyn(tokens.textPrimary)
    static let fg2 = dyn(tokens.textSecondary)
    static let fg3 = dyn(tokens.textTertiary)

    // Borders / cards
    static let border      = dyn(tokens.borderDefault)
    static let borderLight = dyn(tokens.borderLight)
    static let cardBg      = dyn(tokens.cardBg)
    static let cardBorder  = dyn(tokens.cardBorder)

    // Semantic
    static let success = pcToken(tokens.SUCCESS)
    static let warning = pcToken(tokens.WARNING)
    static let error   = pcToken(tokens.ERROR)
    static let onTour  = pcToken(tokens.ON_TOUR)

    // Hosted (XSPF) chip — brand blue, constant
    static let hostedBg = pcToken(tokens.HOSTED_BG)
    static let hostedFg = pcToken(tokens.HOSTED_FG)

    // Always-dark player surfaces (mini-player + Now Playing, regardless of theme)
    enum Player {
        static let bg     = pcToken(tokens.PLAYER_BG)
        static let bgElev = pcToken(tokens.PLAYER_BG_ELEVATED)
        static let fg1    = pcToken(tokens.PLAYER_TEXT_PRIMARY)
        static let fg2    = pcToken(tokens.PLAYER_TEXT_SECONDARY)
    }

    /// A page's colour (drawer entry, "See all") and hero gradient.
    static func page(_ p: DesignTokens.Page) -> Color { pcToken(p.color) }
    static func pageGradient(_ p: DesignTokens.Page) -> [UInt32] {
        p.gradient.map { UInt32($0.int32Value) }
    }

    /// One colour per release type (Album, EP, Single, Live, Compilation).
    static func releaseType(_ type: String?) -> Color {
        pcToken(tokens.releaseTypeColor(type: type))
    }
}

/// A service's colour: its plugin manifest's `color` (shared `ServiceColors`,
/// the same answer Android gets), never a hand-copied hex.
enum ResolverColor {
    static func hex(_ serviceId: String) -> UInt32 {
        UInt32(ServiceColors.shared.color(serviceId: serviceId))
    }
    static func solid(_ serviceId: String) -> Color { pcToken(ServiceColors.shared.color(serviceId: serviceId)) }
}

/// Spacing scale (4px base).
enum PCSpace {
    static let s1: CGFloat = 4
    static let s2: CGFloat = 8
    static let s3: CGFloat = 12
    static let s4: CGFloat = 16   // default screen padding
    static let s5: CGFloat = 20
    static let s6: CGFloat = 24
    static let s8: CGFloat = 32
    static let s10: CGFloat = 40
    static let s12: CGFloat = 48
}

/// Corner radii.
enum PCRadius {
    static let xs: CGFloat = 4    // badges, chips, small thumbs
    static let sm: CGFloat = 6    // inputs
    static let md: CGFloat = 8    // default card
    static let lg: CGFloat = 12   // album art, large cards
    static let xl: CGFloat = 16   // sheets, modals
    static let pill: CGFloat = 999
}

// MARK: - Typography helpers

extension View {
    /// Purple uppercase wide-tracked section header (Home / Library / Sidebar).
    func pcSectionHeader() -> some View {
        self.font(.system(size: 14, weight: .semibold))
            .tracking(1.7)                    // ~0.12em at 14px
            .textCase(.uppercase)
            .foregroundStyle(PC.accent)
    }

    /// "PARACHORD" wide-tracked uppercase brand title.
    func pcAppTitle() -> some View {
        self.font(.system(size: 18, weight: .regular))
            .tracking(6.3)                    // ~0.35em at 18px
            .textCase(.uppercase)
            .foregroundStyle(PC.fg1)
    }
}

// MARK: - Error and empty states (Android's ErrorState / EmptyState)

/// A load that failed with nothing to show: the message in the error color and
/// an outlined retry button, as Android's `ErrorState` (Recommendations,
/// Fresh Drops, Critical Darlings, History, a friend's profile). The message
/// is the repository's own, so both apps say the same thing.
struct PCErrorState: View {
    let message: String
    var retryLabel: String = UiMessages.shared.TRY_AGAIN
    var onRetry: (() -> Void)? = nil

    var body: some View {
        VStack(spacing: 12) {
            Text(message)
                .font(.system(size: 14)).foregroundStyle(PC.error)
                .multilineTextAlignment(.center).padding(.horizontal, 32)
            if let onRetry {
                Button(action: onRetry) {
                    Text(retryLabel).font(.system(size: 14, weight: .medium)).foregroundStyle(PC.accent)
                        .padding(.horizontal, 20).frame(height: 38)
                        .overlay(Capsule().strokeBorder(PC.border))
                }
                .buttonStyle(.plain)
            }
        }
        .frame(maxWidth: .infinity).padding(.vertical, 60)
    }
}

/// Android's `EmptyState`: a list that loaded with nothing in it.
struct PCEmptyState: View {
    let message: String
    var hint: String? = nil
    var icon: String? = nil

    var body: some View {
        VStack(spacing: 8) {
            if let icon { Image(systemName: icon).font(.system(size: 38)).foregroundStyle(PC.fg3) }
            Text(message)
                .font(.system(size: 14)).foregroundStyle(PC.fg2)
                .multilineTextAlignment(.center).padding(.horizontal, 32)
            if let hint {
                Text(hint).font(.system(size: 13)).foregroundStyle(PC.fg3)
                    .multilineTextAlignment(.center).padding(.horizontal, 32)
            }
        }
        .frame(maxWidth: .infinity).padding(.vertical, 60)
    }
}

/// The message of a load that failed with nothing to show (`IosLoadException`,
/// carrying the repository's text), or nil for any other error.
func pcLoadErrorMessage(_ error: Error) -> String? {
    guard (error as NSError).userInfo["KotlinException"] is IosLoadException else { return nil }
    return (error as NSError).userInfo["KotlinException"].flatMap { ($0 as? KotlinThrowable)?.message }
}
