import SwiftUI
import Shared
import AVKit

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
    /// Same nav path as `onArtist`, but opens the artist's On Tour tab (#201).
    var onArtistOnTour: (String) -> Void = { _ in }
    /// Navigate to the queue's source page (album / playlist / artist) — #209.
    var onNavigateToContext: (PlaybackContext) -> Void = { _ in }

    @State private var showQueue = false
    /// Whether the currently-playing artist has upcoming shows near the user's
    /// saved concert location (#201). Drives the teal on-tour dot. Recomputed on
    /// every track/artist change; reset to false while the check is in flight so
    /// a stale dot never lingers from the previous artist (Android parity).
    @State private var isOnTour = false
    @State private var dragY: CGFloat = 0
    /// Observed so the Next button reacts to the friend moving on while listening along.
    @State private var listenAlong = ListenAlongController.shared
    /// Observed so the Spinoff button shows a spinner while the pool is fetching (#231).
    @State private var spinoff = SpinoffController.shared
    /// Observed so the hero art re-renders the moment the current track resolves
    /// and its playing-source artwork (Spotify / Apple Music / SoundCloud) lands.
    /// Non-private so the synthesized memberwise init stays accessible to RootView.
    var resolverCache = IosTrackResolverCache.shared
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
                                seed: track?.title ?? "P", size: nil, radius: 14,
                                resolving: track.map { pcTrackResolving(artist: $0.artist, title: $0.title, album: $0.album) } ?? false)
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
        // Resolve the current track so its playing-source artwork populates the
        // hero (Android always shows art from the service it's playing from). On
        // a cache hit this is a no-op; pcTrackArt then reads the resolved art.
        .task(id: track?.id) {
            if let t = track {
                resolverCache.resolve(
                    ResolveRequest(artist: t.artist, title: t.title, album: t.album), order: 0)
            }
        }
        // On-tour check for the current artist (#201). `.task(id:)` cancels the
        // previous check when the artist changes; reset to false first so the dot
        // doesn't linger from the last track while the new check runs.
        .task(id: track?.artist) {
            isOnTour = false
            guard let a = track?.artist, !a.isEmpty else { return }
            let result = (try? await IosContainer.companion.shared.checkOnTour(artistName: a))?.boolValue ?? false
            if !Task.isCancelled { isOnTour = result }
        }
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
                                 onClose: { withAnimation(.spring(duration: 0.32)) { showQueue = false } },
                                 onNavigateToContext: { ctx in
                                     withAnimation(.spring(duration: 0.32)) { showQueue = false }
                                     onNavigateToContext(ctx)
                                 })
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
                HStack(spacing: 6) {
                    Button {
                        if let a = track?.artist, !a.isEmpty { onArtist(a) }
                    } label: {
                        Text(track?.artist ?? "—").font(.system(size: 16, weight: .medium)).foregroundStyle(PC.accentSoft)
                    }
                    .buttonStyle(.plain)
                    .disabled((track?.artist ?? "").isEmpty)
                    // Teal on-tour dot (#201) — tapping opens the On Tour tab.
                    if isOnTour, let a = track?.artist, !a.isEmpty {
                        Circle().fill(PC.onTour).frame(width: 8, height: 8)
                            .onTapGesture { onArtistOnTour(a) }
                    }
                }
                if let album = track?.album, !album.isEmpty {
                    Text("From \(album)").font(.system(size: 13)).foregroundStyle(.white.opacity(0.55)).lineLimit(1)
                }
            }
            Spacer(minLength: 0)
            if let t = track {
                PCHeartButton(track: t, iconSize: 18, tap: 36)
                    .background(.white.opacity(0.14), in: Circle())
            }
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
        // While listening along, Next catches up to the friend's current song when
        // they're ahead, and is DISABLED when you're in sync (nothing to advance to).
        let inListenAlong = coordinator.playbackContext?.type == "listen-along"
        let canNext = !inListenAlong || listenAlong.friendIsAhead
        return HStack {
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
            Button {
                if inListenAlong { listenAlong.advanceToFriend() } else { coordinator.skipNext() }
            } label: {
                Image(systemName: "forward.fill").font(.system(size: 26))
                    .foregroundStyle(.white.opacity(canNext ? 0.88 : 0.3))
            }
            .disabled(!canNext)
            Spacer()
            Button { coordinator.cycleRepeatMode() } label: {
                Image(systemName: coordinator.repeatMode == .one ? "repeat.1" : "repeat")
                    .font(.system(size: 20))
                    .foregroundStyle(coordinator.repeatMode == .off ? .white.opacity(0.55) : PC.accent)
            }
        }
        .buttonStyle(.plain)
        .padding(.vertical, 4)
    }

    private var bottomActions: some View {
        HStack(spacing: 0) {
            PCRoutePicker()
                .frame(width: 30, height: 30)
                .frame(maxWidth: .infinity)
            if spinoff.loading {
                VStack(spacing: 4) {
                    ProgressView().controlSize(.small).tint(.white)
                    Text("Spinoff").font(.system(size: 11))
                }.frame(maxWidth: .infinity)
            } else {
                // Spinoff (#231): toggle a Last.fm-similar radio off the current
                // track. Active = purple; dimmed + disabled when the track has no
                // similar tracks (Android: enabled = !loading && available != false).
                // The current song keeps playing — the pool starts when it ends.
                Button { spinoff.toggle() } label: {
                    VStack(spacing: 4) {
                        Image(systemName: "sparkles").font(.system(size: 21))
                        Text("Spinoff").font(.system(size: 11))
                    }.frame(maxWidth: .infinity)
                }
                .buttonStyle(.plain)
                .foregroundStyle(spinoffTint)
                .disabled(coordinator.currentTrack == nil || coordinator.spinoffAvailable == false)
            }
            queueActionBtn
            overflowMenu
        }
        .foregroundStyle(.white.opacity(0.65))
        .alert(
            "No similar tracks",
            isPresented: Binding(get: { spinoff.lastEmptySeed != nil },
                                 set: { if !$0 { spinoff.lastEmptySeed = nil } })
        ) {
            Button("OK", role: .cancel) {}
        } message: {
            Text("Couldn't find tracks similar to “\(spinoff.lastEmptySeed ?? "")”.")
        }
    }

    /// Now-Playing overflow (•••) — the track actions for the current track,
    /// surfaced as a tap menu (the same set as the long-press track context
    /// menu, #204). Was a no-op button before.
    @ViewBuilder
    private var overflowMenu: some View {
        Menu {
            if let t = coordinator.currentTrack {
                Button { coordinator.playNext(t) } label: { Label("Play Next", systemImage: "text.line.first.and.arrowtriangle.forward") }
                Button { coordinator.addToQueue(t) } label: { Label("Add to Queue", systemImage: "text.append") }
                PCAddToPlaylistMenu(track: t)
                if !t.artist.isEmpty {
                    Button { onArtist(t.artist) } label: { Label("Go to Artist", systemImage: "music.mic") }
                }
                if let album = t.album, !album.isEmpty {
                    Button { onNavigateToContext(PlaybackContext(type: "album", name: album, id: t.artist)) } label: {
                        Label("Go to Album", systemImage: "square.stack")
                    }
                }
                Divider()
                ShareLink(item: "\(t.title) — \(t.artist)") { Label("Share", systemImage: "square.and.arrow.up") }
                PCCollectionToggleButton(target: .track(t))
            }
        } label: {
            Image(systemName: "ellipsis").font(.system(size: 21)).frame(maxWidth: .infinity, minHeight: 30)
        }
        .buttonStyle(.plain)
    }

    /// Queue action whose count badge anchors to the ICON glyph's top-right —
    /// the old badge sat on the full-width cell, floating it to the far edge
    /// instead of over the icon.
    private var queueActionBtn: some View {
        Button { withAnimation(.spring(duration: 0.32)) { showQueue = true } } label: {
            VStack(spacing: 4) {
                Image(systemName: "list.bullet").font(.system(size: 21))
                    .overlay(alignment: .topTrailing) {
                        // Desktop parity (app.js:55178): the count badge is GRAY
                        // (#6B7280) while spun off / listening along, purple
                        // otherwise. Counts the queue that RESUMES — intact upNext
                        // during spinoff, the suspended queue during listen-along
                        // (the active queue there is just the friend's track).
                        let listeningAlong = coordinator.playbackContext?.type == "listen-along"
                        let suspended = coordinator.spinoffMode || listeningAlong
                        let n = listeningAlong ? listenAlong.suspendedQueue.count : coordinator.upNext.count
                        if n > 0 {
                            Text("\(n)").font(.system(size: 10, weight: .bold))
                                .foregroundStyle(suspended ? .white : .black)
                                .padding(.horizontal, 5).padding(.vertical, 1)
                                .background(suspended ? Color(uiColor: UIColor(hex: 0x6B7280)) : PC.accentSoft, in: Capsule())
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

    // Wraps UIKit's AVRoutePickerView — the system AirPlay / output-route
    // picker (#221). Replaces the previously-dead "airplayaudio" button. The
    // glyph + active state are system-drawn; we just tint it to match.
    private struct PCRoutePicker: UIViewRepresentable {
        func makeUIView(context: Context) -> AVRoutePickerView {
            let v = AVRoutePickerView()
            v.tintColor = UIColor.white.withAlphaComponent(0.65)
            v.activeTintColor = UIColor(PC.accent)
            v.prioritizesVideoDevices = false
            v.backgroundColor = .clear
            return v
        }
        func updateUIView(_ uiView: AVRoutePickerView, context: Context) {}
    }

    /// ✨ Spinoff button tint: active = purple (#C084FC), unavailable = very dim,
    /// otherwise the normal control gray (#231 / Android parity).
    private var spinoffTint: Color {
        if coordinator.spinoffMode { return Color(uiColor: UIColor(hex: 0xC084FC)) }
        if coordinator.spinoffAvailable == false { return .white.opacity(0.25) }
        return .white.opacity(0.65)
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

    @ViewBuilder private var upNextPeek: some View {
        // While listening along, the bottom peek shows "Listening along with X" in
        // green and links to the friend's profile (in addition to the queue's
        // context banner). Otherwise it's the normal Up-Next peek that opens the queue.
        if let ctx = coordinator.playbackContext, ctx.type == "listen-along" {
            let green = Color(uiColor: UIColor(hex: 0x34D399))
            Button { onNavigateToContext(ctx) } label: {
                HStack(spacing: 10) {
                    Image(systemName: "headphones").font(.system(size: 13)).foregroundStyle(green)
                    Text("LISTENING ALONG WITH \(ctx.name.uppercased())")
                        .font(.system(size: 11, weight: .semibold)).tracking(1).foregroundStyle(green).lineLimit(1)
                    Spacer()
                    Image(systemName: "chevron.right").font(.system(size: 11, weight: .semibold)).foregroundStyle(green.opacity(0.85))
                }
                .padding(.horizontal, 22).padding(.vertical, 12)
                .frame(maxWidth: .infinity)
                .background(green.opacity(0.08))
                .overlay(Rectangle().fill(.white.opacity(0.1)).frame(height: 0.5), alignment: .top)
            }
            .buttonStyle(.plain)
        } else if let ctx = coordinator.playbackContext, ctx.type == "spinoff" {
            // While spun off, the peek highlights the STATION SEED ("Spinoff from
            // X") in purple — NOT the next pool track (#231). Tap opens the queue,
            // which shows the user's suspended "YOUR QUEUE".
            let purple = Color(uiColor: UIColor(hex: 0xC084FC))
            Button { withAnimation(.spring(duration: 0.32)) { showQueue = true } } label: {
                HStack(spacing: 10) {
                    Image(systemName: "sparkles").font(.system(size: 13)).foregroundStyle(purple)
                    Text(ctx.name.uppercased())
                        .font(.system(size: 11, weight: .semibold)).tracking(1).foregroundStyle(purple).lineLimit(1)
                    Spacer()
                    Image(systemName: "chevron.up").font(.system(size: 13, weight: .semibold)).foregroundStyle(purple.opacity(0.85))
                }
                .padding(.horizontal, 22).padding(.vertical, 12)
                .frame(maxWidth: .infinity)
                .background(purple.opacity(0.08))
                .overlay(Rectangle().fill(.white.opacity(0.1)).frame(height: 0.5), alignment: .top)
            }
            .buttonStyle(.plain)
        } else {
            let next = coordinator.upNext.first
            Button { withAnimation(.spring(duration: 0.32)) { showQueue = true } } label: {
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
    /// Navigate to the queue's source page (album / playlist / artist) — #209.
    var onNavigateToContext: ((PlaybackContext) -> Void)? = nil

    /// Observed so resolver badges appear as background resolution lands.
    /// (Non-private so the memberwise init stays internal/callable.)
    var resolverCache = IosTrackResolverCache.shared
    /// Observed so the panel flips to the suspended "YOUR QUEUE" view while listening along.
    var listenAlong = ListenAlongController.shared
    @State var dragY: CGFloat = 0
    private var npBg: Color { Color(uiColor: UIColor(hex: 0x1e1e20)) }

    var body: some View {
        // While listening along, the queue is SUSPENDED: show the user's saved
        // queue dimmed as "YOUR QUEUE" (it resumes on disconnect), not editable.
        // SUSPENDED while listening along OR during a spinoff (#231): show the
        // user's queue dimmed as "YOUR QUEUE", non-interactive — it resumes when
        // the spinoff pool exhausts / you disconnect. Spinoff never modifies the
        // queue (the pool is separate), so it reads the live upNext; Listen Along
        // replaced the queue with the friend's track, so it reads its saved snapshot.
        let listeningAlong = coordinator.playbackContext?.type == "listen-along"
        let suspended = listeningAlong || coordinator.spinoffMode
        let up = listeningAlong ? listenAlong.suspendedQueue : coordinator.upNext
        VStack(spacing: 0) {
            header(count: up.count, suspended: suspended)
            if suspended {
                // Suspended preview: dimmed, non-interactive (no reorder/remove/tap)
                // — it's paused and resumes when you disconnect.
                List {
                    ForEach(Array(up.enumerated()), id: \.element.id) { idx, t in
                        queueRow(idx: idx, t: t, suspended: true)
                            .listRowBackground(Color.clear)
                            .listRowSeparator(.hidden)
                            .listRowInsets(EdgeInsets())
                            .opacity(0.45)
                            .allowsHitTesting(false)
                            .onAppear {
                                resolverCache.resolve(
                                    ResolveRequest(artist: t.artist, title: t.title, album: t.album), order: idx)
                            }
                    }
                }
                .listStyle(.plain)
                .scrollContentBackground(.hidden)
                .environment(\.defaultMinListRowHeight, 0)
                .padding(.vertical, 8)
            } else {
            // A styled `List` (not a LazyVStack) so `.onMove` drag-reorder + a
            // swipe-to-delete back the shared QueueManager (#220). Chrome stripped
            // to match the dark panel: plain style, clear rows, no separators/insets,
            // hidden scroll background.
            List {
                ForEach(Array(up.enumerated()), id: \.element.id) { idx, t in
                    queueRow(idx: idx, t: t)
                        .listRowBackground(Color.clear)
                        .listRowSeparator(.hidden)
                        .listRowInsets(EdgeInsets())
                        // Visibility-scoped resolution (mirrors the album/playlist
                        // tracklists): resolve each row as it appears so badges fill
                        // in and playback starts instantly from cache.
                        .onAppear {
                            resolverCache.resolve(
                                ResolveRequest(artist: t.artist, title: t.title, album: t.album), order: idx)
                        }
                        .pcTrackContextMenu(t, coordinator: coordinator,
                                            onRemoveFromQueue: { withAnimation(.spring(response: 0.34, dampingFraction: 0.86)) { coordinator.removeFromQueue(idx) } })
                }
                .onMove { source, dest in
                    guard let from = source.first else { return }
                    // SwiftUI's `toOffset` is the pre-removal insertion index;
                    // QueueManager.moveInQueue wants the post-removal index.
                    coordinator.moveInQueue(from: from, to: dest > from ? dest - 1 : dest)
                }
                // No `.onDelete`: in permanent edit mode it renders an oversized,
                // non-resizable system delete (−) circle on every row. Removal stays
                // on the long-press "Remove from Queue" context action (below),
                // leaving just the reorder handle (Apple-Music style). #220
            }
            .listStyle(.plain)
            .scrollContentBackground(.hidden)
            .environment(\.defaultMinListRowHeight, 0)
            // The queue is a dedicated editing surface — always in edit mode so the
            // drag-reorder handles + delete controls are persistently visible (no
            // Edit/Done toggle; Apple-Music-style). #220
            .environment(\.editMode, .constant(.active))
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

    /// One up-next row. Tapping a mid-queue track removes the tracks above it
    /// (they're "played") and the queue shifts up (Android parity).
    @ViewBuilder private func queueRow(idx: Int, t: Track, suspended: Bool = false) -> some View {
        Button { withAnimation(.spring(response: 0.38, dampingFraction: 0.86)) { coordinator.playFromQueue(idx) } } label: {
            HStack(spacing: 12) {
                // "··" instead of the track number while suspended (spinoff /
                // listen-along) — Android QueueSheet parity (#231).
                Text(suspended ? "··" : "\(idx + 1)").font(.system(size: 12, design: .monospaced))
                    .foregroundStyle(.white.opacity(0.4)).frame(width: 24, alignment: .trailing)
                pcCover(pcTrackArt(t.artworkUrl, artist: t.artist, title: t.title, album: t.album), seed: t.title, size: 38, radius: 6,
                        resolving: pcTrackResolving(artist: t.artist, title: t.title, album: t.album))
                VStack(alignment: .leading, spacing: 2) {
                    Text(t.title).font(.system(size: 14, weight: .medium))
                        .foregroundStyle(pcTrackNoMatch(artist: t.artist, title: t.title, album: t.album) ? Color.white.opacity(0.4) : .white).lineLimit(1)
                    Text(t.artist).font(.system(size: 12)).foregroundStyle(.white.opacity(0.55)).lineLimit(1)
                }
                Spacer(minLength: 8)
                if let s = resolverCache.cached(artist: t.artist, title: t.title, album: t.album), !s.isEmpty {
                    ResolverBadgeRow(sources: s, size: 16)
                }
            }
            .padding(.horizontal, 20).padding(.vertical, 8)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }

    /// "Playing from: …" queue-source line (#209). Mirrors Android's QueueSheet:
    /// type-specific label + color; album/playlist/artist link to their page.
    /// Falls back to the neutral subtitle when the queue has no source context.
    @ViewBuilder private var contextBanner: some View {
        if let ctx = coordinator.playbackContext {
            // Navigable when iOS can route to this context's source — a pushable
            // page or the Collection tab (pcContextIsNavigable is the single
            // source of truth). #209.
            let navigable = onNavigateToContext != nil && pcContextIsNavigable(ctx)
            HStack(spacing: 4) {
                Text(contextLabel(ctx)).font(.system(size: 12, weight: navigable ? .medium : .regular))
                    .foregroundStyle(contextColor(ctx)).lineLimit(1)
                if navigable {
                    Image(systemName: "chevron.right").font(.system(size: 9, weight: .semibold))
                        .foregroundStyle(contextColor(ctx).opacity(0.8))
                }
            }
            .contentShape(Rectangle())
            .onTapGesture { if navigable { onNavigateToContext?(ctx) } }
        } else {
            Text("Playing from your queue").font(.system(size: 12)).foregroundStyle(.white.opacity(0.55))
        }
    }

    private func contextLabel(_ ctx: PlaybackContext) -> String {
        switch ctx.type {
        case "listen-along": return "Listening along with \(ctx.name)"
        case "spinoff":      return ctx.name
        default:             return "Playing from: \(ctx.name)"
        }
    }
    private func contextColor(_ ctx: PlaybackContext) -> Color {
        switch ctx.type {
        case "listen-along": return Color(uiColor: UIColor(hex: 0x34D399)) // green (desktop parity)
        case "spinoff":      return Color(uiColor: UIColor(hex: 0xC084FC)) // spinoff purple
        default:             return PC.accentSoft
        }
    }

    /// Grabber + title + Clear + close. The drag-to-dismiss gesture lives HERE
    /// (not on the whole panel) so it never fights the list's scroll.
    private func header(count: Int, suspended: Bool = false) -> some View {
        VStack(spacing: 0) {
            Capsule().fill(.white.opacity(0.35)).frame(width: 36, height: 5)
                .padding(.top, 8).padding(.bottom, 6)
            HStack {
                VStack(alignment: .leading, spacing: 4) {
                    Text("\(suspended ? "YOUR QUEUE" : "UP NEXT") · \(count) TRACKS")
                        .font(.system(size: 13, weight: .semibold)).tracking(2)
                        .foregroundStyle(suspended ? .white.opacity(0.5) : PC.accentSoft)
                    contextBanner
                }
                Spacer()
                // No Clear while suspended — it's the paused queue that resumes,
                // not the active one.
                if !suspended {
                    Button("Clear") { coordinator.clearQueue() }
                        .font(.system(size: 15, weight: .medium)).foregroundStyle(.white.opacity(0.75))
                }
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
