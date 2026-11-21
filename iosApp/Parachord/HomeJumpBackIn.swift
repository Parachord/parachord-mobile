import SwiftUI
import Shared

// MARK: - Jump back in (desktop `home-sections.js` JumpBackIn)
//
// Up to four tiles in a 2x2 grid (Android's HomeJumpBackIn.kt), each with its
// art on the left and a round Play over the art. The tile opens its item; Play
// plays it. With no tiles, a new user gets the "Build your collection" card.

private let tileHeight: CGFloat = 72
private let tileGap: CGFloat = 12

struct HomeJumpBackIn: View {
    let page: IosHomePage
    let onOpen: (IosJumpTile) -> Void
    let onPlay: (IosJumpTile) -> Void
    let onShown: (IosJumpTile) -> Void
    let onImport: () -> Void

    private let columns = [GridItem(.flexible(), spacing: tileGap), GridItem(.flexible(), spacing: tileGap)]

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            PCSectionHeader(title: HomeCopy.shared.JUMP_BACK_IN)
            Group {
                if page.showImport {
                    HomeImportCard(onImport: onImport)
                } else {
                    LazyVGrid(columns: columns, spacing: tileGap) {
                        ForEach(page.tiles, id: \.key) { tile in
                            HomeJumpTileView(
                                tile: tile,
                                onOpen: { onOpen(tile) },
                                onPlay: { onPlay(tile) }
                            )
                            .onAppear { onShown(tile) }
                        }
                    }
                }
            }
            .padding(.horizontal, homeGutter)
        }
    }
}

private struct HomeJumpTileView: View {
    let tile: IosJumpTile
    let onOpen: () -> Void
    let onPlay: () -> Void

    var body: some View {
        HStack(spacing: 0) {
            ZStack {
                HomeTileArt(tile: tile, size: tileHeight)
                PCArtPlayButton(title: tile.title, onPlay: onPlay, size: CGFloat(ArtPlayButtonSpec.shared.TILE_SIZE))
            }
            .frame(width: tileHeight, height: tileHeight)
            VStack(alignment: .leading, spacing: 2) {
                Text(tile.title)
                    .font(.system(size: 14, weight: .semibold)).foregroundStyle(PC.fg1)
                    .lineLimit(1)
                Text(tile.subtitle)
                    .font(.system(size: 12)).foregroundStyle(PC.fg2)
                    .lineLimit(1)
            }
            .padding(.horizontal, 12)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .frame(height: tileHeight)
        .background(PC.bgSecondary)
        .clipShape(RoundedRectangle(cornerRadius: CGFloat(DesignTokens.shared.RADIUS_CARD), style: .continuous))
        .contentShape(Rectangle())
        .onTapGesture(perform: onOpen)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(HomeCopy.shared.openLabel(title: tile.title))
        .accessibilityAddTraits(.isButton)
        .accessibilityAction(.default, onOpen)
        .accessibilityAction(named: HomeCopy.shared.playLabel(title: tile.title), onPlay)
    }
}

/// One cover, a 2x2 of covers, or the placeholder.
private struct HomeTileArt: View {
    let tile: IosJumpTile
    let size: CGFloat

    var body: some View {
        if tile.mosaic.count >= 4 {
            let urls = Array(tile.mosaic.prefix(4))
            let half = size / 2
            VStack(spacing: 0) {
                ForEach(0..<2, id: \.self) { row in
                    HStack(spacing: 0) {
                        ForEach(0..<2, id: \.self) { col in
                            pcCover(urls[row * 2 + col], seed: tile.title, size: half, radius: 0)
                        }
                    }
                }
            }
            .frame(width: size, height: size)
        } else {
            let isAlbum = tile.kind == IosHomePageKt.JUMP_KIND_ALBUM
            pcCover(tile.imageUrl ?? tile.mosaic.first, seed: tile.title, size: size, radius: 0,
                    artist: isAlbum ? tile.targetArtist : nil, album: isAlbum ? tile.targetName : nil)
        }
    }
}

/// "Build your collection" (a new user: no collection, no playlists).
private struct HomeImportCard: View {
    let onImport: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text(HomeCopy.shared.IMPORT_TITLE)
                .font(.system(size: 16, weight: .semibold)).foregroundStyle(PC.fg1)
            Text(HomeCopy.shared.IMPORT_BODY)
                .font(.system(size: 13)).foregroundStyle(PC.fg2)
                .fixedSize(horizontal: false, vertical: true)
                .padding(.top, 4)
            Button(action: onImport) {
                Text(HomeCopy.shared.IMPORT_BUTTON)
                    .font(.system(size: 14, weight: .semibold)).foregroundStyle(PC.onAccent)
                    .padding(.horizontal, 20).frame(minHeight: 40)
                    .background(PC.fillAccent, in: RoundedRectangle(cornerRadius: CGFloat(DesignTokens.shared.RADIUS_BUTTON)))
            }
            .buttonStyle(.plain)
            .padding(.top, 12)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, 20).padding(.vertical, 16)
        .background(PC.bgSecondary, in: RoundedRectangle(cornerRadius: CGFloat(DesignTokens.shared.RADIUS_PANEL)))
        .overlay(RoundedRectangle(cornerRadius: CGFloat(DesignTokens.shared.RADIUS_PANEL)).strokeBorder(PC.border, lineWidth: 1))
    }
}
