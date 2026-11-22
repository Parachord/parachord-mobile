import SwiftUI
import Shared

// MARK: - Recommendations cards
//
// Stateless cards for the Recommendations page. They draw what the shared page
// (IosRecItem / IosRecListener) says and compute no rules. Shapes mirror
// Android's RecommendationCards.kt. Long-press menus go on at the call site.

/// A card's default width in a scrolling row.
let pcRecCardWidth: CGFloat = 140

/// The play button's tap target sits this far in from the cover's corner, so its 40 pt disc is 8 pt in.
private let recArtPlayInset: CGFloat = 4

struct RecCardText: View {
    let text: String
    var primary = false
    var tertiary = false
    var lines = 1
    var center = false

    var body: some View {
        Text(text)
            .font(.system(size: primary ? 13 : 12, weight: primary ? .semibold : .regular))
            .foregroundStyle(primary ? PC.fg1 : (tertiary ? PC.fg3 : PC.fg2))
            .lineLimit(lines)
            .truncationMode(.tail)
            .multilineTextAlignment(center ? .center : .leading)
            .frame(maxWidth: .infinity, alignment: center ? .center : .leading)
    }
}

/// A square cover: the image, else the generative placeholder.
private struct RecCover: View {
    let url: String?
    let name: String
    let size: CGFloat

    var body: some View {
        Group {
            if let url, let u = URL(string: url) {
                PCCachedImage(url: u) { img in img.resizable().aspectRatio(contentMode: .fill) }
                    placeholder: { PCArtwork(name: name, size: size, radius: 6) }
            } else {
                PCArtwork(name: name, size: size, radius: 6)
            }
        }
        .frame(width: size, height: size)
        .clipShape(RoundedRectangle(cornerRadius: 6, style: .continuous))
    }
}

/// A release: cover, title, artist, year, and the "Coming …" pill at the top right.
struct RecAlbumCard: View {
    let item: IosRecItem
    var width: CGFloat = pcRecCardWidth
    let onOpen: () -> Void
    let onPlay: () -> Void
    /// Home's reason badge, over the cover's bottom-left.
    var coverBadge: AnyView? = nil

    var body: some View {
        PCAlbumCard(
            title: item.name, artist: item.artist ?? "", artworkUrl: item.imageUrl, width: width,
            subtitle: item.year, pill: CardPill.companion.coming(label: item.comingLabel),
            artBadge: coverBadge, onOpen: onOpen, onPlay: onPlay)
    }
}

/// A recommended artist: a square 6-radius photo, else the user icon (Android's `AppIcon.User`).
struct RecArtistCard: View {
    let item: IosRecItem
    var width: CGFloat = pcRecCardWidth
    let onOpen: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Group {
                if let url = item.imageUrl, let u = URL(string: url) {
                    PCCachedImage(url: u) { img in img.resizable().aspectRatio(contentMode: .fill) }
                        placeholder: { placeholder }
                } else {
                    placeholder
                }
            }
            .frame(width: width, height: width)
            .background(PC.bgInset)
            .clipShape(RoundedRectangle(cornerRadius: 6, style: .continuous))
            Spacer().frame(height: 8)
            RecCardText(text: item.name, primary: true)
        }
        .frame(width: width)
        .contentShape(Rectangle())
        .onTapGesture(perform: onOpen)
    }

    private var placeholder: some View {
        PCIcon(icon: .user, size: 40, tint: PC.fg3)
    }
}

/// A service playlist: image, name, and the service it came from.
struct RecPlaylistCard: View {
    let item: IosRecItem
    var width: CGFloat = pcRecCardWidth
    let onOpen: () -> Void

    var body: some View {
        PCPlaylistCard(title: item.name, lines: [item.sourceName], width: width, onOpen: onOpen) { side in
            RecCover(url: item.imageUrl, name: item.name, size: side)
        }
    }
}

/// A weekly playlist: a 2×2 mosaic of `covers` (one fills it), label and week.
struct RecWeeklyCard: View {
    let item: IosRecItem
    let covers: [String]
    var width: CGFloat = pcRecCardWidth
    let onOpen: () -> Void
    let onPlay: () -> Void

    var body: some View {
        PCPlaylistCard(title: item.weeklyLabel ?? item.name, lines: [item.weeklyWeekLabel ?? ""],
                       width: width, onOpen: onOpen, onPlay: onPlay) { side in
            PCCoverMosaic(covers: covers).frame(width: side, height: side).background(PC.bgInset)
        }
    }
}

/// A 2x2 mosaic of `covers` filling its frame (one cover fills it; 2 or 3 repeat in order), else the
/// music icon. Weekly cards and Home's mix and playlist rows draw it, as Android's `CoverMosaic`.
struct PCCoverMosaic: View {
    let covers: [String]

