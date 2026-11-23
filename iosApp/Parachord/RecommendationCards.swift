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
            artBadge: coverBadge ?? item.reasonBadge.map { AnyView(HomeReasonBadge(text: $0)) },
            onOpen: onOpen, onPlay: onPlay)
    }
}

/// A recommended artist: the shared artist card.
struct RecArtistCard: View {
    let item: IosRecItem
    var width: CGFloat = pcRecCardWidth
    let onOpen: () -> Void

    var body: some View {
        PCArtistCard(name: item.name, imageUrl: item.imageUrl, width: width,
                     artBadge: item.reasonBadge.map { AnyView(HomeReasonBadge(text: $0)) }, onOpen: onOpen)
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

    var body: some View {
        PCListenerCard(
            name: listener.name, avatarUrl: listener.avatarUrl, width: width,
            badge: listener.badge.map { badge in
                AnyView(
                    Text(badge)
                        .font(.system(size: 11, weight: .semibold))
                        .foregroundStyle(PC.onAccent)
                        .lineLimit(1)
                        .padding(.horizontal, 7).padding(.vertical, 1)
                        .background(Capsule().fill(ResolverColor.solid(listener.service)))
                        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottom))
            },
            onOpen: onOpen, onPlay: onPlay
        ) {
            VStack(spacing: 2) {
                RecCardText(text: listener.serviceName.isEmpty ? "\u{00A0}" : listener.serviceName, center: true)
                // Two lines always, so every card in the row is the same height.
                RecCardText(text: listener.topArtists, tertiary: true, lines: 2, center: true)
                    .frame(height: 32, alignment: .top)
            }
            .padding(.top, 2)
        }
    }
}

/// Placeholder while a row loads: `lines` text lines under the cover (album 3, playlist 2, artist 1).
struct RecCardSkeleton: View {
    var lines: Int = 3
    var width: CGFloat = pcRecCardWidth

    var body: some View {
        // One line is an artist card (the name alone); more are an album or playlist card.
        if lines <= 1 { PCArtistCardSkeleton(width: width) } else { PCCardSkeleton(width: width, lines: lines - 1) }
    }
}

/// Placeholder for a listener while the Listeners row loads.
struct ListenerCardSkeleton: View {
    var width: CGFloat = pcRecCardWidth

    var body: some View {
        let spec = FriendAvatarSpec.shared
        let avatar = CGFloat(spec.LISTENER_AVATAR)
        VStack(spacing: 0) {
            PCSkeletonBox(width: avatar, height: avatar, radius: 0)
                .clipShape(PCHexagon(listener: true))
            Spacer().frame(height: CGFloat(spec.LISTENER_NAME_GAP))
            VStack(spacing: 6) {
                PCSkeletonBox(width: width * 0.6, height: 12, radius: 4)
                PCSkeletonBox(width: width * 0.4, height: 10, radius: 4)
                PCSkeletonBox(width: width * 0.7, height: 10, radius: 4)
            }
        }
        .padding(.horizontal, CGFloat(spec.LISTENER_PADDING_H)).padding(.vertical, CGFloat(spec.LISTENER_PADDING_V))
        .frame(width: width)
        .background(RoundedRectangle(cornerRadius: CGFloat(CardSpec.shared.RADIUS), style: .continuous).fill(PC.cardBg)
            .shadow(color: .black.opacity(0.08), radius: 2, y: 1))
        .accessibilityHidden(true)
    }
}

// MARK: - Previews

