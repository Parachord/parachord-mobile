import SwiftUI
import Shared

// MARK: - Home's getting-started layout (desktop's home-getting-started.js, Android's HomeGettingStarted.kt)
//
// Shown in place of the sections until setup is done or skipped: the welcome,
// one card with the three steps (the current one open, any one can be opened),
// then "Explore while you set up". Every word and rule comes from the shared
// DTO (`IosGettingStarted`) and `HomeCopy`; this only draws it. Left out, as on
// Android: desktop's "Sound quality" and its note about the phone apps.

private let gsCopy = HomeCopy.shared
private let gsCardRadius = CGFloat(DesignTokens.shared.RADIUS_SHEET)
private let gsTileRadius = CGFloat(DesignTokens.shared.RADIUS_PANEL)
private let gsMarkRadius = CGFloat(DesignTokens.shared.RADIUS_BUTTON)

struct HomeGettingStartedView: View {
    let gs: IosGettingStarted
    let onSkip: () -> Void
    let onOpenStep: (String) -> Void
    /// Settings, on a plug-in's sheet when the id is set, else the first page.
    let onOpenPlugin: (String?) -> Void
    let onOpenPluginsTab: () -> Void
    let onSyncLibrary: () -> Void
    let onImport: () -> Void
    let onOpenPlaylist: (String) -> Void
    let onCollectionRadio: () -> Void
    let onSearch: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 20) {
            VStack(alignment: .leading, spacing: 4) {
                Text(gsCopy.WELCOME).font(.system(size: 28, weight: .bold)).foregroundStyle(PC.fg1)
                    .accessibilityAddTraits(.isHeader)
                Text(gsCopy.WELCOME_INTRO).font(.system(size: 14)).foregroundStyle(PC.fg2)
                Button(action: onSkip) {
                    Text(gsCopy.SKIP_SETUP).font(.system(size: 13, weight: .semibold)).foregroundStyle(PC.accent)
                        .frame(minHeight: 44, alignment: .leading)
                }
                .buttonStyle(.plain)
            }
            VStack(spacing: 0) {
                ForEach(Array(gs.steps.enumerated()), id: \.element.id) { i, step in
                    if i > 0 { Divider().overlay(PC.borderLight) }
                    let isOpen = gs.openStep == step.id
                    stepHeader(i, step, open: isOpen)
                    if isOpen {
                        VStack(alignment: .leading, spacing: 20) {
                            switch step.id {
                            case GettingStartedLayoutKt.GETTING_STARTED_CONNECT: connectStep
                            case GettingStartedLayoutKt.GETTING_STARTED_MUSIC: musicStep
                            default: listenStep
                            }
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(.horizontal, 16).padding(.top, 4).padding(.bottom, 20)
                    }
                }
            }
            .background(PC.cardBg)
            .clipShape(RoundedRectangle(cornerRadius: gsCardRadius, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: gsCardRadius, style: .continuous).strokeBorder(PC.border))
        }
        .padding(.horizontal, homeGutter)
    }

    // MARK: Step headers

    private func stepHeader(_ index: Int, _ step: IosGsStep, open: Bool) -> some View {
        Button { onOpenStep(step.id) } label: {
            HStack(spacing: 12) {
                marker(index, step.marker)
                VStack(alignment: .leading, spacing: 0) {
                    Text(step.title).font(.system(size: 14, weight: .semibold)).foregroundStyle(PC.fg1)
                    Text(step.stateLine).font(.system(size: 12)).foregroundStyle(PC.fg3)
                }
                Spacer(minLength: 0)
            }
            .padding(.horizontal, 16).padding(.vertical, 8)
            .frame(maxWidth: .infinity, minHeight: 64, alignment: .leading)
            .background(open ? PC.accentSurface : PC.cardBg)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .combine)
        .accessibilityValue(open ? gsCopy.EXPANDED : gsCopy.COLLAPSED)
    }

    /// A check when done, the number in the accent when current, else on grey.
    private func marker(_ index: Int, _ look: String) -> some View {
        let bg: Color = look == "done" ? PC.fillSuccess : look == "current" ? PC.fillAccent : PC.bgSecondary
        let fg: Color = look == "todo" ? PC.fg2 : PC.onAccent
        return ZStack {
            Circle().fill(bg)
            if look == "done" {
                PCIcon(icon: .check, size: 16, tint: fg)
            } else {
                Text("\(index + 1)").font(.system(size: 13, weight: .semibold)).foregroundStyle(fg)
            }
        }
        .frame(width: 28, height: 28)
        .accessibilityHidden(true)
    }

    // MARK: Steps

    @ViewBuilder private var connectStep: some View {
        stepLine(gsCopy.CONNECT_LINE)
        group(gsCopy.GROUP_SERVICES) {
            ForEach(gs.services, id: \.pluginId) { t in
                tile(mark: AnyView(serviceMark(t.pluginId)), title: t.name, text: t.text, badge: t.connected,
                     label: t.label) { onOpenPlugin(t.pluginId) }
            }
            tile(mark: AnyView(iconMark(.plus, PC.fillNeutral)), title: gsCopy.MORE_PLUGINS, text: gsCopy.BROWSE,
                 badge: false, label: nil, action: onOpenPluginsTab)
        }
        if gs.folder != nil || !gs.free.isEmpty {
            group(gsCopy.GROUP_NO_ACCOUNT) {
                if let f = gs.folder {
                    tile(mark: AnyView(serviceMark(f.pluginId)), title: f.name, text: f.text, badge: false,
                         label: f.label) { onOpenPlugin(f.pluginId) }
                }
                ForEach(gs.free, id: \.pluginId) { t in
                    tile(mark: AnyView(serviceMark(t.pluginId)), title: t.name, text: t.text, badge: false,
                         label: t.label) { onOpenPlugin(t.pluginId) }
                }
            }
        }
        // Below every source: the order decides which one plays.
        HStack(alignment: .top, spacing: 12) {
            PCIcon(icon: .listordered, size: 18, tint: PC.fg3).padding(.top, 2)
            VStack(alignment: .leading, spacing: 4) {
                Text(gsCopy.ORDER_NOTE).font(.system(size: 13)).foregroundStyle(PC.fg2)
                Button(action: onOpenPluginsTab) {
                    Text(gsCopy.SET_THE_ORDER).font(.system(size: 13, weight: .semibold)).foregroundStyle(PC.accent)
                        .frame(minHeight: 44, alignment: .leading)
                }
                .buttonStyle(.plain)
            }
            Spacer(minLength: 0)
        }
        .padding(.horizontal, 16).padding(.vertical, 12)
        .overlay(RoundedRectangle(cornerRadius: gsTileRadius, style: .continuous).strokeBorder(PC.border))
    }

    @ViewBuilder private var musicStep: some View {
        stepLine(gsCopy.MUSIC_LINE)
        group(gsCopy.GROUP_PLAYLISTS) {
            tile(mark: AnyView(iconMark(PageIcon.playlists.icon, PC.pageFill(DesignTokens.Page.playlists))),
                 title: gsCopy.IMPORT_A_PLAYLIST, text: gsCopy.IMPORT_A_PLAYLIST_SUB, badge: false, label: nil,
                 action: onImport)
            tile(mark: AnyView(iconMark(PageIcon.collection.icon, PC.pageFill(DesignTokens.Page.collection))),
                 title: gsCopy.SYNC_YOUR_LIBRARY, text: gsCopy.SYNC_YOUR_LIBRARY_SUB, badge: false, label: nil,
                 action: onSyncLibrary)
        }
        if !gs.scrobblers.isEmpty {
            group(gsCopy.GROUP_LISTENING_HISTORY) {
                ForEach(gs.scrobblers, id: \.pluginId) { t in
                    tile(mark: AnyView(serviceMark(t.pluginId)), title: t.name, text: t.text, badge: t.connected,
                         label: t.label) { onOpenPlugin(t.pluginId) }
                }
            }
        }
    }

    @ViewBuilder private var listenStep: some View {
        stepLine(gs.listenLine)
        if !gs.playlists.isEmpty {
            VStack(spacing: 12) {
                ForEach(gs.playlists, id: \.pluginId) { p in
                    tile(mark: AnyView(iconMark(PageIcon.playlists.icon, PC.pageFill(DesignTokens.Page.playlists))),
                         title: p.name, text: p.text, badge: false, label: p.label) { onOpenPlaylist(p.pluginId) }
                }
            }
        }
        HStack(spacing: 12) {
            if gs.hasMusic { HomeCollectionRadioButton(onTap: onCollectionRadio) }
            Button(action: onSearch) {
                HStack(spacing: 8) {
                    PCIcon(icon: .search, size: 16, tint: PC.fg1)
                    Text(gsCopy.SEARCH).font(.system(size: 14, weight: .medium)).foregroundStyle(PC.fg1)
                }
                .padding(.horizontal, 16).padding(.vertical, 10)
                .frame(minHeight: 40)
                .background(PC.cardBg, in: RoundedRectangle(cornerRadius: gsMarkRadius, style: .continuous))
                .overlay(RoundedRectangle(cornerRadius: gsMarkRadius, style: .continuous).strokeBorder(PC.borderStrong))
            }
            .buttonStyle(.plain)
            Spacer(minLength: 0)
        }
    }

    // MARK: Pieces

    private func stepLine(_ text: String) -> some View {
        Text(text).font(.system(size: 14)).foregroundStyle(PC.fg2)
    }

    /// A group of tiles under its label (12, semibold, uppercase, tertiary).
    private func group<C: View>(_ label: String, @ViewBuilder _ content: () -> C) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(label.uppercased()).font(.system(size: 12, weight: .semibold)).tracking(0.6).foregroundStyle(PC.fg3)
                .accessibilityAddTraits(.isHeader)
            VStack(spacing: 12) { content() }
        }
    }

    /// Desktop's `OptionTile`: a bordered choice with its mark, title and description; [badge] draws the
    /// description as the accent "Connected" badge.
    private func tile(mark: AnyView, title: String, text: String, badge: Bool, label: String?,
                      action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 12) {
                mark
                VStack(alignment: .leading, spacing: 2) {
                    Text(title).font(.system(size: 14, weight: .semibold)).foregroundStyle(PC.fg1).lineLimit(1)
                    if badge { connectedBadge(text) }
                    else { Text(text).font(.system(size: 12)).foregroundStyle(PC.fg2).lineLimit(2) }
                }
                Spacer(minLength: 0)
            }
            .padding(.horizontal, 16).padding(.vertical, 12)
            .frame(maxWidth: .infinity, minHeight: 68, alignment: .leading)
            .overlay(RoundedRectangle(cornerRadius: gsTileRadius, style: .continuous).strokeBorder(PC.border))
            .contentShape(RoundedRectangle(cornerRadius: gsTileRadius, style: .continuous))
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(label ?? "\(title), \(text)")
        .accessibilityAddTraits(.isButton)
    }

    private func connectedBadge(_ text: String) -> some View {
        HStack(spacing: 4) {
            PCIcon(icon: .check, size: 12, tint: PC.accent)
            Text(text).font(.system(size: 12, weight: .semibold)).foregroundStyle(PC.accent)
        }
        .padding(.horizontal, 8).padding(.vertical, 2)
        .background(PC.accentSurface, in: Capsule())
    }

    /// A service's logo on its own colour (desktop `ServiceMark`); a wide one (Libre.fm's wordmark) at 30.
    private func serviceMark(_ id: String) -> some View {
        let spec = ServiceLogos.shared.badgeOrLarge(serviceId: id.lowercased())
        let wide = spec.map { $0.height > 0 && $0.width / $0.height > 1.5 } ?? false
        let bitmap = id.lowercased() == "soundcloud" || id.lowercased() == "subsonic"
        return ZStack {
            ResolverColor.solid(id)
            if bitmap {
                PCServiceLogo(serviceId: id).padding(7)
            } else if spec != nil {
                PCServiceLogo(serviceId: id).frame(width: wide ? 30 : 22, height: wide ? 30 : 22)
            } else {
                PCIcon(icon: .music, size: 18, tint: PC.onAccent)
            }
        }
        .frame(width: 36, height: 36)
        .clipShape(RoundedRectangle(cornerRadius: gsMarkRadius, style: .continuous))
        .accessibilityHidden(true)
    }

    private func iconMark(_ icon: AppIcon, _ fill: Color) -> some View {
        ZStack { PCIcon(icon: icon, size: 18, tint: PC.onAccent) }
            .frame(width: 36, height: 36)
            .background(fill, in: RoundedRectangle(cornerRadius: gsMarkRadius, style: .continuous))
            .accessibilityHidden(true)
    }
}
