import SwiftUI
import Shared

// MARK: - Recommendations cards
//
// Stateless cards for the Recommendations page. They draw what the shared page
// (IosRecItem / IosRecListener) says and compute no rules. Shapes mirror
// Android's RecommendationCards.kt. Long-press menus go on at the call site.

/// A card's default width in a scrolling row.
let pcRecCardWidth: CGFloat = 140

private let recPlayDisc: CGFloat = 32
private let recPlayTarget: CGFloat = 48

private struct RecCardText: View {
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

/// The 32 pt play disc inside a 48 pt tap target.
private struct RecPlayDisc: View {
    let onPlay: () -> Void

    var body: some View {
        Button(action: onPlay) {
            ZStack {
                Circle().fill(PC.fillAccent).frame(width: recPlayDisc, height: recPlayDisc)
                PCIcon(icon: .play, size: 16, tint: PC.onAccent)
            }
            .frame(width: recPlayTarget, height: recPlayTarget)
            .contentShape(Circle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(RecommendationCopy.shared.PLAY)
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

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            ZStack {
                RecCover(url: item.imageUrl, name: item.name, size: width)
                if let coming = item.comingLabel {
                    Text(coming)
                        .font(.system(size: 11, weight: .semibold))
                        .foregroundStyle(PC.onAccent)
                        .lineLimit(1)
                        .padding(.horizontal, 8).padding(.vertical, 4)
                        .background(Capsule().fill(PC.fillAccent))
                        .padding(8)
                        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topTrailing)
                }
                RecPlayDisc(onPlay: onPlay)
                    .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottomTrailing)
            }
            .frame(width: width, height: width)
            Spacer().frame(height: 8)
            RecCardText(text: item.name, primary: true)
            RecCardText(text: (item.artist ?? "").isEmpty ? "\u{00A0}" : (item.artist ?? ""))
            RecCardText(text: item.year.isEmpty ? "\u{00A0}" : item.year, tertiary: true)
        }
        .frame(width: width)
        .contentShape(Rectangle())
        .onTapGesture(perform: onOpen)
        .accessibilityElement(children: .contain)
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
        VStack(alignment: .leading, spacing: 0) {
            RecCover(url: item.imageUrl, name: item.name, size: width)
            Spacer().frame(height: 8)
            RecCardText(text: item.name, primary: true)
            RecCardText(text: item.sourceName.isEmpty ? "\u{00A0}" : item.sourceName)
        }
        .frame(width: width)
        .contentShape(Rectangle())
        .onTapGesture(perform: onOpen)
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
        VStack(alignment: .leading, spacing: 0) {
            ZStack {
                mosaic
                RecPlayDisc(onPlay: onPlay)
                    .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottomTrailing)
            }
            .frame(width: width, height: width)
            .background(PC.bgInset)
            .clipShape(RoundedRectangle(cornerRadius: 6, style: .continuous))
            Spacer().frame(height: 8)
            RecCardText(text: item.weeklyLabel ?? item.name, primary: true)
            RecCardText(text: (item.weeklyWeekLabel ?? "").isEmpty ? "\u{00A0}" : (item.weeklyWeekLabel ?? ""))
        }
        .frame(width: width)
        .contentShape(Rectangle())
        .onTapGesture(perform: onOpen)
    }

    @ViewBuilder private var mosaic: some View {
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
                RecPlayDisc(onPlay: onPlay)
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

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            PCSkeletonBox(width: width, height: width, radius: 6)
            Spacer().frame(height: 8)
            VStack(alignment: .leading, spacing: 4) {
                ForEach(0..<max(lines, 1), id: \.self) { i in
                    PCSkeletonBox(width: width * (i == 0 ? 0.8 : 0.5), height: i == 0 ? 12 : 10, radius: 4)
                }
            }
        }
        .frame(width: width, alignment: .leading)
        .accessibilityHidden(true)
    }
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
