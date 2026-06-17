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
        onGoToAlbum: (() -> Void)? = nil,
        onRemoveFromCollection: (() -> Void)? = nil,
        onRemoveFromQueue: (() -> Void)? = nil
    ) -> some View {
        contextMenu {
            Button { coordinator.playNext(track) } label: {
                Label("Play Next", systemImage: "text.line.first.and.arrowtriangle.forward")
            }
            Button { coordinator.addToQueue(track) } label: {
                Label("Add to Queue", systemImage: "text.append")
            }
            Button { } label: { Label("Add to Playlist…", systemImage: "music.note.list") }
            // Queue-context only (#220): remove this track from the up-next list.
            if let onRemoveFromQueue {
                Button(role: .destructive) { onRemoveFromQueue() } label: {
                    Label("Remove from Queue", systemImage: "text.badge.minus")
                }
            }
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
            if let onRemoveFromCollection {
                Button(role: .destructive) { onRemoveFromCollection() } label: {
                    Label("Remove from Collection", systemImage: "heart.slash")
                }
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

// MARK: - Mock Dynamic Island live activity (chrome.jsx DynamicIslandLive)

/// Prototype now-playing pill near the top. Collapsed = artwork + EQ bars;
/// expands ~4.5s on each track change to reveal the title. A MOCK visual (not
/// an ActivityKit Live Activity), so on a device it sits at/over the hardware
/// Dynamic Island.
struct PCDynamicIsland: View {
    let title: String
    let isPlaying: Bool
    @State private var expanded = false
    @State private var eqUp = false

    var body: some View {
        HStack(spacing: 8) {
            PCArtwork(name: title, size: 24, radius: 6)
            if expanded {
                Text(title).font(.system(size: 11, weight: .semibold))
                    .foregroundStyle(.white).lineLimit(1).transition(.opacity)
            }
            Spacer(minLength: 0)
            if isPlaying { eqBars }
        }
        .padding(.horizontal, 8)
        .frame(width: expanded ? 220 : 128, height: 37)
        .background(.black, in: Capsule())
        .onAppear { eqUp = true; pulse() }
        .onChange(of: title) { _, _ in pulse() }
    }

    private func pulse() {
        withAnimation(.spring(duration: 0.32)) { expanded = true }
        Task { @MainActor in
            try? await Task.sleep(nanoseconds: 4_500_000_000)
            withAnimation(.spring(duration: 0.32)) { expanded = false }
        }
    }

    private var eqBars: some View {
        HStack(spacing: 2) {
            ForEach(0..<3, id: \.self) { i in
                let lows: [CGFloat] = [0.4, 0.8, 0.6]
                let highs: [CGFloat] = [1.0, 0.5, 0.85]
                Capsule().fill(PC.accentSoft)
                    .frame(width: 2.5, height: 14)
                    .scaleEffect(y: eqUp ? highs[i] : lows[i], anchor: .center)
                    .animation(.easeInOut(duration: 0.5).repeatForever().delay(Double(i) * 0.15), value: eqUp)
            }
        }
        .frame(height: 14)
    }
}
