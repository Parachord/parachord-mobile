import SwiftUI
import Shared

// MARK: - App-level playback (phase 5.2)
//
// The single app-wide playback engine: one IosAVPlayer driven by the
// shared QueueManager via QueuePlaybackCoordinator (both built in phase
// 4.4–4.6). Created once at the app root and shared by the Now Playing
// tab + the persistent mini player. (The Dev tab keeps its own separate
// smoke-test player; it retires when the Dev tab does.)

@Observable
final class AppPlayback {
    let player: IosAVPlayer
    let musicKit: IosMusicKitPlayer
    let spotify: IosSpotifyConnect
    let coordinator: QueuePlaybackCoordinator

    @MainActor
    init() {
        let p = IosAVPlayer()
        let mk = IosMusicKitPlayer()
        let sp = IosSpotifyConnect()
        self.player = p
        self.musicKit = mk
        self.spotify = sp
        self.coordinator = QueuePlaybackCoordinator(
            player: p,
            musicKit: mk,
            spotify: sp,
            resolverCache: IosTrackResolverCache.shared
        )
    }

    /// Until a Library / resolver path feeds real tracks, this seeds a
    /// 3-track SoundHelix queue so Now Playing + the mini player have
    /// something to drive. Replaced by real playback entry points
    /// (tap a track in Library / Search / a playlist) as those land.
    func playSampleQueue() {
        let tracks = AppPlayback.sampleTracks
        coordinator.setQueue(tracks, startIndex: 0)
    }

    private static let sampleTrackData: [(title: String, url: String)] = [
        ("SoundHelix Song 1", "https://www.soundhelix.com/examples/mp3/SoundHelix-Song-1.mp3"),
        ("SoundHelix Song 2", "https://www.soundhelix.com/examples/mp3/SoundHelix-Song-2.mp3"),
        ("SoundHelix Song 3", "https://www.soundhelix.com/examples/mp3/SoundHelix-Song-3.mp3"),
    ]

    static var sampleTracks: [Track] {
        sampleTrackData.enumerated().map { idx, t in
            makeSampleTrack(id: "sample-\(idx)", title: t.title, url: t.url)
        }
    }

    /// The 19-param Kotlin-bridged Track initializer overwhelms Swift's
    /// inline type-checker inside a `.map` closure — keep it in a
    /// standalone func with an explicit return type.
    private static func makeSampleTrack(id: String, title: String, url: String) -> Track {
        Track(
            id: id, title: title, artist: "T. Schürger",
            album: nil, albumId: nil, duration: nil, artworkUrl: nil,
            sourceType: "direct", sourceUrl: url, addedAt: 0,
            resolver: "direct", spotifyUri: nil, soundcloudId: nil,
            spotifyId: nil, appleMusicId: nil, isrc: nil, recordingMbid: nil,
            artistMbid: nil, releaseMbid: nil, crossResolverEnrichedAt: nil
        )
    }
}

// MARK: - Now Playing screen

struct NowPlayingView: View {
    let playback: AppPlayback

    private var player: IosAVPlayer { playback.player }
    private var coordinator: QueuePlaybackCoordinator { playback.coordinator }

    var body: some View {
        NavigationStack {
            Group {
                if coordinator.currentTrack == nil {
                    emptyState
                } else {
                    playingState
                }
            }
            .navigationTitle("Now Playing")
            .toolbar {
                // Spotify Connect device picker — re-pick the output device
                // (e.g. switch from "This device" to a Mac that plays silently).
                ToolbarItem(placement: .topBarTrailing) {
                    Button {
                        Task { await playback.spotify.chooseDevice() }
                    } label: {
                        Image(systemName: "hifispeaker.2.fill")
                    }
                    .tint(.accentColor)
                }
            }
        }
    }

    private var emptyState: some View {
        ContentUnavailableView {
            Label("Nothing playing", systemImage: "music.note")
        } description: {
            Text("Start a queue to see transport controls here.")
        } actions: {
            Button("Play sample queue") { playback.playSampleQueue() }
                .buttonStyle(.borderedProminent)
        }
    }

