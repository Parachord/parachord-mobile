import SwiftUI
import Shared

// MARK: - Home's card rows (desktop `home-sections.js`, Android's HomeRows.kt)
//
// "Made from your listening", "Your playlists, freshest first" and "Albums you
// might love". Which row shows, which cards, every line of text and every
// colour comes from the shared page (`IosHomePage`) or `HomeCopy`; Swift only
// draws. Rows are horizontal scrollers of `pcRecCardWidth` cards.

private let rowGap: CGFloat = 16
private let badgeDisc: CGFloat = 28
private let badgeLogo: CGFloat = 20

// MARK: Shared pieces

/// A row's source chips ("All" first), shown when the page says so.
private struct HomeSourceChips: View {
    let chips: [IosHomeChip]
    let selected: String
    let onSelect: (String) -> Void

    var body: some View {
        PCFilterChips(
            chips: chips.map {
                PCChip(id: $0.id, label: $0.label,
                       color: $0.id == HomeStateKt.HOME_ALL_SOURCES ? nil : UInt32(bitPattern: $0.colorRgb))
            },
            selection: selected,
            onSelect: onSelect,
            pageFill: UInt32(DesignTokens.Page.home.fill),
            horizontalPadding: homeGutter
        )
        .padding(.bottom, 12)
    }
}

/// A Home card's art: the card's cover, else its mosaic, as Android's `CoverMosaic(imageUrl ?: mosaic)`,
/// clipped to the card art's radius.
struct HomeCoverArt: View {
    let imageUrl: String?
    let mosaic: [String]
    let size: CGFloat

    var body: some View {
        ZStack { PCCoverMosaic(covers: imageUrl.map { [$0] } ?? mosaic) }
            .frame(width: size, height: size)
            .background(PC.bgInset)
            .clipShape(RoundedRectangle(cornerRadius: CGFloat(DesignTokens.shared.RADIUS_ART), style: .continuous))
    }
}

/// A line under a card's title, clamped to `maxLines`.
private struct HomeCardLine {
    let text: String
    var maxLines: Int = 1
}

/// A Home card: art (with an optional badge top-left), the play disc bottom-right, the title and lines
/// (blank lines keep their height so the row lines up).
private struct HomeRowCard<Art: View>: View {
    let title: String
    let lines: [HomeCardLine]
    var badge: String? = nil
    let onOpen: () -> Void
    let onPlay: () -> Void
    @ViewBuilder let art: () -> Art

    private let width = pcRecCardWidth

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            ZStack {
                art()
                if let badge {
                    Text(badge)
                        .font(.system(size: 11, weight: .bold)).foregroundStyle(PC.fg1)
                        .lineLimit(1)
                        .padding(.horizontal, 8).padding(.vertical, 4)
                        .background(Capsule().fill(PC.bgPrimary))
                        .padding(8)
                        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
                }
                PCArtPlayButton(title: title, onPlay: onPlay)
                    .padding(4)
                    .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottomTrailing)
            }
            .frame(width: width, height: width)
            Spacer().frame(height: 8)
            RecCardText(text: title, primary: true)
            ForEach(Array(lines.enumerated()), id: \.offset) { _, line in
                RecCardText(text: line.text.isEmpty ? "\u{00A0}" : line.text, lines: line.maxLines)
            }
        }
        .frame(width: width)
        .contentShape(Rectangle())
        .onTapGesture(perform: onOpen)
        .accessibilityElement(children: .contain)
        .accessibilityLabel(HomeCopy.shared.openLabel(title: title))
        .accessibilityAction(.default, onOpen)
        .accessibilityAction(named: HomeCopy.shared.playLabel(title: title), onPlay)
    }
}

// MARK: Made from your listening

