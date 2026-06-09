import SwiftUI
import Shared

// MARK: - Now Playing (Phase 2 — docs/design/parachord-ios/nowplaying.jsx)
//
// Full always-dark sheet: large art, title/artist/album, scrubber, transport,
// the "Playing From" RESOLVER DECK (the signature moment), bottom actions, and
// an Up-Next peek that opens the queue. Wired to the real
// QueuePlaybackCoordinator.

private let pcKnownResolvers = ["spotify", "applemusic", "bandcamp", "soundcloud", "youtube", "localfiles"]

private func pcResolverName(_ r: String) -> String {
    switch r.lowercased() {
    case "spotify": return "Spotify"
    case "applemusic": return "Apple Music"
    case "bandcamp": return "Bandcamp"
    case "soundcloud": return "SoundCloud"
    case "youtube": return "YouTube"
    case "localfiles": return "Local Files"
    default: return "Direct"
    }
}

private func pcTime(_ s: Double) -> String {
    guard s.isFinite, s >= 0 else { return "0:00" }
    let i = Int(s); return String(format: "%d:%02d", i / 60, i % 60)
}

struct PCNowPlaying: View {
    @Bindable var coordinator: QueuePlaybackCoordinator
    let onClose: () -> Void

    @State private var showQueue = false
    @State private var dragY: CGFloat = 0

    private var npBg: Color { Color(uiColor: UIColor(hex: 0x1e1e20)) }

    var body: some View {
        let track = coordinator.currentTrack
        ZStack {
            npBg.ignoresSafeArea()
            VStack(spacing: 0) {
                grabberBar
                ScrollView {
                    VStack(spacing: 16) {
                        PCArtwork(name: track?.title ?? "P", radius: 14)
                            .aspectRatio(1, contentMode: .fit)
                            .frame(maxWidth: .infinity)
                            .shadow(color: .black.opacity(0.6), radius: 24, y: 20)
                            .padding(.top, 4)

                        titleRow(track)
                        scrubber
                        transport
                        PCResolverDeck(
                            active: coordinator.activeResolverName,
                            available: coordinator.availableResolvers(),
                            onPick: { coordinator.switchResolver($0) }
                        )
                        bottomActions
                    }
                    .padding(.horizontal, 24)
                    .padding(.bottom, 24)
                }
                upNextPeek
            }
        }
        .preferredColorScheme(.dark)
        .offset(y: max(0, dragY))
        .gesture(
            DragGesture()
                .onChanged { v in if v.translation.height > 0 { dragY = v.translation.height } }
                .onEnded { v in
                    if v.translation.height > 120 { onClose() } else { withAnimation(.spring(duration: 0.3)) { dragY = 0 } }
                }
        )
        .sheet(isPresented: $showQueue) {
            PCQueueSheet(coordinator: coordinator)
                .presentationDetents([.large])
                .presentationBackground(npBg)
        }
    }

    private var grabberBar: some View {
        VStack(spacing: 0) {
            Capsule().fill(.white.opacity(0.35)).frame(width: 36, height: 5).padding(.top, 8)
            HStack {
                Text("NOW PLAYING").font(.system(size: 13, weight: .medium)).tracking(3)
                    .foregroundStyle(.white.opacity(0.7))
                Spacer()
                Button(action: onClose) {
                    Image(systemName: "chevron.down").font(.system(size: 16, weight: .semibold))
                        .foregroundStyle(.white).frame(width: 32, height: 32)
                        .background(.white.opacity(0.14), in: Circle())
                }
                .buttonStyle(.plain)
            }
            .padding(.horizontal, 20).padding(.top, 12)
        }
        .contentShape(Rectangle())
    }

    private func titleRow(_ track: Track?) -> some View {
        HStack(alignment: .top, spacing: 14) {
            VStack(alignment: .leading, spacing: 4) {
                Text(track?.title ?? "—").font(.system(size: 22, weight: .bold)).foregroundStyle(.white).lineLimit(2)
                Text(track?.artist ?? "—").font(.system(size: 16, weight: .medium)).foregroundStyle(PC.accentSoft)
                if let album = track?.album, !album.isEmpty {
                    Text("From \(album)").font(.system(size: 13)).foregroundStyle(.white.opacity(0.55)).lineLimit(1)
                }
            }
            Spacer(minLength: 0)
            Image(systemName: "heart").font(.system(size: 18)).foregroundStyle(PC.error)
                .frame(width: 36, height: 36).background(.white.opacity(0.14), in: Circle())
        }
    }

