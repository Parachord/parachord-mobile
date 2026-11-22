import SwiftUI
import Shared

// MARK: - Home's hero, header, banner slot and skeleton
//
// Android's HomeHero / HomeHeader / HomeSkeleton (HomeScreen.kt, HomeHeader.kt),
// drawn from the shared page. Words come from `HomeCopy` and the page's DTOs.

let homeGutter = CGFloat(DesignTokens.shared.SCREEN_GUTTER)

/// The collapsed page hero (desktop `home-page.js`): Home's gradient with its
/// ring pattern at 10%, "HOME" uppercase, light, 0.2em tracking, white. The
/// menu button sits on it in place of the top bar.
struct HomeHero: View {
    let onMenu: () -> Void

    private var gradient: LinearGradient {
        LinearGradient(colors: PC.pageGradient(.home).map { Color(uiColor: UIColor(hex: $0)) },
                       startPoint: .topLeading, endPoint: .bottomTrailing)
    }

    var body: some View {
        HStack(spacing: 4) {
            Button(action: onMenu) {
                Image(systemName: "line.3.horizontal")
                    .font(.system(size: 18, weight: .regular)).foregroundStyle(PC.onAccent)
                    .frame(width: 44, height: 44)
            }
            .buttonStyle(.plain)
            .accessibilityLabel(HomeCopy.shared.MENU)
            Text(DesignTokens.Page.home.title.uppercased())
                .font(.system(size: 24, weight: .light))
                .tracking(24 * 0.2)
                .foregroundStyle(PC.onAccent)
                .lineLimit(1)
                .accessibilityAddTraits(.isHeader)
            Spacer(minLength: 0)
        }
        .padding(.leading, 4).padding(.trailing, homeGutter)
        .frame(height: 64)
        .frame(maxWidth: .infinity)
        .background {
            ZStack {
                gradient
                HomeHeroPattern()
            }
            .ignoresSafeArea(edges: .top)
        }
    }
}

/// Desktop's hero pattern: a 100 pt tile of three rings (r 40 / 25 / 10), white at 10%.
private struct HomeHeroPattern: View {
    var body: some View {
        Canvas { ctx, size in
            let tile: CGFloat = 100
            let rings: [(CGFloat, CGFloat)] = [(40, 2), (25, 1.5), (10, 1)]
            let color = PC.onAccent.opacity(0.1)
            var x: CGFloat = 0
            while x < size.width {
                var y: CGFloat = 0
                while y < size.height + tile / 2 {
                    let c = CGPoint(x: x + tile / 2, y: y + tile / 2)
                    for (r, w) in rings {
                        let rect = CGRect(x: c.x - r, y: c.y - r, width: r * 2, height: r * 2)
                        ctx.stroke(Path(ellipseIn: rect), with: .color(color), lineWidth: w)
                    }
                    y += tile
                }
                x += tile
            }
        }
        .accessibilityHidden(true)
    }
}

/// The greeting (28 / 700) over the freshest playlist's change line, then the
/// "Finish setting up" pill and "Play Collection Radio" (desktop `home-page.js`;
/// a phone wraps them under the greeting).
struct HomeHeaderView: View {
    let page: IosHomePage
    let onCollectionRadio: () -> Void
    let onSetupAction: (String) -> Void
    let onDismissSetupItem: (String) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text(page.greeting)
                .font(.system(size: 28, weight: .bold)).foregroundStyle(PC.fg1)
                .accessibilityAddTraits(.isHeader)
            if let line = page.changeLine {
                Text(line)
                    .font(.system(size: 14)).foregroundStyle(PC.fg2)
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.top, 4)
            }
            if page.setup != nil || page.hasCollection {
                ViewThatFits(in: .horizontal) {
                    HStack(spacing: 12) { buttons }
                    VStack(alignment: .leading, spacing: 12) { buttons }
                }
                .padding(.top, 16)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, homeGutter)
    }

    @ViewBuilder private var buttons: some View {
        if let setup = page.setup {
            HomeSetupPill(setup: setup, onAction: onSetupAction, onDismiss: onDismissSetupItem)
        }
        if page.hasCollection {
            HomeCollectionRadioButton(onTap: onCollectionRadio)
        }
    }
}

