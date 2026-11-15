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
    static let accentSoft    = dyn(tokens.accentSoft)
    static let accentSecondary = dyn(tokens.accentSecondary)
    static let accentSurface = dyn(tokens.accentSurface)
    static let fillAccent    = pcToken(tokens.FILL_ACCENT)
    static let fillAccentHover = pcToken(tokens.FILL_ACCENT_HOVER)
    static let onAccent      = pcToken(tokens.ON_ACCENT)

    // Backgrounds
    static let bgPrimary   = dyn(tokens.bgPrimary)
    static let bgSecondary = dyn(tokens.bgSecondary)
    static let bgElevated  = dyn(tokens.bgElevated)
    static let bgInset     = dyn(tokens.bgInset)

    // Text. fg2 = labels and metadata, fg3 = hints, counts, timestamps; both
    // pass 4.5:1 in both themes.
    static let fg1 = dyn(tokens.textPrimary)
    static let fg2 = dyn(tokens.textSecondary)
    static let fg3 = dyn(tokens.textTertiary)

    // Borders / cards
    static let border      = dyn(tokens.borderDefault)
    static let borderStrong = dyn(tokens.borderStrong)
    static let controlOff  = dyn(tokens.controlOff)
    static let placeholder = dyn(tokens.placeholderBg)
    static let borderLight = dyn(tokens.borderLight)
    static let cardBg      = dyn(tokens.cardBg)
    static let cardBorder  = dyn(tokens.cardBorder)

    // Status: dots, icons and indicators (lighter in dark mode)
    static let success = dyn(tokens.success)
    static let warning = dyn(tokens.warning)
    static let error   = dyn(tokens.error)
    static let info    = dyn(tokens.info)
    // Status text on a page or card (≥ 4.5:1 in both themes)
    static let textSuccess = dyn(tokens.textSuccess)
    static let textWarning = dyn(tokens.textWarning)
    static let textDanger  = dyn(tokens.textDanger)
    static let textInfo    = dyn(tokens.textInfo)
    // Status fills with white text, the same in both themes
    static let fillSuccess = pcToken(tokens.FILL_SUCCESS)
    static let fillWarning = pcToken(tokens.FILL_WARNING)
    static let fillError   = pcToken(tokens.FILL_ERROR)
    static let fillInfo    = pcToken(tokens.FILL_INFO)
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
    static func page(_ p: DesignTokens.Page) -> Color { p == .home ? accent : pcToken(p.color) }
    /// A page's darker shade, for its buttons with white text (≥ 4.5:1).
    static func pageFill(_ p: DesignTokens.Page) -> Color { pcToken(p.fill) }
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

// MARK: - Icons (desktop's icon set and service logos, shared)

/// Draws a shared `IconSpec`: an icon from `AppIcon` (generated from desktop's
/// `icons.js`) or a service logo from `ServiceLogos` (desktop's
/// `brand-logos.js`). Parts without their own colour take `tint`; strokes are
/// round-capped and scale with the icon, as on desktop. Android draws the same
/// specs (`ParachordIcon`, `serviceLogoVector`).
struct PCIconCanvas: View {
    let spec: IconSpec
    var tint: Color