    private var scrubber: some View {
        let dur = max(coordinator.duration, 0.01)
        let frac = max(0, min(1, coordinator.currentTime / dur))
        return VStack(spacing: 8) {
            GeometryReader { geo in
                ZStack(alignment: .leading) {
                    Capsule().fill(.white.opacity(0.15))
                    Capsule().fill(.white).frame(width: geo.size.width * frac)
                }
                .contentShape(Rectangle())
                .gesture(DragGesture(minimumDistance: 0).onEnded { v in
                    coordinator.seek(to: max(0, min(1, v.location.x / geo.size.width)) * dur)
                })
            }
            .frame(height: 6)
            HStack {
                Text(pcTime(coordinator.currentTime))
                Spacer()
                Text("-\(pcTime(max(0, coordinator.duration - coordinator.currentTime)))")
            }
            .font(.system(size: 12, weight: .medium, design: .monospaced))
            .foregroundStyle(.white.opacity(0.55))
        }
    }

    private var transport: some View {
        HStack {
            Button { coordinator.toggleShuffle() } label: {
                Image(systemName: "shuffle").font(.system(size: 20)).foregroundStyle(.white.opacity(0.55))
            }
            Spacer()
            Button { coordinator.skipPrevious() } label: {
                Image(systemName: "backward.fill").font(.system(size: 26)).foregroundStyle(.white.opacity(0.88))
            }
            Spacer()
            Button { coordinator.togglePlayPause() } label: {
                Image(systemName: coordinator.isPlaying ? "pause.fill" : "play.fill")
                    .font(.system(size: 30)).foregroundStyle(.black)
                    .frame(width: 76, height: 76).background(.white, in: Circle())
                    .shadow(color: .black.opacity(0.3), radius: 12, y: 8)
            }
            Spacer()
            Button { coordinator.skipNext() } label: {
                Image(systemName: "forward.fill").font(.system(size: 26)).foregroundStyle(.white.opacity(0.88))
            }
            Spacer()
            Image(systemName: "repeat").font(.system(size: 20)).foregroundStyle(.white.opacity(0.55))
        }
        .buttonStyle(.plain)
        .padding(.vertical, 4)
    }

    private var bottomActions: some View {
        HStack(spacing: 0) {
            actionBtn("airplayaudio", nil) { }
            actionBtn("sparkles", "Spinoff") { }
            ZStack(alignment: .topTrailing) {
                actionBtn("list.bullet", "Queue") { showQueue = true }
                let n = coordinator.upNext.count
                if n > 0 {
                    Text("\(n)").font(.system(size: 10, weight: .bold)).foregroundStyle(.black)
                        .padding(.horizontal, 6).padding(.vertical, 1)
                        .background(PC.accentSoft, in: Capsule()).offset(x: -2, y: -2)
                }
            }
            actionBtn("ellipsis", nil) { }
        }
        .foregroundStyle(.white.opacity(0.65))
    }

    private func actionBtn(_ icon: String, _ label: String?, _ go: @escaping () -> Void) -> some View {
        Button(action: go) {
            VStack(spacing: 4) {
                Image(systemName: icon).font(.system(size: 21))
                if let label { Text(label).font(.system(size: 11)) }
            }
            .frame(maxWidth: .infinity)
        }
        .buttonStyle(.plain)
    }

    private var upNextPeek: some View {
        let next = coordinator.upNext.first
        return Button { showQueue = true } label: {
            HStack(spacing: 10) {
                Image(systemName: "chevron.up").font(.system(size: 14)).foregroundStyle(.white.opacity(0.5))
                Text("UP NEXT").font(.system(size: 11, weight: .semibold)).tracking(1.5).foregroundStyle(.white.opacity(0.5))
                Spacer()
                Text(next.map { "\($0.title) · \($0.artist)" } ?? "End of queue")
                    .font(.system(size: 13, weight: .medium)).foregroundStyle(.white.opacity(0.8)).lineLimit(1)
            }
            .padding(.horizontal, 22).padding(.vertical, 12)
            .frame(maxWidth: .infinity)
            .background(.white.opacity(0.03))
            .overlay(Rectangle().fill(.white.opacity(0.1)).frame(height: 0.5), alignment: .top)
        }
        .buttonStyle(.plain)
    }
}

// MARK: - Resolver deck ("Playing From")

struct PCResolverDeck: View {
    let active: String
    let available: [String]
    let onPick: (String) -> Void
    @State private var open = false

    private func square(_ r: String, size: CGFloat) -> some View {
        RoundedRectangle(cornerRadius: 6, style: .continuous)
            .fill(ResolverColor.of(r).solid)
            .frame(width: size, height: size)
            .overlay(Text(String(pcResolverName(r).prefix(1)))
                .font(.system(size: size * 0.5, weight: .bold)).foregroundStyle(.white))
    }

