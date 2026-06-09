import SwiftUI
import Shared

// MARK: - Critical Darlings + Fresh Drops (Phase 4 — DB-backed curated lists)
//
// Both are album/release lists from the shared repos (now unblocked by the iOS
// DB layer: CriticalDarlings → ImageEnrichmentService, FreshDrops → TrackDao).
// Each row navigates to the AlbumScreen. NOTE: CriticsPickAlbum.description is
// shadowed by NSObject.description on iOS (AGENTS.md rule), so the critic blurb
// isn't shown until that shared field is renamed.

private struct CuratedAlbumRow: View {
    let art: String?
    let title: String
    let artist: String
    let subtitle: String?

    var body: some View {
        HStack(spacing: 12) {
            if let art, let u = URL(string: art) {
                AsyncImage(url: u) { img in img.resizable().aspectRatio(contentMode: .fill) }
                    placeholder: { PCArtwork(name: title + artist, size: 52, radius: 8) }
                    .frame(width: 52, height: 52).clipShape(RoundedRectangle(cornerRadius: 8, style: .continuous))
            } else {
                PCArtwork(name: title + artist, size: 52, radius: 8)
            }
            VStack(alignment: .leading, spacing: 2) {
                Text(title).font(.system(size: 15, weight: .medium)).foregroundStyle(PC.fg1).lineLimit(1)
                Text(artist).font(.system(size: 13)).foregroundStyle(PC.fg2).lineLimit(1)
                if let subtitle, !subtitle.isEmpty {
                    Text(subtitle).font(.system(size: 12)).foregroundStyle(PC.fg3).lineLimit(1)
                }
            }
            Spacer(minLength: 0)
        }
        .padding(.vertical, 4)
    }
}

// MARK: Critical Darlings

@MainActor @Observable
final class CriticalDarlingsModel {
    private let container = IosContainer.companion.shared
    var albums: [CriticsPickAlbum] = []
    var isLoading = false
    var loaded = false

    func load() async {
        guard !loaded else { return }
        isLoading = true
        albums = (try? await container.loadCriticalDarlings()) ?? []
        isLoading = false
        loaded = true
    }
}

struct CriticalDarlingsScreen: View {
    @State private var model = CriticalDarlingsModel()

    var body: some View {
        Group {
            if model.isLoading && !model.loaded {
                ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
            } else {
                List(Array(model.albums.enumerated()), id: \.element.id) { _, album in
                    NavigationLink { AlbumScreen(title: album.title, artist: album.artist) } label: {
                        CuratedAlbumRow(art: album.albumArt, title: album.title, artist: album.artist, subtitle: nil)
                    }
                }
                .listStyle(.plain)
            }
        }
        .navigationTitle("Critical Darlings")
        .navigationBarTitleDisplayMode(.inline)
        .task { await model.load() }
    }
}

// MARK: Fresh Drops

@MainActor @Observable
final class FreshDropsModel {
    private let container = IosContainer.companion.shared
    var drops: [FreshDrop] = []
    var isLoading = false
    var loaded = false

    func load() async {
        guard !loaded else { return }
        isLoading = true
        drops = (try? await container.loadFreshDrops()) ?? []
        isLoading = false
        loaded = true
    }
}

struct FreshDropsScreen: View {
    @State private var model = FreshDropsModel()

    var body: some View {
        Group {
            if model.isLoading && !model.loaded {
                ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
            } else {
                List(Array(model.drops.enumerated()), id: \.offset) { _, drop in
                    NavigationLink { AlbumScreen(title: drop.title, artist: drop.artist) } label: {
                        CuratedAlbumRow(art: drop.albumArt, title: drop.title, artist: drop.artist,
                                        subtitle: drop.releaseType.capitalized)
                    }
                }
                .listStyle(.plain)
            }
        }
        .navigationTitle("Fresh Drops")
        .navigationBarTitleDisplayMode(.inline)
        .task { await model.load() }
    }
}
