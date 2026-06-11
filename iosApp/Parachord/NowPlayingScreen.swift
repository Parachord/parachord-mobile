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
    var artNamespace: Namespace.ID
    let onClose: () -> Void
    /// Navigate to the tapped artist's page. The shell closes Now Playing and
    /// pushes ArtistScreen on the Home stack.
    var onArtist: (String) -> Void = { _ in }

    @State private var showQueue = false
    @State private var dragY: CGFloat = 0
    /// Regular width (iPad / landscape) gets a larger art cap so it doesn't look
    /// lost in the wide column; compact (phone) stays capped so the controls
    /// clear the Up-Next peek.
    @Environment(\.horizontalSizeClass) private var hSize

    private var npBg: Color { Color(uiColor: UIColor(hex: 0x1e1e20)) }

    var body: some View {
        let track = coordinator.currentTrack
        ZStack {
            npBg.ignoresSafeArea()
            VStack(spacing: 0) {
                grabberBar
                ScrollView {
                    VStack(spacing: 16) {
                        // iPad: center the column in the tall viewport instead of
                        // leaving a big gap below the controls (phone stays
                        // top-anchored — these spacers collapse there).
                        if hSize == .regular { Spacer(minLength: 0) }
                        pcCover(track.flatMap { pcTrackArt($0.artworkUrl, artist: $0.artist, title: $0.title, album: $0.album) },
                                seed: track?.title ?? "P", size: nil, radius: 14)
                            // Cap the art so the transport, resolver deck and
                            // bottom actions stay on-screen above the Up-Next
                            // peek; larger cap on iPad so it isn't tiny in the
                            // wide column. Capped square, centered.
                            .frame(maxWidth: hSize == .regular ? 460 : 320)
                            .frame(maxWidth: .infinity)
                            .matchedGeometryEffect(id: "npArt", in: artNamespace)
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
                        if hSize == .regular { Spacer(minLength: 0) }
                    }
                    .padding(.horizontal, 24)
                    .padding(.bottom, 24)
                    .modifier(NPVCenterOnRegular(active: hSize == .regular))
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
        // Queue lives ON the Now Playing screen (not a system sheet): a scrim
        // + bottom panel revealed by tapping/swiping the Up-Next peek. The
        // ZStack is ALWAYS present so the children's transitions fire — the
        // scrim fades, the panel SLIDES up from the bottom (a conditional
        // container would have faded the whole layer in instead).
        .overlay(alignment: .bottom) {
            ZStack(alignment: .bottom) {
                if showQueue {
                    Color.black.opacity(0.4).ignoresSafeArea()
                        .transition(.opacity)
                        .onTapGesture { withAnimation(.spring(duration: 0.32)) { showQueue = false } }
                    PCQueuePanel(coordinator: coordinator,
                                 onClose: { withAnimation(.spring(duration: 0.32)) { showQueue = false } })
                        .padding(.top, 90) // scrim gap at the top
                        .transition(.move(edge: .bottom))
                }
            }
            .zIndex(20)
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
                Button {
                    if let a = track?.artist, !a.isEmpty { onArtist(a) }
                } label: {
                    Text(track?.artist ?? "—").font(.system(size: 16, weight: .medium)).foregroundStyle(PC.accentSoft)
                }
                .buttonStyle(.plain)
                .disabled((track?.artist ?? "").isEmpty)
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
            queueActionBtn
            actionBtn("ellipsis", nil) { }
        }
        .foregroundStyle(.white.opacity(0.65))
    }

    /// Queue action whose count badge anchors to the ICON glyph's top-right —
    /// the old badge sat on the full-width cell, floating it to the far edge
    /// instead of over the icon.
    private var queueActionBtn: some View {
        Button { withAnimation(.spring(duration: 0.32)) { showQueue = true } } label: {
            VStack(spacing: 4) {
                Image(systemName: "list.bullet").font(.system(size: 21))
                    .overlay(alignment: .topTrailing) {
                        let n = coordinator.upNext.count
                        if n > 0 {
                            Text("\(n)").font(.system(size: 10, weight: .bold)).foregroundStyle(.black)
                                .padding(.horizontal, 5).padding(.vertical, 1)
                                .background(PC.accentSoft, in: Capsule())
                                .fixedSize()
                                .offset(x: 11, y: -7)
                        }
                    }
                Text("Queue").font(.system(size: 11))
            }
            .frame(maxWidth: .infinity)
        }
        .buttonStyle(.plain)
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
        return Button { withAnimation(.spring(duration: 0.32)) { showQueue = true } } label: {
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
        // Swipe up on the peek also opens the queue (highPriority so it wins
        // over the sheet's swipe-DOWN-to-close drag on the root).
        .highPriorityGesture(
            DragGesture(minimumDistance: 12)
                .onEnded { v in
                    if v.translation.height < -30 {
                        withAnimation(.spring(duration: 0.32)) { showQueue = true }
                    }
                }
        )
    }
}

/// Vertically centers the Now Playing column within the scroll viewport on
/// regular width (iPad). No-op on compact (phone), where the content fills and
/// scrolls normally. `containerRelativeFrame` pins the content to the scroll
/// viewport's height so the inner Spacers can center it.
private struct NPVCenterOnRegular: ViewModifier {
    let active: Bool
    func body(content: Content) -> some View {
        if active {
            content.frame(maxWidth: .infinity).containerRelativeFrame(.vertical, alignment: .center)
        } else {
            content
        }
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
                // ONLY enabled resolvers that actually MATCHED this track. The
                // resolve pipeline only runs active (enabled) resolvers, and
                // `available` is the floor-passing matches — so we no longer
                // append the greyed "Not connected" rows for every known service.
                let order = available
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

// MARK: - Queue panel (inline on Now Playing)

struct PCQueuePanel: View {
    @Bindable var coordinator: QueuePlaybackCoordinator
    let onClose: () -> Void

    /// Observed so resolver badges appear as background resolution lands.
    /// (Non-private so the memberwise init stays internal/callable.)
    var resolverCache = IosTrackResolverCache.shared
    @State var dragY: CGFloat = 0
    private var npBg: Color { Color(uiColor: UIColor(hex: 0x1e1e20)) }

    var body: some View {
        let up = coordinator.upNext
        VStack(spacing: 0) {
            header(count: up.count)
            ScrollView {
                LazyVStack(spacing: 0) {
                    ForEach(Array(up.enumerated()), id: \.element.id) { idx, t in
                        Button { coordinator.playFromQueue(idx) } label: {
                            HStack(spacing: 12) {
                                Text("\(idx + 1)").font(.system(size: 12, design: .monospaced))
                                    .foregroundStyle(.white.opacity(0.4)).frame(width: 24, alignment: .trailing)
                                pcCover(pcTrackArt(t.artworkUrl, artist: t.artist, title: t.title, album: t.album), seed: t.title, size: 38, radius: 6)
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(t.title).font(.system(size: 14, weight: .medium)).foregroundStyle(.white).lineLimit(1)
                                    Text(t.artist).font(.system(size: 12)).foregroundStyle(.white.opacity(0.55)).lineLimit(1)
                                }
                                Spacer(minLength: 8)
                                // Resolver badges — same confidence-aware row as
                                // every other tracklist. Resolved per-row below.
                                if let s = resolverCache.cached(artist: t.artist, title: t.title, album: t.album),
                                   !s.isEmpty {
                                    ResolverBadgeRow(sources: s, size: 16)
                                }
                            }
                            .padding(.horizontal, 20).padding(.vertical, 8)
                            .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                        // Visibility-scoped resolution (mirrors the album/playlist
                        // tracklists): resolve each row as it appears so badges
                        // fill in and playback starts instantly from cache.
                        .onAppear {
                            resolverCache.resolve(
                                ResolveRequest(artist: t.artist, title: t.title, album: t.album), order: idx)
                        }
                        .pcTrackContextMenu(t, coordinator: coordinator)
                    }
                }
                .padding(.vertical, 8)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
        .background(
            npBg.clipShape(.rect(topLeadingRadius: 22, topTrailingRadius: 22))
                .ignoresSafeArea(edges: .bottom)
        )
        .offset(y: max(0, dragY))
        .preferredColorScheme(.dark)
    }

    /// Grabber + title + Clear + close. The drag-to-dismiss gesture lives HERE
    /// (not on the whole panel) so it never fights the list's scroll.
    private func header(count: Int) -> some View {
        VStack(spacing: 0) {
            Capsule().fill(.white.opacity(0.35)).frame(width: 36, height: 5)
                .padding(.top, 8).padding(.bottom, 6)
            HStack {
                VStack(alignment: .leading, spacing: 4) {
                    Text("UP NEXT · \(count) TRACKS").font(.system(size: 13, weight: .semibold)).tracking(2)
                        .foregroundStyle(PC.accentSoft)
                    Text("Playing from your queue").font(.system(size: 12)).foregroundStyle(.white.opacity(0.55))
                }
                Spacer()
                Button("Clear") { coordinator.clearQueue() }
                    .font(.system(size: 15, weight: .medium)).foregroundStyle(.white.opacity(0.75))
                Button(action: onClose) {
                    Image(systemName: "chevron.down").font(.system(size: 15, weight: .semibold))
                        .foregroundStyle(.white).frame(width: 30, height: 30)
                        .background(.white.opacity(0.14), in: Circle())
                }
                .buttonStyle(.plain).padding(.leading, 6)
            }
            .padding(.horizontal, 20).padding(.bottom, 12)
        }
        .background(npBg.clipShape(.rect(topLeadingRadius: 22, topTrailingRadius: 22)))
        .overlay(Rectangle().fill(.white.opacity(0.08)).frame(height: 1), alignment: .bottom)
        .contentShape(Rectangle())
        .gesture(
            DragGesture(minimumDistance: 8)
                .onChanged { v in if v.translation.height > 0 { dragY = v.translation.height } }
                .onEnded { v in
                    if v.translation.height > 110 { onClose() }
                    else { withAnimation(.spring(duration: 0.3)) { dragY = 0 } }
                }
        )
    }
}
