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

// MARK: - Parachord design system (ported from docs/design/parachord-ios)
//
// Tokens mirror `assets/colors_and_type.css` from the Claude Design handoff,
// which itself mirrors the Android Compose theme + desktop CSS. Purple-accented,
// with ALWAYS-DARK player surfaces and a resolver-color system (each music
// source has paired bg / fg / solid colors). Light + dark variants adapt
// automatically via dynamic UIColors.

private func dyn(_ light: UInt32, _ dark: UInt32) -> Color {
    Color(uiColor: UIColor { tc in
        UIColor(hex: tc.userInterfaceStyle == .dark ? dark : light)
    })
}

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
    // Accent (purple)
    static let accent        = dyn(0x7c3aed, 0xa78bfa)
    static let accentHover   = dyn(0x6d28d9, 0x8b5cf6)
    static let accentSoft     = Color(uiColor: UIColor(hex: 0xa78bfa))
    static let accentSurface = dyn(0xede9fe, 0x1e1e2e)

    // Backgrounds
    static let bgPrimary   = dyn(0xffffff, 0x161616)
    static let bgSecondary = dyn(0xf9fafb, 0x1e1e1e)
    static let bgElevated  = dyn(0xffffff, 0x252525)
    static let bgInset     = dyn(0xf3f4f6, 0x1a1a1a)

    // Text
    static let fg1 = dyn(0x111827, 0xf3f4f6)
    static let fg2 = dyn(0x6b7280, 0x9ca3af)
    static let fg3 = dyn(0x9ca3af, 0x6b7280)

    // Borders / cards
    static let border     = dyn(0xe5e7eb, 0x2e2e2e)
    static let borderLight = dyn(0xf3f4f6, 0x252525)
    static let cardBg     = dyn(0xffffff, 0x1e1e1e)
    static let cardBorder = dyn(0xe5e7eb, 0x2a2a2a)

    // Semantic
    static let success = Color(uiColor: UIColor(hex: 0x10b981))
    static let warning = Color(uiColor: UIColor(hex: 0xf59e0b))
    static let error   = Color(uiColor: UIColor(hex: 0xef4444))
    static let onTour  = Color(uiColor: UIColor(hex: 0x10c9b4))

    // Hosted (XSPF) chip — brand blue, constant
    static let hostedBg = Color(uiColor: UIColor(hex: 0xeff6ff))
    static let hostedFg = Color(uiColor: UIColor(hex: 0x3b82f6))

    // Always-dark player surfaces (mini-player + Now Playing, regardless of theme)
    enum Player {
        static let bg     = Color(uiColor: UIColor(hex: 0x161616))
        static let bgElev = Color(uiColor: UIColor(hex: 0x1e1e1e))
        static let fg1    = Color(uiColor: UIColor(hex: 0xf3f4f6))
        static let fg2    = Color(uiColor: UIColor(hex: 0x9ca3af))
    }
}

/// Resolver source colors — small colored squares with white glyph + pill badges.
enum ResolverColor {
    case spotify, appleMusic, youtube, bandcamp, soundcloud, localfiles

    static func of(_ resolver: String) -> ResolverColor {
        switch resolver.lowercased() {
        case "spotify": return .spotify
        case "applemusic", "apple music": return .appleMusic
        case "youtube": return .youtube
        case "bandcamp": return .bandcamp
        case "soundcloud": return .soundcloud
        default: return .localfiles
        }
    }

    /// Solid brand color (the colored square fill / pill).
    var solid: Color {
        switch self {
        case .spotify:    return Color(uiColor: UIColor(hex: 0x1db954))
        case .appleMusic: return Color(uiColor: UIColor(hex: 0xfa233b))
        case .youtube:    return Color(uiColor: UIColor(hex: 0xff0033))
        case .bandcamp:   return Color(uiColor: UIColor(hex: 0x1da0c3))
        case .soundcloud: return Color(uiColor: UIColor(hex: 0xff5500))
        case .localfiles: return Color(uiColor: UIColor(hex: 0x4b5563))
        }
    }
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