    var body: some View {
        VStack(spacing: 8) {
            Text("PLAYING FROM").font(.system(size: 11, weight: .semibold)).tracking(1.6)
                .foregroundStyle(.white.opacity(0.42))
            Button { withAnimation(.spring(duration: 0.25)) { open.toggle() } } label: {
                HStack(spacing: 9) {
                    square(active, size: 22)
                    Text(pcResolverName(active)).font(.system(size: 14, weight: .semibold)).foregroundStyle(.white)
                    Text("320 kbps").font(.system(size: 12)).foregroundStyle(.white.opacity(0.5))
                    Image(systemName: "chevron.down").font(.system(size: 12))
                        .foregroundStyle(.white.opacity(0.55))
                        .rotationEffect(.degrees(open ? 180 : 0))
                }
                .padding(.leading, 8).padding(.trailing, 12).padding(.vertical, 7)
                .background(.white.opacity(0.1), in: Capsule())
            }
            .buttonStyle(.plain)
        }
        .overlay(alignment: .bottom) {
            if open {
                let order = available + pcKnownResolvers.filter { !available.contains($0) }
                VStack(spacing: 0) {
                    ForEach(order, id: \.self) { r in
                        let avail = available.contains(r)
                        Button {
                            if avail { onPick(r) }
                            withAnimation { open = false }
                        } label: {
                            HStack(spacing: 10) {
                                square(r, size: 22)
                                Text(pcResolverName(r)).font(.system(size: 15, weight: .medium)).foregroundStyle(.white)
                                Spacer()
                                if r == active {
                                    Image(systemName: "checkmark").font(.system(size: 13, weight: .bold)).foregroundStyle(PC.accentSoft)
                                } else if !avail {
                                    Text("Not connected").font(.system(size: 12)).foregroundStyle(.white.opacity(0.5))
                                }
                            }
                            .padding(.horizontal, 12).padding(.vertical, 10)
                            .opacity(avail ? 1 : 0.45)
                        }
                        .buttonStyle(.plain)
                    }
                }
                .padding(6)
                .frame(width: 250)
                .background(Color(uiColor: UIColor(hex: 0x2c2c2e)), in: RoundedRectangle(cornerRadius: 16, style: .continuous))
                .shadow(color: .black.opacity(0.55), radius: 20, y: 16)
                .offset(y: -52)
            }
        }
    }
}

// MARK: - Queue sheet

struct PCQueueSheet: View {
    @Bindable var coordinator: QueuePlaybackCoordinator

    var body: some View {
        let up = coordinator.upNext
        VStack(spacing: 0) {
            Capsule().fill(.white.opacity(0.35)).frame(width: 36, height: 5).padding(.top, 8)
            HStack {
                VStack(alignment: .leading, spacing: 4) {
                    Text("UP NEXT · \(up.count) TRACKS").font(.system(size: 13, weight: .semibold)).tracking(2)
                        .foregroundStyle(PC.accentSoft)
                    Text("Playing from your queue").font(.system(size: 12)).foregroundStyle(.white.opacity(0.55))
                }
                Spacer()
                Button("Clear") { coordinator.clearQueue() }
                    .font(.system(size: 15, weight: .medium)).foregroundStyle(.white.opacity(0.75))
            }
            .padding(.horizontal, 20).padding(.vertical, 12)
            .overlay(Rectangle().fill(.white.opacity(0.08)).frame(height: 1), alignment: .bottom)

            ScrollView {
                LazyVStack(spacing: 0) {
                    ForEach(Array(up.enumerated()), id: \.element.id) { idx, t in
                        Button { coordinator.playFromQueue(idx) } label: {
                            HStack(spacing: 12) {
                                Text("\(idx + 1)").font(.system(size: 12, design: .monospaced))
                                    .foregroundStyle(.white.opacity(0.4)).frame(width: 24, alignment: .trailing)
                                PCArtwork(name: t.title, size: 38, radius: 6)
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(t.title).font(.system(size: 14, weight: .medium)).foregroundStyle(.white).lineLimit(1)
                                    Text(t.artist).font(.system(size: 12)).foregroundStyle(.white.opacity(0.55)).lineLimit(1)
                                }
                                Spacer(minLength: 0)
                            }
                            .padding(.horizontal, 20).padding(.vertical, 8)
                        }
                        .buttonStyle(.plain)
                        .pcTrackContextMenu(t, coordinator: coordinator)
                    }
                }
                .padding(.vertical, 8)
            }
        }
        .preferredColorScheme(.dark)
    }
}