    var body: some View {
        Canvas { ctx, size in
            let w = CGFloat(spec.width), h = CGFloat(spec.height)
            let scale = min(size.width / w, size.height / h)
            let dx = (size.width - w * scale) / 2 - CGFloat(spec.minX) * scale
            let dy = (size.height - h * scale) / 2 - CGFloat(spec.minY) * scale
            func pt(_ x: Float, _ y: Float) -> CGPoint {
                CGPoint(x: CGFloat(x) * scale + dx, y: CGFloat(y) * scale + dy)
            }
            for part in spec.parts {
                var path = Path()
                for c in part.commands {
                    if let m = c as? PathCommand.MoveTo { path.move(to: pt(m.x, m.y)) }
                    else if let l = c as? PathCommand.LineTo { path.addLine(to: pt(l.x, l.y)) }
                    else if let q = c as? PathCommand.CubicTo {
                        path.addCurve(to: pt(q.x, q.y), control1: pt(q.x1, q.y1), control2: pt(q.x2, q.y2))
                    } else { path.closeSubpath() }
                }
                let alpha = Double(part.alpha)
                if part.fill {
                    let color = part.fillColor.map { pcToken($0.int32Value) } ?? tint
                    ctx.fill(path, with: .color(color.opacity(alpha)), style: FillStyle(eoFill: part.evenOdd))
                }
                if part.stroke {
                    let color = part.strokeColor.map { pcToken($0.int32Value) } ?? tint
                    ctx.stroke(path, with: .color(color.opacity(alpha)),
                               style: StrokeStyle(lineWidth: CGFloat(part.strokeWidth) * scale,
                                                  lineCap: .round, lineJoin: .round))
                }
            }
        }
    }
}

/// An icon from desktop's set. Sizes, as desktop: 16 in rows, chips and small
/// buttons; 20 in buttons and dialog titles; 24 by default. Decorative unless
/// it has a `label`; an icon-only button carries the label instead.
struct PCIcon: View {
    let icon: AppIcon
    var size: CGFloat = 24
    var tint: Color = PC.fg1
    var label: String? = nil

    var body: some View {
        PCIconCanvas(spec: icon.spec, tint: tint)
            .frame(width: size, height: size)
            .accessibilityHidden(label == nil)
            .accessibilityLabel(label ?? "")
    }
}

/// A service's logo: desktop's image for a bitmap one (SoundCloud,
/// Subsonic), else its vector logo from the shared `ServiceLogos`. Nil-safe:
/// draws nothing for a service with no logo.
struct PCServiceLogo: View {
    let serviceId: String
    var tint: Color = .white

    var body: some View {
        let id = serviceId.lowercased()
        if id == "soundcloud" {
            Image("ServiceLogoSoundcloud").resizable().scaledToFit()
        } else if id == "subsonic" {
            Image("ServiceLogoSubsonic").resizable().scaledToFit()
        } else if let spec = ServiceLogos.shared.badgeOrLarge(serviceId: id) {
            PCIconCanvas(spec: spec, tint: tint)
        }
    }