    private var playingState: some View {
        VStack(spacing: 24) {
            // Artwork placeholder — real artwork lands with the
            // resolver/enrichment path.
            RoundedRectangle(cornerRadius: 16)
                .fill(Color(.systemGray5))
                .aspectRatio(1, contentMode: .fit)
                .frame(maxWidth: 320)
                .overlay {
                    Image(systemName: "music.note")
                        .font(.system(size: 64))
                        .foregroundStyle(.secondary)
                }
                .padding(.top, 24)

            VStack(spacing: 4) {
                Text(coordinator.currentTrack?.title ?? "—")
                    .font(.title2.weight(.semibold))
                    .lineLimit(1)
                Text(coordinator.currentTrack?.artist ?? "—")
                    .font(.body)
                    .foregroundStyle(.secondary)
            }

            scrubber

            transportControls

            upNextList

            Spacer()
        }
        .padding(.horizontal)
    }

    private var scrubber: some View {
        VStack(spacing: 4) {
            Slider(
                value: Binding(
                    get: { coordinator.currentTime },
                    set: { coordinator.seek(to: $0) }
                ),
                in: 0...max(coordinator.duration, 0.01)
            )
            .tint(.accentColor)
            .disabled(coordinator.duration <= 0)

            HStack {
                Text(Self.time(coordinator.currentTime))
                Spacer()
                Text(Self.time(coordinator.duration))
            }
            .font(.caption.monospacedDigit())
            .foregroundStyle(.secondary)
        }
    }

    private var transportControls: some View {
        HStack(spacing: 40) {
            Button { coordinator.skipPrevious() } label: {
                Image(systemName: "backward.fill").font(.title)
            }
            Button { coordinator.togglePlayPause() } label: {
                Image(systemName: coordinator.isPlaying ? "pause.circle.fill" : "play.circle.fill")
                    .font(.system(size: 64))
            }
            Button { coordinator.skipNext() } label: {
                Image(systemName: "forward.fill").font(.title)
            }
        }
        .tint(.accentColor)
    }

    @ViewBuilder
    private var upNextList: some View {
        if !coordinator.upNext.isEmpty {
            VStack(alignment: .leading, spacing: 6) {
                Text("Up Next")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(.secondary)
                    .frame(maxWidth: .infinity, alignment: .leading)
                ForEach(Array(coordinator.upNext.enumerated()), id: \.offset) { idx, track in
                    Button {
                        coordinator.playFromQueue(idx)
                    } label: {
                        HStack {
                            Text(track.title).font(.callout)
                            Spacer()
                        }
                    }
                    .buttonStyle(.plain)
                }
            }
        }
    }

    static func time(_ seconds: Double) -> String {
        guard seconds.isFinite, seconds >= 0 else { return "0:00" }
        let s = Int(seconds)
        return String(format: "%d:%02d", s / 60, s % 60)
    }
}

// MARK: - Mini player (persists across tabs)

struct MiniPlayer: View {
    let playback: AppPlayback

    private var player: IosAVPlayer { playback.player }
    private var coordinator: QueuePlaybackCoordinator { playback.coordinator }

    var body: some View {
        if coordinator.currentTrack != nil {
            HStack(spacing: 12) {
                RoundedRectangle(cornerRadius: 6)
                    .fill(Color(.systemGray4))
                    .frame(width: 40, height: 40)
                    .overlay {
                        Image(systemName: "music.note")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }

                VStack(alignment: .leading, spacing: 1) {
                    Text(coordinator.currentTrack?.title ?? "—")
                        .font(.callout.weight(.medium))
                        .lineLimit(1)
                    Text(coordinator.currentTrack?.artist ?? "—")
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                }

                Spacer()

                Button { coordinator.togglePlayPause() } label: {
                    Image(systemName: coordinator.isPlaying ? "pause.fill" : "play.fill")
                        .font(.title3)
                }
                Button { coordinator.skipNext() } label: {
                    Image(systemName: "forward.fill")
                        .font(.body)
                }
            }
            .tint(.accentColor)
            .padding(.horizontal, 12)
            .padding(.vertical, 8)
            .background(.regularMaterial)
            .clipShape(RoundedRectangle(cornerRadius: 12))
            .padding(.horizontal, 8)
        }
    }
}
