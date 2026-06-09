import SwiftUI
import Shared

// MARK: - Phase 2.5 interactions (docs/design/parachord-ios)
//
// Haptic-style track context menus (long-press) + the mock Dynamic Island
// live activity. Pure UI — no Spotify calls.

extension View {
    /// Long-press haptic context menu for a track row, mirroring the design's
    /// ContextMenu items. Play Next / Add to Queue are wired to the queue;
    /// nav + share items land with later phases.
    func pcTrackContextMenu(
        _ track: Track,
        coordinator: QueuePlaybackCoordinator,
        onGoToArtist: (() -> Void)? = nil,
        onGoToAlbum: (() -> Void)? = nil
    ) -> some View {
        contextMenu {
            Button { coordinator.playNext(track) } label: {
                Label("Play Next", systemImage: "text.line.first.and.arrowtriangle.forward")
            }
            Button { coordinator.addToQueue(track) } label: {
                Label("Add to Queue", systemImage: "text.append")
            }
            Button { } label: { Label("Add to Playlist…", systemImage: "music.note.list") }
            if let onGoToArtist {
                Button { onGoToArtist() } label: { Label("Go to Artist", systemImage: "music.mic") }
            }
            if let onGoToAlbum {
                Button { onGoToAlbum() } label: { Label("Go to Album", systemImage: "square.stack") }
            }
            Divider()
            ShareLink(item: "\(track.title) — \(track.artist)") {
                Label("Share", systemImage: "square.and.arrow.up")
            }
        } preview: {
            // Rich preview: artwork + title/artist (Apple Music style).
            HStack(spacing: 12) {
                PCArtwork(name: track.title, size: 56, radius: 8)
                VStack(alignment: .leading, spacing: 3) {
                    Text(track.title).font(.system(size: 15, weight: .semibold)).lineLimit(1)
                    Text(track.artist).font(.system(size: 13)).foregroundStyle(.secondary).lineLimit(1)
                }
                Spacer(minLength: 0)
            }
            .padding(14)
            .frame(width: 280)
        }
    }
}
