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
    let image: String?

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

    /// Saves (or opens the earlier copy); returns the saved id, or nil after a failure
    /// (the caller shows `couldntSave`). A success keeps Save disabled and remembers the id.
    func save() async -> String? {
        if let id = savedId { return id }
        guard !saving else { return nil }
        saving = true
        do {
            let r = try await container.importPlaylistFromUrl(url: url, savedKey: container.recommendedPlaylistKey(url: url))
            savedId = r.playlistId
            return r.playlistId
        } catch {
            saving = false
            return nil
        }
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
                            Button { coordinator.setQueue(model.tracks, startIndex: index, context: model.context) } label: {
                                row(index: index, track: track)
                            }
                            .buttonStyle(.plain)
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
                    Label(RecommendationCopy.shared.PLAY, systemImage: "play.fill")
                        .font(.system(size: 14, weight: .semibold)).foregroundStyle(.white)
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
            if let id = await model.save() {
                coordinator.notice = QueuePlaybackCoordinator.Notice(message: copy.saved(name: model.name), showInCar: false)
                route = .savedPlaylist(id: id)
            } else {
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

    @ViewBuilder
    private func row(index: Int, track: Track) -> some View {
        let isCurrent = coordinator.currentTrack?.id == track.id
        HStack(spacing: 12) {
            Text("\(index + 1)").font(.system(size: 13, design: .monospaced))
                .foregroundStyle(PC.fg3).frame(width: 24)
            art(track)
            VStack(alignment: .leading, spacing: 2) {
                Text(track.title).font(.system(size: 15, weight: .medium))
                    .foregroundStyle(pcTrackNoMatch(artist: track.artist, title: track.title, album: track.album) ? PC.fg3
                        : (isCurrent ? PC.accent : PC.fg1)).lineLimit(1)
                Text(track.artist).font(.system(size: 13)).foregroundStyle(PC.fg2).lineLimit(1)
            }
            Spacer(minLength: 8)
            if let ranked = resolverCache.cached(artist: track.artist, title: track.title, album: track.album),
               !ranked.isEmpty {
                ResolverBadgeRow(sources: ranked)
            }
        }
        .padding(.horizontal, 20).padding(.vertical, 8).contentShape(Rectangle())
    }

    @ViewBuilder
    private func art(_ track: Track) -> some View {
        if let url = track.artworkUrl, let u = URL(string: url) {
            PCCachedImage(url: u) { $0.resizable().aspectRatio(contentMode: .fill) }
                placeholder: { PCArtwork(name: track.title + track.artist, size: 44, radius: 6) }
                .frame(width: 44, height: 44).clipShape(RoundedRectangle(cornerRadius: 6, style: .continuous))
        } else { PCArtwork(name: track.title + track.artist, size: 44, radius: 6) }
    }
}