    var body: some View {
        if covers.isEmpty {
            PCIcon(icon: .music, size: 40, tint: PC.fg3)
        } else if covers.count == 1 {
            tile(covers[0])
        } else {
            // 2 or 3 covers fill the grid by repeating in order.
            VStack(spacing: 0) {
                ForEach(0..<2, id: \.self) { row in
                    HStack(spacing: 0) {
                        ForEach(0..<2, id: \.self) { col in
                            tile(covers[(row * 2 + col) % covers.count])
                        }
                    }
                }
            }
        }
    }

    private func tile(_ url: String) -> some View {
        Group {
            if let u = URL(string: url) {
                PCCachedImage(url: u) { img in img.resizable().aspectRatio(contentMode: .fill) }
                    placeholder: { Color.clear }
            } else {
                Color.clear
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .clipped()
    }
}

/// A listener with a similar taste: hexagon avatar, play disc, similarity pill, name,
/// service and top artists.
struct ListenerCard: View {
    let listener: IosRecListener
    var width: CGFloat = pcRecCardWidth
    let onOpen: () -> Void
    let onPlay: () -> Void

    private var avatar: CGFloat { width * (120.0 / 140.0) }

    var body: some View {
        VStack(spacing: 0) {
            ZStack {
                Group {
                    if let url = listener.avatarUrl, let u = URL(string: url) {
                        PCCachedImage(url: u) { img in img.resizable().aspectRatio(contentMode: .fill) }
                            placeholder: { initial }
                    } else {
                        initial
                    }
                }
                .frame(width: avatar, height: avatar)
                .background(ResolverColor.solid(listener.service))
                .clipShape(PCHexagon())
                if let badge = listener.badge {
                    Text(badge)
                        .font(.system(size: 11, weight: .semibold))
                        .foregroundStyle(PC.onAccent)
                        .lineLimit(1)
                        .padding(.horizontal, 8).padding(.vertical, 2)
                        .background(Capsule().fill(ResolverColor.solid(listener.service)))
                        .frame(width: avatar, height: avatar, alignment: .bottom)
                }
                // Desktop's friend card plays the person's top tracks.
                PCArtPlayButton(title: listener.name, onPlay: onPlay)
                    .offset(x: 8, y: -8)
                    .frame(width: avatar, height: avatar, alignment: .topTrailing)
            }
            .frame(width: avatar, height: avatar)
            Spacer().frame(height: 8)
            RecCardText(text: listener.name, primary: true, center: true)
            RecCardText(text: listener.serviceName, center: true)
            // Fixed two-line height so every card in the row lines up.
            RecCardText(text: listener.topArtists, tertiary: true, lines: 2, center: true)
                .frame(height: 32, alignment: .top)
        }
        .frame(width: width)
        .contentShape(Rectangle())
        .onTapGesture(perform: onOpen)
    }

    private var initial: some View {
        Text(String(listener.name.prefix(1)).uppercased())
            .font(.system(size: 48, weight: .bold))
            .foregroundStyle(PC.onAccent)
    }
}

/// Placeholder while a row loads: `lines` text lines under the cover (album 3, playlist 2, artist 1).
struct RecCardSkeleton: View {
    var lines: Int = 3
    var width: CGFloat = pcRecCardWidth

    var body: some View { PCCardSkeleton(width: width, lines: max(lines - 1, 0)) }
}

/// Placeholder for a listener while the Listeners row loads.
struct ListenerCardSkeleton: View {
    var width: CGFloat = pcRecCardWidth
    private var avatar: CGFloat { width * (120.0 / 140.0) }

    var body: some View {
        VStack(spacing: 0) {
            PCSkeletonBox(width: avatar, height: avatar, radius: 0)
                .clipShape(PCHexagon())
            Spacer().frame(height: 8)
            VStack(spacing: 4) {
                PCSkeletonBox(width: width * 0.6, height: 12, radius: 4)
                PCSkeletonBox(width: width * 0.4, height: 10, radius: 4)
            }
        }
        .frame(width: width)
        .accessibilityHidden(true)
    }
}

// MARK: - Previews

#if DEBUG
private func sampleItem(_ type: String, name: String, coming: String? = nil) -> IosRecItem {
    IosRecItem(id: name, type: type, name: name, artist: "Some Artist", imageUrl: nil, url: nil,
               year: "2026", comingLabel: coming, sourceName: "Spotify",
               weeklyId: "w1", weeklyContextType: "weekly-jams", weeklyLabel: "Weekly Jams",
               weeklyWeekLabel: "Week of Nov 16", opensView: true, trackId: nil)
}

private let sampleListener = IosRecListener(
    id: "sam", name: "sam", service: "listenbrainz", serviceName: "ListenBrainz",
    avatarUrl: nil, badge: "88%", topArtists: "Radiohead, Portishead, Massive Attack",
    showsAchordion: false, achordionUrl: nil, friendId: "sam")

private struct RecCardsPreview: View {
    var body: some View {
        ScrollView(.horizontal) {
            HStack(alignment: .top, spacing: 12) {
                RecAlbumCard(item: sampleItem("album", name: "A Long Album Title Here", coming: "Coming Dec 5"),
                             onOpen: {}, onPlay: {})
                RecArtistCard(item: sampleItem("artist", name: "Some Artist"), onOpen: {})
                RecPlaylistCard(item: sampleItem("playlist", name: "Chill Mix"), onOpen: {})
                RecWeeklyCard(item: sampleItem("playlist", name: "Weekly Jams"), covers: [], onOpen: {}, onPlay: {})
                ListenerCard(listener: sampleListener, onOpen: {}, onPlay: {})
                RecCardSkeleton(lines: 3)
                ListenerCardSkeleton()
            }
            .padding(16)
        }
        .background(PC.bgPrimary)
    }
}

#Preview("Cards light") { RecCardsPreview().preferredColorScheme(.light) }
#Preview("Cards dark") { RecCardsPreview().preferredColorScheme(.dark) }
#endif

// MARK: - The shared cards (CardSpec)
//
// One album card, one playlist card and their skeleton (desktop album-card.js /
// playlist-card.js / home-skeletons.js), drawn from the shared `CardSpec`, as
// Android's `ParachordAlbumCard` / `ParachordPlaylistCard` / `CardSkeleton`.
// `width` is the cover's; the card adds its padding around it (`CardSpec.cardWidth`), so a row
// keeps its 140 covers.

private var cardSpec: CardSpec { CardSpec.shared }

/// The art's side in a card given `width`: the width is the cover's.
func pcCardArtSize(_ width: CGFloat) -> CGFloat { width }

/// The card box (desktop's `HOME_CARD_BOX`): the card colour, a small shadow, 10 corners, 10 inside.
struct PCCardBox<Content: View>: View {
    let width: CGFloat?
    @ViewBuilder let content: () -> Content

    var body: some View {
        VStack(alignment: .leading, spacing: 0) { content() }
            .frame(width: width, alignment: .leading)
            .padding(CGFloat(cardSpec.PADDING))
            .frame(maxWidth: width == nil ? .infinity : nil, alignment: .leading)
            .background(
                RoundedRectangle(cornerRadius: CGFloat(cardSpec.RADIUS), style: .continuous)
                    .fill(PC.cardBg)
                    .shadow(color: .black.opacity(0.08), radius: 2, y: 1)
            )
            .contentShape(RoundedRectangle(cornerRadius: CGFloat(cardSpec.RADIUS), style: .continuous))
    }
}

/// A card's title and lines: 13 / 500 primary, 12 secondary, later lines tertiary.
struct PCCardLines: View {
    let title: String
    let lines: [String]
    var tertiaryFrom = 1
    var firstLineLimit = 1

    var body: some View {
        Spacer().frame(height: CGFloat(cardSpec.TEXT_GAP))
        Text(title)
            .font(.system(size: CGFloat(cardSpec.TITLE_SIZE), weight: .medium))
            .foregroundStyle(PC.fg1)
            .lineLimit(1)
            .frame(maxWidth: .infinity, alignment: .leading)
        ForEach(Array(lines.enumerated()), id: \.offset) { i, line in
            Spacer().frame(height: CGFloat(cardSpec.LINE_GAP))
            Text(line.isEmpty ? "\u{00A0}" : line)
                .font(.system(size: CGFloat(cardSpec.LINE_SIZE)))
                .foregroundStyle(i >= tertiaryFrom ? PC.fg3 : PC.fg2)
                .lineLimit(i == 0 ? firstLineLimit : 1)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
    }
}

/// A pill at the art's top right (release type, "Coming …"): white text on its colour.
struct PCCardPillBadge: View {
    let pill: CardPill

    var body: some View {
        Text(pill.text)
            .font(.system(size: CGFloat(cardSpec.PILL_SIZE), weight: .semibold))
            .foregroundStyle(PC.onAccent)
            .lineLimit(1)
            .padding(.horizontal, 6).padding(.vertical, 2)
            .background(RoundedRectangle(cornerRadius: CGFloat(DesignTokens.shared.RADIUS_BADGE))
                .fill(pcToken(pill.color).opacity(Double(pill.alpha))))
            .padding(CGFloat(cardSpec.BADGE_INSET))
    }
}

/// History's and a friend's rank at the art's top left (desktop `AlbumRankBadge`).
struct PCCardRankBadge: View {
    let rank: Int

    var body: some View {
        Text(cardSpec.rankLabel(rank: Int32(rank)))
            .font(.system(size: CGFloat(cardSpec.LINE_SIZE), weight: .medium))
            .foregroundStyle(.white)
            .padding(.horizontal, 6).padding(.vertical, 2)
            .background(RoundedRectangle(cornerRadius: CGFloat(DesignTokens.shared.RADIUS_BADGE)).fill(.black.opacity(0.6)))
            .padding(CGFloat(cardSpec.BADGE_INSET))
    }
}

/// The album card: the cover with its pill, rank, badge and play disc, then the title, artist and subtitle.
/// `width` nil fills a grid cell.
struct PCAlbumCard: View {
    let title: String
    let artist: String?
    let artworkUrl: String?
    var width: CGFloat? = pcRecCardWidth
    var subtitle: String? = nil
    var pill: CardPill? = nil
    var rank: Int? = nil
    var artBadge: AnyView? = nil
    /// nil when the card sits in a link or button that takes the tap itself.
    var onOpen: (() -> Void)? = nil
    var onPlay: (() -> Void)? = nil

    var body: some View {
        PCCardBox(width: width) {
            cover
            PCCardLines(title: title, lines: [artist, subtitle].compactMap { $0 })
        }
        .onTapGesture { onOpen?() }
        .accessibilityElement(children: .contain)
    }

    @ViewBuilder private var cover: some View {
        let radius = CGFloat(cardSpec.ART_RADIUS)
        let side = width.map(pcCardArtSize)
        ZStack {
            pcCover(artworkUrl, seed: title, size: side, radius: radius, artist: artist, album: title)
            if let pill { PCCardPillBadge(pill: pill).frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topTrailing) }
            if let rank { PCCardRankBadge(rank: rank).frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading) }
            if let artBadge { artBadge }
            if let onPlay {
                PCArtPlayButton(title: title, onPlay: onPlay)
                    .padding(recArtPlayInset)
                    .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottomTrailing)
            }
        }
        .frame(width: side, height: side)
        .aspectRatio(1, contentMode: .fit)
        .clipShape(RoundedRectangle(cornerRadius: radius, style: .continuous))
    }
}