#if DEBUG
private func sampleItem(_ type: String, name: String, coming: String? = nil) -> IosRecItem {
    IosRecItem(id: name, type: type, name: name, artist: "Some Artist", imageUrl: nil, url: nil,
               year: "2026", comingLabel: coming, sourceName: "Spotify",
               weeklyId: "w1", weeklyContextType: "weekly-jams", weeklyLabel: "Weekly Jams",
               weeklyWeekLabel: "Week of Nov 16", opensView: true, trackId: nil, reasonBadge: nil)
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
                // A wrapping first line keeps its full height, so a row of cards lines up.
                .lineLimit(i == 0 ? firstLineLimit : 1, reservesSpace: true)
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

// MARK: - The artist card (desktop artist-card.js)

/// An artist's stand-in art (desktop `lib/artist-pattern.js`, the shared `ArtistPattern`):
/// the name's gradient and its initials at 40%.
struct PCArtistPatternArt: View {
    let name: String
    var initialsSize: CGFloat = 48

    var body: some View {
        let pattern = ArtistPattern.companion.of(artistName: name)
        let stops = pattern.gradient.stops.map {
            Gradient.Stop(color: pcToken(Int32($0.color)), location: CGFloat($0.at))
        }
        GeometryReader { geo in
            let side = max(geo.size.width, 1)
            ZStack {
                if let linear = pattern.gradient as? ArtistPattern.GradientLinear {
                    let p = linear.line(width: 1, height: 1)
                    LinearGradient(stops: stops,
                                   startPoint: UnitPoint(x: CGFloat(p.get(index: 0)), y: CGFloat(p.get(index: 1))),
                                   endPoint: UnitPoint(x: CGFloat(p.get(index: 2)), y: CGFloat(p.get(index: 3))))
                } else if let radial = pattern.gradient as? ArtistPattern.GradientRadial {
                    RadialGradient(stops: stops,
                                   center: UnitPoint(x: CGFloat(radial.centerX), y: CGFloat(radial.centerY)),
                                   startRadius: 0,
                                   endRadius: CGFloat(radial.radius(width: 1, height: 1)) * side)
                }
                Text(pattern.initials)
                    .font(.system(size: initialsSize, weight: .bold))
                    .tracking(initialsSize * 0.05)
                    .foregroundStyle(pcToken(Int32(pattern.textColor)).opacity(Double(ArtistPattern.companion.INITIALS_ALPHA)))
                    .lineLimit(1)
            }
            .frame(width: geo.size.width, height: geo.size.height)
        }
    }
}

/// The artist card (desktop `artist-card.js`), used by every artist grid and row: a rounded card
/// with the card colour and shadow, the square photo full-bleed (the name's pattern when there's
/// none or it won't load), the rank at its top left, then the name (14 / 500) and a subtitle
/// (12 tertiary). `width` nil fills a grid cell; leave `onOpen` nil inside a link or button.
struct PCArtistCard: View {
    let name: String
    let imageUrl: String?
    var width: CGFloat? = pcRecCardWidth
    var subtitle: String? = nil
    var rank: Int? = nil
    /// Drawn over the photo (the reason badge on a Shuffleupagus pick).
    var artBadge: AnyView? = nil
    var onOpen: (() -> Void)? = nil

    var body: some View {
        let radius = CGFloat(cardSpec.ARTIST_RADIUS)
        VStack(alignment: .leading, spacing: 0) {
            Color.clear
                .aspectRatio(1, contentMode: .fit)
                .overlay {
                    PCArtistImage(url: imageUrl.flatMap { URL(string: $0) }, artistName: name) {
                        PCArtistPatternArt(name: name)
                    }
                }
                .background(PC.placeholder)
                .clipped()
                .overlay(alignment: .topLeading) {
                    if let rank { PCCardRankBadge(rank: rank) }
                }
                .overlay(alignment: .bottomLeading) { if let artBadge { artBadge } }
            VStack(alignment: .leading, spacing: CGFloat(cardSpec.LINE_GAP)) {
                Text(name)
                    .font(.system(size: CGFloat(cardSpec.ARTIST_NAME_SIZE), weight: .medium))
                    .foregroundStyle(PC.fg1)
                    .lineLimit(1)
                if let subtitle {
                    Text(subtitle)
                        .font(.system(size: CGFloat(cardSpec.LINE_SIZE)))
                        .foregroundStyle(PC.fg3)
                        .lineLimit(1)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(CGFloat(cardSpec.ARTIST_TEXT_PADDING))
        }
        .frame(width: width)
        .frame(maxWidth: width == nil ? .infinity : nil)
        .background(PC.cardBg)
        .clipShape(RoundedRectangle(cornerRadius: radius, style: .continuous))
        .shadow(color: .black.opacity(0.08), radius: 2, y: 1)
        .contentShape(RoundedRectangle(cornerRadius: radius, style: .continuous))
        .onTapGesture { onOpen?() }
        .accessibilityElement(children: .combine)
    }
}

/// An artist card's stand-in (desktop `ArtistCardSkeleton`): the same box, the photo and `lines` bars.
struct PCArtistCardSkeleton: View {
    var width: CGFloat? = pcRecCardWidth
    var lines: Int = 1

    var body: some View {
        let radius = CGFloat(cardSpec.ARTIST_RADIUS)
        VStack(alignment: .leading, spacing: 0) {
            PCSkeletonBox(radius: 0).aspectRatio(1, contentMode: .fit)
            VStack(alignment: .leading, spacing: CGFloat(cardSpec.LINE_GAP) + 2) {
                PCSkeletonBox(height: 14, radius: 4).frame(maxWidth: .infinity, alignment: .leading)
                    .scaleEffect(x: 0.7, anchor: .leading)
                if lines > 1 {
                    PCSkeletonBox(height: 12, radius: 4).scaleEffect(x: 0.4, anchor: .leading)
                }
            }
            .padding(CGFloat(cardSpec.ARTIST_TEXT_PADDING))
        }
        .frame(width: width)
        .frame(maxWidth: width == nil ? .infinity : nil)
        .background(PC.cardBg)
        .clipShape(RoundedRectangle(cornerRadius: radius, style: .continuous))
        .shadow(color: .black.opacity(0.08), radius: 2, y: 1)
        .accessibilityHidden(true)
    }
}

// MARK: - The friend avatar and listener card (desktop friend-avatar.js / listener-card.js)

/// A friend's hexagon avatar: their photo, or their initial in white on the avatar gradient. A photo
/// that won't load falls back to the initial. Sizes come from the shared `FriendAvatarSpec.Size`.
struct PCFriendAvatar: View {
    let name: String?
    let avatarUrl: String?
    var side: CGFloat
    var fontSize: CGFloat

    init(name: String?, avatarUrl: String?, size: FriendAvatarSpec.Size = .small) {
        self.name = name; self.avatarUrl = avatarUrl
        self.side = CGFloat(size.box); self.fontSize = CGFloat(size.font)
    }

    init(name: String?, avatarUrl: String?, side: CGFloat, fontSize: CGFloat) {
        self.name = name; self.avatarUrl = avatarUrl; self.side = side; self.fontSize = fontSize
    }

    var body: some View {
        let spec = FriendAvatarSpec.shared
        Color.clear
            .frame(width: side, height: side)
            .overlay {
                PCArtistImage(url: avatarUrl.flatMap { URL(string: $0) }) {
                    LinearGradient(colors: [pcToken(Int32(spec.GRADIENT_FROM)), pcToken(Int32(spec.GRADIENT_TO))],
                                   startPoint: .topLeading, endPoint: .bottomTrailing)
                        .overlay {
                            Text(spec.initial(name: name))
                                .font(.system(size: fontSize, weight: .medium))
                                .foregroundStyle(PC.onAccent)
                        }
                }
            }
            .clipShape(PCHexagon())
            .accessibilityHidden(true)
    }
}

/// The card for a person whose listening you follow (desktop `listener-card.js`): the card box, a
/// 100 pt regular hexagon in a ring that's green while they're on air (with the on-air dot), their
/// photo or initial inside, `badge` over the hexagon's foot, the play disc, the name (with a pin mark
/// when pinned), then `details`.
struct PCListenerCard<Details: View>: View {
    let name: String
    let avatarUrl: String?
    var width: CGFloat? = pcRecCardWidth
    var onAir = false
    var pinned = false
    var badge: AnyView? = nil
    let onOpen: () -> Void
    var onPlay: (() -> Void)? = nil
    @ViewBuilder var details: () -> Details

    var body: some View {
        let spec = FriendAvatarSpec.shared
        let avatar = CGFloat(spec.LISTENER_AVATAR)
        let ring = CGFloat(spec.LISTENER_RING)
        VStack(spacing: 0) {
            ZStack {
                PCHexagon(listener: true)
                    .fill(onAir
                          ? AnyShapeStyle(PC.success)
                          : AnyShapeStyle(LinearGradient(colors: [PC.hoverSubtle, PC.hoverDefault],
                                                         startPoint: .topLeading, endPoint: .bottomTrailing)))
                Color.clear
                    .overlay {
                        PCArtistImage(url: avatarUrl.flatMap { URL(string: $0) }) {
                            PC.placeholder.overlay {
                                Text(spec.initial(name: name))
                                    .font(.system(size: CGFloat(spec.LISTENER_INITIAL_SIZE), weight: .medium))
                                    .foregroundStyle(PC.fg3)
                            }
                        }
                    }
                    .clipShape(PCHexagon(listener: true))
                    .padding(ring)
                if onAir {
                    Circle().fill(PC.success)
                        .frame(width: CGFloat(spec.ON_AIR_DOT), height: CGFloat(spec.ON_AIR_DOT))
                        .overlay(Circle().strokeBorder(PC.cardBg, lineWidth: CGFloat(spec.ON_AIR_DOT_BORDER)))
                        .padding(.trailing, CGFloat(spec.ON_AIR_DOT_END)).padding(.bottom, CGFloat(spec.ON_AIR_DOT_BOTTOM))
                        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottomTrailing)
                }
                if let badge { badge }
                if let onPlay {
                    PCArtPlayButton(title: name, onPlay: onPlay)
                        .padding(recArtPlayInset)
                        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topTrailing)
                }
            }
            .frame(width: avatar, height: avatar)
            Spacer().frame(height: CGFloat(spec.LISTENER_NAME_GAP))
            HStack(spacing: 4) {
                Text(name).font(.system(size: 13, weight: .medium)).foregroundStyle(PC.fg1).lineLimit(1)
                if pinned { PCIcon(icon: .bookmark, size: 12, tint: PC.accentSoft) }
            }
            .frame(height: 20)
            details()
        }
        .padding(.horizontal, CGFloat(spec.LISTENER_PADDING_H)).padding(.vertical, CGFloat(spec.LISTENER_PADDING_V))
        .frame(width: width)
        .frame(maxWidth: width == nil ? .infinity : nil)
        .background(RoundedRectangle(cornerRadius: CGFloat(CardSpec.shared.RADIUS), style: .continuous).fill(PC.cardBg)
            .shadow(color: .black.opacity(0.08), radius: 2, y: 1))
        .contentShape(RoundedRectangle(cornerRadius: CGFloat(CardSpec.shared.RADIUS), style: .continuous))
        .onTapGesture(perform: onOpen)
        .accessibilityElement(children: .contain)
        .accessibilityLabel(spec.openLabel(name: name))
    }
}

/// Desktop's `Badge`, the neutral tone: a small pill, 12 / 600, bg-secondary with secondary text, never wrapping.
struct PCNeutralBadge: View {
    let text: String

    var body: some View {
        Text(text)
            .font(.system(size: 12, weight: .semibold))
            .foregroundStyle(PC.fg2)
            .lineLimit(1)
            .fixedSize()
            .padding(.horizontal, 8).padding(.vertical, 2)
            .background(Capsule().fill(PC.bgSecondary))
    }
}