/// Desktop's `CollectionRadioButton`: the accent fill, a round Play mark and the label.
struct HomeCollectionRadioButton: View {
    let onTap: () -> Void

    var body: some View {
        Button(action: onTap) {
            // Padding 6 16 6 6, desktop's: the mark sits 6 from the pill's edge.
            HStack(spacing: 8) {
                ZStack {
                    Circle().fill(PC.onAccent).frame(width: 28, height: 28)
                    PCIcon(icon: .play, size: 12, tint: PC.fillAccent)
                }
                Text(HomeCopy.shared.PLAY_COLLECTION_RADIO)
                    .font(.system(size: 14, weight: .semibold)).foregroundStyle(PC.onAccent)
                    .lineLimit(1)
            }
            .padding(.leading, 6).padding(.trailing, 16).padding(.vertical, 6)
            .frame(minHeight: 40)
            .background(PC.fillAccent, in: Capsule())
            .fixedSize()
        }
        .buttonStyle(.plain)
    }
}

/// "Finish setting up · N of M", which opens the checklist as a sheet (desktop's popover).
private struct HomeSetupPill: View {
    let setup: IosHomeSetup
    let onAction: (String) -> Void
    let onDismiss: (String) -> Void
    @State private var open = false

    var body: some View {
        Button { open = true } label: {
            Text(setup.pill)
                .font(.system(size: 14, weight: .semibold)).foregroundStyle(PC.fg1)
                .lineLimit(1)
                .padding(.horizontal, 16).padding(.vertical, 10)
                .frame(minHeight: 40)
                .background(PC.cardBg, in: Capsule())
                .overlay(Capsule().strokeBorder(PC.border, lineWidth: 1))
                .fixedSize()
        }
        .buttonStyle(.plain)
        .sheet(isPresented: $open) {
            HomeSetupSheet(
                setup: setup,
                onAction: { id in open = false; onAction(id) },
                onDismiss: onDismiss
            )
            .presentationDetents([.height(CGFloat(120 + 56 * setup.items.count)), .medium])
            .presentationBackground(PC.bgElevated)
        }
    }
}

private struct HomeSetupSheet: View {
    let setup: IosHomeSetup
    let onAction: (String) -> Void
    let onDismiss: (String) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text(HomeCopy.shared.SETUP_DIALOG)
                .font(.system(size: 20, weight: .bold)).foregroundStyle(PC.fg1)
                .accessibilityAddTraits(.isHeader)
                .padding(.bottom, 12)
            ForEach(setup.items, id: \.id) { item in
                HStack(spacing: 12) {
                    // Desktop's "○": an open ring. Every listed item is still to do.
                    Circle().strokeBorder(PC.fg2, lineWidth: 1.5)
                        .frame(width: 12, height: 12)
                        .frame(width: 20, height: 20)
                    Text(item.label)
                        .font(.system(size: 14)).foregroundStyle(PC.fg1)
                        .frame(maxWidth: .infinity, alignment: .leading)
                    Button { onAction(item.id) } label: {
                        Text(HomeCopy.shared.SET_UP)
                            .font(.system(size: 13)).foregroundStyle(PC.fg1)
                            .padding(.horizontal, 12).padding(.vertical, 4)
                            .frame(minHeight: 32)
                            .overlay(RoundedRectangle(cornerRadius: CGFloat(DesignTokens.shared.RADIUS_BUTTON))
                                .strokeBorder(PC.border, lineWidth: 1))
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(HomeCopy.shared.setUpLabel(item: item.label))
                    Button { onDismiss(item.id) } label: {
                        PCIcon(icon: .x, size: 16, tint: PC.fg2)
                            .frame(width: 44, height: 44)
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(item.dismissLabel)
                }
                .frame(minHeight: 56)
            }
            Spacer(minLength: 0)
        }
        .padding(.horizontal, homeGutter)
        .padding(.top, 24)
    }
}

