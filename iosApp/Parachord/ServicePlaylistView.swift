import SwiftUI
import Shared

// MARK: - A recommended service playlist (Android's ServicePlaylistScreen)
//
// Opens at once with skeleton rows, loads the playlist's tracks through the
// registry's link lookup, and saves it only when the user taps Save (the shared
// keyed import: a playlist already saved from this link is opened, not copied).

@MainActor
@Observable
final class ServicePlaylistViewModel {
    private let container = IosContainer.companion.shared

    let url: String
    let name: String
    let source: String
    /// The card's cover, else the lookup's own (Android's `coverUrl`); nil shows the track mosaic.
    var image: String?

    var tracks: [Track] = []
    var loading = true
    var error: String?
    var saving = false
    private var savedId: String?
    private var loadTask: Task<Void, Never>?

    init(url: String, name: String, source: String, image: String?) {
        self.url = url; self.name = name; self.source = source
        self.image = (image?.isEmpty ?? true) ? nil : image
    }

    /// The context a queue played from here carries (shared `servicePlaylistContext`).
    var context: PlaybackContext {
        RecommendationQueuesKt.servicePlaylistContext(name: name, url: url)
    }

    /// The first four distinct track covers (shared rule), for the mosaic when the card had no cover.
    var covers: [String] {
        RecommendationQueuesKt.playlistCovers(artworks: tracks.map { $0.artworkUrl })
    }

    func load() {
        loadTask?.cancel()
        loading = true; error = nil
        loadTask = Task {
            let r = try? await container.recommendationsServicePlaylist(url: url, name: name)
            if Task.isCancelled { return }
            if let q = r?.queue, !q.tracks.isEmpty {
                tracks = q.tracks
                if image == nil, let c = r?.cover, !c.isEmpty { image = c }
            } else {
                error = r?.message ?? RecommendationCopy.shared.couldntLoad(name: name)
            }
            loading = false
        }
    }

    func resolveVisible(_ t: Track, index: Int) {
        IosTrackResolverCache.shared.resolve(
            ResolveRequest(artist: t.artist, title: t.title, album: t.album), order: index)
    }

    enum SaveOutcome { case saved(String), alreadySaving, failed }

    /// Saves (or returns the earlier copy). Disabled only while an import is in flight.
    func save() async -> SaveOutcome {
        if let id = savedId { return .saved(id) }
        guard !saving else { return .alreadySaving }
        saving = true
        defer { saving = false }
        let r = try? await container.saveRecommendedPlaylist(url: url)
        guard let id = r?.playlistId else { return .failed }
        savedId = id
        return .saved(id)
    }
}

struct ServicePlaylistView: View {
    @State private var model: ServicePlaylistViewModel
    @State private var route: PCRoute?
    @Environment(QueuePlaybackCoordinator.self) private var coordinator
    @Environment(\.dismiss) private var dismiss
    private var resolverCache = IosTrackResolverCache.shared

    init(url: String, name: String, source: String, image: String?) {
        _model = State(initialValue: ServicePlaylistViewModel(url: url, name: name, source: source, image: image))
    }

    var body: some View {
        VStack(spacing: 0) {
            PCTopBar(title: model.name, leading: .back, onLeading: { dismiss() })
            if model.loading {
                ScrollView { header; PCSkeletonList(count: 8, art: 44) }
            } else if let err = model.error {
                PCErrorState(message: err, retryLabel: RecommendationCopy.shared.TRY_AGAIN, onRetry: { model.load() })
            } else {
                ScrollView {
                    header
                    LazyVStack(spacing: 0) {
                        ForEach(Array(model.tracks.enumerated()), id: \.offset) { index, track in
                            row(index: index, track: track) {
                                coordinator.setQueue(model.tracks, startIndex: index, context: model.context)
                            }
                            .onAppear { model.resolveVisible(track, index: index) }
                            .onDisappear { IosTrackResolverCache.shared.leave(artist: track.artist, title: track.title) }
                            .pcTrackContextMenu(track, coordinator: coordinator)
                        }
                    }
                    .padding(.bottom, 130)
                }
            }
        }
        .toolbar(.hidden, for: .navigationBar)
        // On an iPad the header and tracks sit in a centred column (`LIST_MAX`).
        .pcListColumn()
        .navigationDestination(item: $route) { pcRouteDestination($0) }
        .onAppear { if model.tracks.isEmpty && model.error == nil && model.loading { model.load() } }
    }