struct HomeMixesRow: View {
    let page: IosHomePage
    let onSelectSource: (String) -> Void
    let onOpen: (IosHomeMixCard) -> Void
    let onPlay: (IosHomeMixCard) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            PCSectionHeader(title: HomeCopy.shared.MIXES_TITLE, subtitle: HomeCopy.shared.MIXES_SUBTITLE, gutter: homeGutter)
            if page.showMixChips {
                HomeSourceChips(chips: page.mixChips, selected: page.mixSource, onSelect: onSelectSource)
            }
            ScrollView(.horizontal, showsIndicators: false) {
                LazyHStack(alignment: .top, spacing: rowGap) {
                    ForEach(page.mixCards, id: \.key) { card in
                        HomeRowCard(
                            title: card.title,
                            lines: [HomeCardLine(text: card.subtitle, maxLines: 2)],
                            onOpen: { onOpen(card) },
                            onPlay: { onPlay(card) }
                        ) {
                            HomeCoverArt(imageUrl: card.imageUrl, mosaic: card.mosaic, size: pcRecCardWidth)
                        }
                    }
                }
                .padding(.horizontal, homeGutter)
            }
        }
    }
}

// MARK: Your playlists, freshest first

struct HomePlaylistsRow: View {
    let page: IosHomePage
    let onOpen: (IosHomePlaylistCard) -> Void
    let onPlay: (IosHomePlaylistCard) -> Void
    let onSeeAll: () -> Void

    @State private var renameTarget: Playlist?
    @State private var renameText = ""
    @State private var deleteTarget: Playlist?
    @State private var syncTarget: Playlist?
    @State private var shareFlow = PlaylistShareFlow()
    private let container = IosContainer.companion.shared

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            PCSectionHeader(
                title: HomeCopy.shared.PLAYLISTS_TITLE, subtitle: HomeCopy.shared.PLAYLISTS_SUBTITLE,
                page: DesignTokens.Page.playlists, onSeeAll: onSeeAll, gutter: homeGutter
            )
            ScrollView(.horizontal, showsIndicators: false) {
                LazyHStack(alignment: .top, spacing: rowGap) {
                    ForEach(page.playlists, id: \.id) { card in
                        HomeRowCard(
                            title: card.name,
                            lines: [HomeCardLine(text: card.line1, maxLines: 2), HomeCardLine(text: card.line2 ?? "")],
                            badge: card.badge,
                            onOpen: { onOpen(card) },
                            onPlay: { onPlay(card) }
                        ) {
                            pcCover(card.imageUrl, seed: card.name, size: pcRecCardWidth, radius: CGFloat(DesignTokens.shared.RADIUS_ART))
                        }
                        .contextMenu { menu(card) }
                    }
                }
                .padding(.horizontal, homeGutter)
            }
        }
        // The Playlists tab's menu and sheets, reused.
        .playlistShareFlow(shareFlow)
        .alert("Rename Playlist", isPresented: Binding(get: { renameTarget != nil }, set: { if !$0 { renameTarget = nil } })) {
            TextField("Name", text: $renameText)
            Button("Cancel", role: .cancel) { renameTarget = nil }
            Button("Save") {
                let n = renameText.trimmingCharacters(in: .whitespaces)
                if let p = renameTarget, !n.isEmpty { Task { try? await container.renamePlaylist(id: p.id, name: n) } }
                renameTarget = nil
            }
        }
        .sheet(isPresented: Binding(get: { deleteTarget != nil }, set: { if !$0 { deleteTarget = nil } })) {
            if let p = deleteTarget {
                DeletePlaylistSheet(playlist: p) { deleteTarget = nil }
                    .presentationDetents([.medium])
            }
        }
        .sheet(isPresented: Binding(get: { syncTarget != nil }, set: { if !$0 { syncTarget = nil } })) {
            if let p = syncTarget {
                PlaylistSyncChannelsSheet(playlist: p) { syncTarget = nil }
                    .presentationDetents([.medium])
            }
        }
    }

    @ViewBuilder private func menu(_ card: IosHomePlaylistCard) -> some View {
        Button { onPlay(card) } label: { Label("Play Playlist", systemImage: "play.fill") }
        Button { shareFlow.share(localId: card.id, name: card.name) } label: { Label("Share…", systemImage: "square.and.arrow.up") }
        Button { withPlaylist(card.id) { syncTarget = $0 } } label: { Label("Sync…", systemImage: "arrow.triangle.2.circlepath") }
        Button { withPlaylist(card.id) { renameTarget = $0; renameText = $0.name } } label: { Label("Rename", systemImage: "pencil") }
        Button(role: .destructive) { withPlaylist(card.id) { deleteTarget = $0 } } label: { Label("Delete Playlist", systemImage: "trash") }
    }

    /// The saved playlist behind a card, for the sheets that need the whole row.
    private func withPlaylist(_ id: String, _ then: @escaping @MainActor (Playlist) -> Void) {
        Task { @MainActor in
            if let p = (try? await container.getSavedPlaylistsOnce())?.first(where: { $0.id == id }) { then(p) }
        }
    }
}