/// The playlist card: the art `art` draws, the "Δ N" badge top left, `cornerBadge` top right, the
/// play disc, then the title and lines (the first may wrap to two).
struct PCPlaylistCard<Art: View>: View {
    let title: String
    let lines: [String]
    var width: CGFloat = pcRecCardWidth
    var badge: String? = nil
    var cornerBadge: AnyView? = nil
    let onOpen: () -> Void
    var onPlay: (() -> Void)? = nil
    @ViewBuilder let art: (CGFloat) -> Art

    var body: some View {
        let side = pcCardArtSize(width)
        PCCardBox(width: width) {
            ZStack {
                art(side)
                if let badge {
                    Text(badge)
                        .font(.system(size: CGFloat(cardSpec.PILL_SIZE), weight: .bold)).foregroundStyle(PC.fg1)
                        .lineLimit(1)
                        .padding(.horizontal, 8).padding(.vertical, 4)
                        .background(Capsule().fill(PC.bgPrimary))
                        .padding(CGFloat(cardSpec.BADGE_INSET))
                        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
                }
                if let cornerBadge { cornerBadge.frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topTrailing) }
                if let onPlay {
                    PCArtPlayButton(title: title, onPlay: onPlay)
                        .padding(recArtPlayInset)
                        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottomTrailing)
                }
            }
            .frame(width: side, height: side)
            .clipShape(RoundedRectangle(cornerRadius: CGFloat(cardSpec.ART_RADIUS), style: .continuous))
            PCCardLines(title: title, lines: lines, tertiaryFrom: .max, firstLineLimit: 2)
        }
        .onTapGesture(perform: onOpen)
        .accessibilityElement(children: .contain)
    }
}

/// A card's shimmer stand-in: the same box, the art and `lines` text bars under the title bar.
struct PCCardSkeleton: View {
    var width: CGFloat = pcRecCardWidth
    var lines: Int = 1

    var body: some View {
        let side = pcCardArtSize(width)
        PCCardBox(width: width) {
            PCSkeletonBox(width: side, height: side, radius: CGFloat(cardSpec.ART_RADIUS))
            Spacer().frame(height: CGFloat(cardSpec.TEXT_GAP))
            PCSkeletonBox(width: side * 0.8, height: 13, radius: 4)
            ForEach(0..<max(lines, 0), id: \.self) { _ in
                Spacer().frame(height: CGFloat(cardSpec.LINE_GAP) + 2)
                PCSkeletonBox(width: side * 0.55, height: 11, radius: 4)
            }
        }
        .accessibilityHidden(true)
    }
}