    private var header: some View {
        VStack(spacing: 12) {
            cover
                .frame(width: 160, height: 160)
                .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
                .shadow(color: .black.opacity(0.12), radius: 10, y: 5)
            Text(model.name).font(.system(size: 20, weight: .bold)).foregroundStyle(PC.fg1)
                .multilineTextAlignment(.center)
            Text(RecommendationCopy.shared.recommendedBy(sourceName: model.source))
                .font(.system(size: 13)).foregroundStyle(PC.fg2)
            HStack(spacing: 10) {
                Button { coordinator.setQueue(model.tracks, startIndex: 0, context: model.context) } label: {
                    HStack(spacing: 6) {
                        PCIcon(icon: .play, size: 16, tint: PC.onAccent)
                        Text(RecommendationCopy.shared.PLAY)
                            .font(.system(size: 14, weight: .semibold)).foregroundStyle(PC.onAccent)
                    }
                    .padding(.horizontal, 22).frame(height: 40)
                    .background(PC.fillAccent, in: Capsule())
                }
                .buttonStyle(.plain).disabled(model.tracks.isEmpty)
                Button { save() } label: {
                    Text(RecommendationCopy.shared.SAVE)
                        .font(.system(size: 14, weight: .medium)).foregroundStyle(PC.fg1)
                        .padding(.horizontal, 18).frame(height: 40)
                        .overlay(Capsule().strokeBorder(PC.border))
                }
                .buttonStyle(.plain).disabled(model.tracks.isEmpty || model.saving)
            }
        }
        .frame(maxWidth: .infinity)
        .padding(.horizontal, 20).padding(.top, 12).padding(.bottom, 16)
    }

    private func save() {
        let copy = RecommendationCopy.shared
        Task {
            switch await model.save() {
            case .saved(let id):
                coordinator.notice = QueuePlaybackCoordinator.Notice(message: copy.saved(name: model.name), showInCar: false)
                route = .savedPlaylist(id: id)
            case .alreadySaving:
                break
            case .failed:
                coordinator.notice = QueuePlaybackCoordinator.Notice(message: copy.couldntSave(name: model.name), showInCar: false)
            }
        }
    }

    @ViewBuilder
    private var cover: some View {
        if let img = model.image, let u = URL(string: img) {
            PCCachedImage(url: u) { $0.resizable().aspectRatio(contentMode: .fill) }
                placeholder: { PCArtwork(name: model.name, size: 160, radius: 0) }
        } else if !model.covers.isEmpty {
            let covers = model.covers
            LazyVGrid(columns: [GridItem(.fixed(80), spacing: 0), GridItem(.fixed(80), spacing: 0)], spacing: 0) {
                ForEach(0..<4, id: \.self) { j in
                    if j < covers.count, let u = URL(string: covers[j]) {
                        PCCachedImage(url: u) { $0.resizable().aspectRatio(contentMode: .fill) }
                            placeholder: { PCArtwork(name: "\(model.name)\(j)", size: 80, radius: 0) }
                            .frame(width: 80, height: 80).clipped()
                    } else { PCArtwork(name: "\(model.name)\(j)", size: 80, radius: 0) }
                }
            }
        } else {
            PCArtwork(name: model.name, size: 160, radius: 0)
        }
    }

    /// The shared track row (#760).
    private func row(index: Int, track: Track, onTap: @escaping () -> Void) -> some View {
        PCTrackRow(title: track.title, artist: track.artist, album: track.album, artworkUrl: track.artworkUrl,
                   number: index + 1,
                   isPlaying: coordinator.currentTrack?.id == track.id,
                   durationMs: track.duration?.int64Value,
                   onPlaySource: pcPlayFromSource(coordinator, title: track.title, artist: track.artist, play: onTap),
                   onTap: onTap)
            .padding(.horizontal, 20).padding(.vertical, 8)
    }

    /// The track's cover, else its resolved sources' (`pcTrackArt`), as the Recommendations song rows draw it.
    private func art(_ track: Track) -> some View {
        pcCover(pcTrackArt(track.artworkUrl, artist: track.artist, title: track.title, album: track.album),
                seed: track.title + track.artist, size: 44, radius: 6, artist: track.artist, album: track.album)
    }
}