// MARK: Albums you might love

struct HomeAlbumsRow: View {
    let page: IosHomePage
    let onSelectSource: (String) -> Void
    let onShown: (IosHomeAlbum) -> Void
    let onOpen: (IosHomeAlbum) -> Void
    let onOpenArtist: (IosHomeAlbum) -> Void
    let onPlay: (IosHomeAlbum) -> Void
    let onSeeAll: () -> Void
    @Environment(QueuePlaybackCoordinator.self) private var coordinator

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            PCSectionHeader(
                title: HomeCopy.shared.ALBUMS_TITLE, page: DesignTokens.Page.recommendations,
                onSeeAll: onSeeAll, gutter: homeGutter
            )
            if page.showAlbumChips {
                HomeSourceChips(chips: page.albumChips, selected: page.albumSource, onSelect: onSelectSource)
            }
            ScrollView(.horizontal, showsIndicators: false) {
                LazyHStack(alignment: .top, spacing: rowGap) {
                    if page.albumsLoading {
                        ForEach(0..<Int(HomePageKt.HOME_ROW_CARDS), id: \.self) { _ in RecCardSkeleton(lines: 2) }
                    } else {
                        ForEach(page.albums, id: \.key) { album in
                            let item = album.item
                            RecAlbumCard(
                                item: item,
                                onOpen: { onOpen(album) },
                                onPlay: { onPlay(album) },
                                coverBadge: album.reasonBadge.map { AnyView(HomeReasonBadge(text: $0)) }
                            )
                            .onAppear { onShown(album) }
                            .pcAlbumContextMenu(
                                title: item.name, artist: item.artist ?? "", artworkUrl: item.imageUrl, coordinator: coordinator,
                                onGoToAlbum: { onOpen(album) },
                                onGoToArtist: (item.artist ?? "").isEmpty ? nil : { onOpenArtist(album) })
                        }
                    }
                }
                .padding(.horizontal, homeGutter)
            }
        }
    }
}

/// Shuffleupagus's disc on its own (desktop `ReasonMark`, Android's `ReasonMark`): the white chip with its
/// logo in dark ink. The badges draw it, and the Ask box's heading, so they read as one thing. `outlined`
/// adds a border so the white disc shows on a light surface (the mark off the art).
struct HomeReasonMark: View {
    var outlined: Bool = false

    var body: some View {
        ZStack {
            Circle()
                .fill(pcToken(DesignTokens.shared.ON_COLOR_CHIP).opacity(Double(DesignTokens.shared.ON_COLOR_CHIP_ALPHA)))
                .overlay { if outlined { Circle().strokeBorder(PC.border, lineWidth: 1) } }
                .frame(width: badgeDisc, height: badgeDisc)
            PCServiceLogo(serviceId: RecommendationPageKt.SHUFFLEUPAGUS_ID, tint: pcToken(DesignTokens.shared.ON_ART_INK))
                .frame(width: badgeLogo, height: badgeLogo)
        }
        .accessibilityHidden(true)
    }
}

/// The reason badge (desktop `reason-badge.js`) on a Shuffleupagus pick: a white chip disc with its
/// logo in dark ink, over the cover's bottom-left; a tap shows why in a popover.
struct HomeReasonBadge: View {
    let text: String
    var label: String = HomeCopy.shared.WHY_SHUFFLEUPAGUS
    @State private var shown = false

    var body: some View {
        Button { shown = true } label: {
            HomeReasonMark()
                .frame(width: 48, height: 48)
            .contentShape(Circle())
        }
        .buttonStyle(.plain)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottomLeading)
        .popover(isPresented: $shown) {
            Text(text)
                .font(.system(size: 13)).foregroundStyle(PC.fg1)
                .padding(16).frame(maxWidth: 260)
                .presentationCompactAdaptation(.popover)
        }
        .accessibilityLabel(label)
        .accessibilityValue(text)
    }
}