    /// Whether a logo exists for the service (else callers show its initial).
    static func has(_ serviceId: String) -> Bool {
        let id = serviceId.lowercased()
        return id == "soundcloud" || id == "subsonic" || ServiceLogos.shared.badgeOrLarge(serviceId: id) != nil

// MARK: - Section header and filter chips (desktop section-header.js / filter-chips.js)

/// The section title over a row or grid, as desktop's `SectionHeader` (#1370;
/// sizes in the shared `SectionHeaderSpec`, Android's `SectionHeader` reads
/// them too): 20 / 700 text-primary in sentence case, an optional grey
/// subtitle, and on the right an optional link. With `page` the link is
/// "See all in <page>" in that page's colour; with `action` it acts in place,
/// in the accent. `sub` makes it a subsection title (15 / 600). Page heroes,
/// dialog titles and Settings' group labels are other elements.
struct PCSectionHeader<Trailing: View>: View {
    let title: String
    var subtitle: String? = nil
    var page: DesignTokens.Page? = nil
    var onSeeAll: (() -> Void)? = nil
    var action: (label: String, run: () -> Void)? = nil
    var sub: Bool = false
    @ViewBuilder var trailing: () -> Trailing

    private var spec: SectionHeaderSpec { SectionHeaderSpec.shared }

    var body: some View {
        HStack(alignment: .lastTextBaseline, spacing: CGFloat(spec.ROW_GAP)) {
            VStack(alignment: .leading, spacing: CGFloat(spec.SUBTITLE_GAP)) {
                Text(title)
                    .font(.system(size: CGFloat(sub ? spec.SUB_TITLE_SIZE : spec.TITLE_SIZE),
                                  weight: sub ? .semibold : .bold))
                    .foregroundStyle(PC.fg1).lineLimit(1)
                    .accessibilityAddTraits(.isHeader)
                if let subtitle {
                    Text(subtitle).font(.system(size: CGFloat(spec.SUBTITLE_SIZE))).foregroundStyle(PC.fg2)
                }
            }
            Spacer(minLength: 0)
            trailing()
            if let page, let onSeeAll {
                link(spec.seeAllLabel(page: page), color: PC.page(page), run: onSeeAll)
            } else if let action {
                link(action.label, color: PC.accent, run: action.run)
            }
        }
        .padding(.horizontal, CGFloat(DesignTokens.shared.SCREEN_GUTTER))
        .padding(.top, 16)
        .padding(.bottom, CGFloat(spec.GAP_BELOW))
    }

    private func link(_ label: String, color: Color, run: @escaping () -> Void) -> some View {
        Button(label, action: run)
            .font(.system(size: CGFloat(spec.LINK_SIZE), weight: .semibold))
            .foregroundStyle(color).lineLimit(1).buttonStyle(.plain)
    }
}

extension PCSectionHeader where Trailing == EmptyView {
    init(title: String, subtitle: String? = nil, page: DesignTokens.Page? = nil,
         onSeeAll: (() -> Void)? = nil, action: (label: String, run: () -> Void)? = nil, sub: Bool = false) {
        self.init(title: title, subtitle: subtitle, page: page, onSeeAll: onSeeAll,
                  action: action, sub: sub, trailing: { EmptyView() })
    }
}

/// One pick-one chip. `color` is its own `0xRRGGBB` colour (a service's
/// `ResolverColor.hex(id)`, a release type's), or nil for a neutral chip.
struct PCChip: Identifiable, Hashable {
    let id: String
    let label: String
    var color: UInt32? = nil
}

/// The app's pick-one filter row, as desktop's `FilterChips` (#1371; look and
/// sizes from the shared `FilterChipSpec`, which Android's `FilterChips` reads
/// too). Idle chips are bg-secondary, a coloured one with its dot; the picked
/// chip fills with its colour (white or near-black text, whichever reads), or
/// a neutral chip with fill-accent / `pageFill` (the page's
/// `DesignTokens.Page.fill`). Scrolls sideways when it doesn't fit.
struct PCFilterChips: View {
    let chips: [PCChip]
    let selection: String?
    let onSelect: (String) -> Void
    var pageFill: UInt32? = nil

    private var spec: FilterChipSpec { FilterChipSpec.shared }

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: CGFloat(spec.GAP)) {
                ForEach(chips) { chip in chipView(chip) }
            }
            .padding(.horizontal, CGFloat(DesignTokens.shared.SCREEN_GUTTER))
        }
    }

    private func chipView(_ chip: PCChip) -> some View {
        let on = chip.id == selection
        let look = spec.look(selected: on,
                             color: chip.color.map { KotlinInt(int: Int32($0)) },
                             pageFill: pageFill.map { KotlinInt(int: Int32($0)) })
        return Button { onSelect(chip.id) } label: {
            HStack(spacing: CGFloat(spec.GAP)) {
                if let dot = look.dot {
                    Circle().fill(pcToken(dot.int32Value))
                        .frame(width: CGFloat(spec.DOT), height: CGFloat(spec.DOT))
                }
                Text(chip.label)
                    .font(.system(size: CGFloat(spec.TEXT_SIZE), weight: .semibold))
                    .foregroundStyle(look.text.map { pcToken($0.int32Value) } ?? PC.fg1)
                    .lineLimit(1)
            }
            .padding(.horizontal, CGFloat(spec.PAD_HORIZONTAL))
            .padding(.vertical, CGFloat(spec.PAD_VERTICAL))
            .frame(minHeight: CGFloat(spec.MIN_HEIGHT))
            .background(look.fill.map { pcToken($0.int32Value) } ?? PC.bgSecondary, in: Capsule())
            .frame(minHeight: 44)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(on ? .isSelected : [])
    }
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