/// The one announcement Home shows; its view is reported once per id.
struct HomeBannerSlot: View {
    let banner: IosHomeBanner
    let session: IosHomeSession

    private var color: Color {
        switch banner.role {
        case .error:   return PC.error
        case .warning: return PC.warning
        case .success: return PC.success
        default:       return PC.accent
        }
    }

    var body: some View {
        HStack(alignment: .top, spacing: 8) {
            VStack(alignment: .leading, spacing: 4) {
                Text(banner.title).font(.system(size: 16, weight: .semibold)).foregroundStyle(PC.fg1)
                    .fixedSize(horizontal: false, vertical: true)
                if let text = banner.text, !text.isEmpty {
                    Text(text).font(.system(size: 13)).foregroundStyle(PC.fg2)
                        .lineLimit(3)
                        .fixedSize(horizontal: false, vertical: true)
                }
                if let label = banner.ctaLabel, let url = banner.ctaUrl {
                    Button {
                        session.bannerCtaClicked(id: banner.id)
                        openHttp(url)
                    } label: {
                        Text(label).font(.system(size: 14, weight: .semibold)).foregroundStyle(PC.fg1)
                    }
                    .buttonStyle(.plain)
                    .padding(.top, 4)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            Button { session.dismissBanner(id: banner.id) } label: {
                PCIcon(icon: .x, size: 16, tint: PC.fg2).frame(width: 32, height: 32)
            }
            .buttonStyle(.plain)
            .accessibilityLabel(HomeCopy.shared.dismissLabel(item: banner.title))
        }
        .padding(12)
        .background(color.opacity(0.12), in: RoundedRectangle(cornerRadius: CGFloat(DesignTokens.shared.RADIUS_PANEL)))
        .padding(.horizontal, homeGutter).padding(.vertical, 8)
        .onAppear { session.bannerShown(id: banner.id) }
    }

    /// A banner's CTA: http(s) only, as desktop's `home-banner.js`.
    private func openHttp(_ raw: String) {
        let u = raw.trimmingCharacters(in: .whitespaces)
        let lower = u.lowercased()
        guard lower.hasPrefix("https://") || lower.hasPrefix("http://"), let url = URL(string: u) else { return }
        UIApplication.shared.open(url)
    }
}

/// While the getting-started decision is pending: header bars, a 2x2 of tiles, a row of cards.
struct HomeSkeleton: View {
    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            PCSkeletonBox(width: 200, height: 28, radius: CGFloat(DesignTokens.shared.RADIUS_BADGE))
            PCSkeletonBox(width: 160, height: 14, radius: CGFloat(DesignTokens.shared.RADIUS_BADGE))
            Spacer().frame(height: 12)
            PCSkeletonBox(width: 140, height: 20, radius: CGFloat(DesignTokens.shared.RADIUS_BADGE))
            ForEach(0..<2, id: \.self) { _ in
                HStack(spacing: 12) {
                    ForEach(0..<2, id: \.self) { _ in
                        PCSkeletonBox(height: 72, radius: CGFloat(DesignTokens.shared.RADIUS_CARD))
                    }
                }
            }
            Spacer().frame(height: 12)
            PCSkeletonBox(width: 180, height: 20, radius: CGFloat(DesignTokens.shared.RADIUS_BADGE))
            HStack(spacing: 16) {
                ForEach(0..<3, id: \.self) { _ in
                    VStack(alignment: .leading, spacing: 8) {
                        PCSkeletonBox(width: 140, height: 140, radius: CGFloat(DesignTokens.shared.RADIUS_CARD))
                        PCSkeletonBox(width: 110, height: 12, radius: CGFloat(DesignTokens.shared.RADIUS_BADGE))
                        PCSkeletonBox(width: 80, height: 10, radius: CGFloat(DesignTokens.shared.RADIUS_BADGE))
                    }
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .clipped()
        }
        .padding(.horizontal, homeGutter).padding(.vertical, 24)
    }
}
