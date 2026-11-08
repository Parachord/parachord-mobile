import CarPlay
import Observation
import Shared
import UIKit

// MARK: - CarPlay Now Playing: buttons, Up Next and alerts
//
// `CPNowPlayingTemplate.shared` draws the track, the transport and the
// scrubber from `MPNowPlayingInfoCenter` (see `NowPlayingPublisher`); this
// class adds what's Parachord's own, while the CarPlay scene is connected:
//
// - The buttons Android Auto shows (`carNowPlayingButtons`, shared): the heart
//   adds the playing track to the collection or removes it, and Spinoff starts
//   a spinoff from it or ends the running one. Spinoff uses the phone's
//   Spinoff icon (`sparkles`), cut out of a filled disc while a spinoff runs;
//   it's hidden when the track has nothing similar, and a tap while the pool is
//   loading is ignored. The buttons follow the phone: a heart tapped there
//   updates the car too.
// - Up Next lists the queue (title, artist, artwork); a tap plays that track,
//   as the phone's queue does, and returns to Now Playing. While a spinoff or a
//   Listen Along runs, the queue waits underneath: it's listed as "Your Queue"
//   with its rows disabled, as the phone dims them.
// - A playback notice that needs the driver (a track that can't play here,
//   "Open Spotify on your phone…", "Open Parachord on your phone…") shows as
//   an alert with OK, one at a time: a newer notice replaces the alert on
//   screen. "Playing via X" stays on the phone (`Notice.showInCar`).

@MainActor
final class CarNowPlaying: NSObject, CPNowPlayingTemplateObserver {
    private weak var interfaceController: CPInterfaceController?
    private let container = IosContainer.companion.shared
    private var coordinator: QueuePlaybackCoordinator?
    private var running = false

    /// The collection state of the playing track, watched per title + artist.
    private var lovedKey: String?
    private var lovedWatch: Cancellable?
    private var loved: Bool?
    /// The buttons last handed to the template, so an unrelated change
    /// doesn't redraw them.
    private var shownButtons: [CarNowPlayingButton]?

    /// The Up Next list while it's pushed, and the rows it shows.
    private weak var upNextList: CPListTemplate?
    private var upNextSignature: [String] = []
    private var upNextArtwork: Task<Void, Never>?

    /// The notice the car has already seen (shown or skipped).
    private var seenNoticeId: UUID?

    init(interfaceController: CPInterfaceController) {
        self.interfaceController = interfaceController
    }

    /// Set up the template now, then follow playback once the bootstrap has
    /// made the coordinator (a CarPlay-only launch may still be loading it).
    func start() async {
        let template = CPNowPlayingTemplate.shared
        template.isUpNextButtonEnabled = true
        template.upNextTitle = "Up Next"
        template.add(self)
        running = true

        await AppBootstrap.shared.start()
        guard running else { return }
        let c = AppPlayback.shared.coordinator
        coordinator = c
        // A notice from before the car connected isn't news to the driver.
        seenNoticeId = c.notice?.id
        observe()
    }

    /// The car disconnected.
    func stop() {
        running = false
        CPNowPlayingTemplate.shared.remove(self)
        lovedWatch?.cancel()
        lovedWatch = nil
        lovedKey = nil
        loved = nil
        upNextArtwork?.cancel()
        upNextArtwork = nil
    }

    // MARK: Following playback

    private struct State {
        let track: Track?
        let spinoffMode: Bool
        let spinoffAvailable: Bool?
        let notice: QueuePlaybackCoordinator.Notice?
        let queue: [Track]
        let suspended: Bool
    }

    /// Read what the car shows, and read it again on the next change.
    private func observe() {
        guard running, let c = coordinator else { return }
        let state = withObservationTracking {
            let listeningAlong = c.playbackContext?.type == ListenAlongController.listenAlongContext
            return State(
                track: c.currentTrack,
                spinoffMode: c.spinoffMode,
                spinoffAvailable: c.spinoffAvailable,
                notice: c.notice,
                queue: listeningAlong ? ListenAlongController.shared.suspendedQueue : c.upNext,
                suspended: listeningAlong || c.spinoffMode
            )
        } onChange: {
            Task { @MainActor [weak self] in self?.observe() }
        }
        watchLoved(state.track)
        updateButtons(state)
        refreshUpNextIfShown(state)
        showNoticeIfNew(state.notice)
    }

    private var currentState: State? {
        guard let c = coordinator else { return nil }
        let listeningAlong = c.playbackContext?.type == ListenAlongController.listenAlongContext
        return State(
            track: c.currentTrack, spinoffMode: c.spinoffMode, spinoffAvailable: c.spinoffAvailable,
            notice: c.notice,
            queue: listeningAlong ? ListenAlongController.shared.suspendedQueue : c.upNext,
            suspended: listeningAlong || c.spinoffMode
        )
    }

    // MARK: Buttons

    private func watchLoved(_ track: Track?) {
        let key = track.map { "\($0.title)\u{1F}\($0.artist)" }
        guard key != lovedKey else { return }
        lovedKey = key
        lovedWatch?.cancel()
        lovedWatch = nil
        loved = nil
        guard let track, let key else { return }
        lovedWatch = container.watchTrackInCollection(title: track.title, artist: track.artist) { [weak self] yes in
            let value = yes.boolValue
            Task { @MainActor [weak self] in
                guard let self, self.running, self.lovedKey == key else { return }
                self.loved = value
                if let state = self.currentState { self.updateButtons(state) }
            }
        }
    }

    private func updateButtons(_ state: State) {
        // Unknown until the collection answers: no buttons rather than a heart
        // that might be wrong.
        let heart: Bool? = state.track == nil ? nil : loved
        let buttons = CarNowPlayingButtonsKt.carNowPlayingButtons(
            loved: heart.map { KotlinBoolean(value: $0) },
            spinoffMode: state.spinoffMode,
            spinoffAvailable: state.spinoffAvailable.map { KotlinBoolean(value: $0) }
        )
        guard buttons != shownButtons else { return }
        shownButtons = buttons
        let made: [CPNowPlayingButton] = buttons.map { button in
            switch button {
            case .love, .unlove:
                return CPNowPlayingImageButton(image: button == .unlove ? Self.heartFilled : Self.heart) { [weak self] _ in
                    MainActor.assumeIsolated { self?.toggleLove() }
                }
            default:
                return CPNowPlayingImageButton(image: button == .endSpinoff ? Self.spinoffActive : Self.spinoff) { _ in
                    MainActor.assumeIsolated {
                        // Android Auto ignores the button while the pool loads.
                        guard !SpinoffController.shared.loading else { return }
                        SpinoffController.shared.toggle()
                    }
                }
            }
        }
        CPNowPlayingTemplate.shared.updateNowPlayingButtons(made)
    }

    /// The playing track into the collection, or out of it, as the phone's
    /// heart does.
    private func toggleLove() {
        guard let track = coordinator?.currentTrack, let loved else { return }
        Task {
            if loved { try? await container.removeTrackFromCollection(track: track) }
            else { try? await container.addTrackToCollection(track: track) }
        }
    }

    // MARK: Up Next

    nonisolated func nowPlayingTemplateUpNextButtonTapped(_ nowPlayingTemplate: CPNowPlayingTemplate) {
        MainActor.assumeIsolated { showUpNext() }
    }

    nonisolated func nowPlayingTemplateAlbumArtistButtonTapped(_ nowPlayingTemplate: CPNowPlayingTemplate) {}

    private func showUpNext() {
        guard let ic = interfaceController, let state = currentState else { return }
        let list = CPListTemplate(title: state.suspended ? "Your Queue" : "Up Next", sections: [])
        fill(list, with: state)
        upNextList = list
        ic.pushTemplate(list, animated: true, completion: nil)
    }

    private func refreshUpNextIfShown(_ state: State) {
        guard let list = upNextList, let ic = interfaceController,
              ic.templates.contains(where: { $0 === list }) else {
            upNextList = nil
            return
        }
        fill(list, with: state)
    }

    /// The rows for [state]'s queue, unless they're the ones already shown.
    private func fill(_ list: CPListTemplate, with state: State) {
        let shown = Array(state.queue.prefix(CPListTemplate.maximumItemCount))
        let signature = [state.suspended ? "suspended" : "live"] + shown.map(\.id)
        if list.sections.count > 0, signature == upNextSignature { return }
        upNextSignature = signature

        var rows: [CPListItem] = []
        var artwork: [(CPListItem, [String])] = []
        for (index, track) in shown.enumerated() {
            let row = CPListItem(text: track.title, detailText: track.artist, image: UIImage(systemName: "music.note"))
            if state.suspended {
                // Waiting underneath a spinoff / Listen Along: not playable from
                // here, as on the phone.
                row.isEnabled = false
            } else {
                row.handler = { [weak self] _, completion in
                    MainActor.assumeIsolated { self?.playFromQueue(index) }
                    completion()
                }
            }
            rows.append(row)
            let urls = CarArtwork.candidates(for: track)
            if !urls.isEmpty { artwork.append((row, urls)) }
        }
        if rows.isEmpty {
            let empty = CPListItem(text: "Nothing up next", detailText: nil)
            empty.isEnabled = false
            rows = [empty]
        }
        list.updateSections([CPListSection(items: rows)])

        upNextArtwork?.cancel()
        upNextArtwork = Task { @MainActor in
            for (row, urls) in artwork {
                if Task.isCancelled { return }
                if let image = await CarArtwork.image(forFirstOf: urls), !Task.isCancelled { row.setImage(image) }
            }
        }
    }

    private func playFromQueue(_ index: Int) {
        guard let c = coordinator, CarPlaySession.shared.isConnected else { return }
        c.playFromQueue(index)
        if let ic = interfaceController, ic.templates.contains(where: { $0 === CPNowPlayingTemplate.shared }) {
            ic.pop(to: CPNowPlayingTemplate.shared, animated: true, completion: nil)
        }
    }

    // MARK: Alerts

    private func showNoticeIfNew(_ notice: QueuePlaybackCoordinator.Notice?) {
        guard let notice, notice.id != seenNoticeId else { return }
        seenNoticeId = notice.id
        guard notice.showInCar, let ic = interfaceController else { return }
        let id = notice.id
        let alert = CPAlertTemplate(titleVariants: [notice.message], actions: [
            CPAlertAction(title: "OK", style: .default) { [weak self] _ in
                MainActor.assumeIsolated { self?.dismissAlert(noticeId: id) }
            },
        ])
        // One at a time: a newer notice replaces the one on screen.
        if ic.presentedTemplate != nil {
            ic.dismissTemplate(animated: false) { [weak ic] _, _ in
                MainActor.assumeIsolated { ic?.presentTemplate(alert, animated: true, completion: nil) }
            }
        } else {
            ic.presentTemplate(alert, animated: true, completion: nil)
        }
    }

    private func dismissAlert(noticeId: UUID) {
        interfaceController?.dismissTemplate(animated: true, completion: nil)
        // Seen in the car: the phone's banner needn't show it again.
        if coordinator?.notice?.id == noticeId { coordinator?.notice = nil }
    }

    // MARK: Images

    // Drawn at CarPlay's button size (`CPNowPlayingButtonMaximumImageSize`) as
    // template images; CarPlay tints them.
    private static let heart = drawn("heart", inDisc: false)
    private static let heartFilled = drawn("heart.fill", inDisc: false)
    private static let spinoff = drawn("sparkles", inDisc: false)
    /// The Spinoff sparkles cut out of a filled disc, since CarPlay draws its
    /// buttons in one colour (Android Auto's `ic_auto_spinoff_active`).
    private static let spinoffActive = drawn("sparkles", inDisc: true)

    private static func drawn(_ name: String, inDisc: Bool) -> UIImage {
        let size = CPNowPlayingButtonMaximumImageSize
        let format = UIGraphicsImageRendererFormat()
        format.scale = max(CarArtwork.displayScale, 2)
        let config = UIImage.SymbolConfiguration(pointSize: 64, weight: inDisc ? .semibold : .regular)
        guard let glyph = UIImage(systemName: name, withConfiguration: config)?
            .withTintColor(.black, renderingMode: .alwaysOriginal) else { return UIImage() }
        let image = UIGraphicsImageRenderer(size: size, format: format).image { _ in
            let side = min(size.width, size.height)
            // The glyph fits a box this share of the canvas, centred.
            let box = side * (inDisc ? 0.62 : 0.9)
            let ratio = min(box / glyph.size.width, box / glyph.size.height)
            let fitted = CGSize(width: glyph.size.width * ratio, height: glyph.size.height * ratio)
            let rect = CGRect(x: (size.width - fitted.width) / 2, y: (size.height - fitted.height) / 2,
                              width: fitted.width, height: fitted.height)
            if inDisc {
                UIColor.black.setFill()
                UIBezierPath(ovalIn: CGRect(x: (size.width - side) / 2, y: (size.height - side) / 2,
                                            width: side, height: side)).fill()
                glyph.draw(in: rect, blendMode: .destinationOut, alpha: 1)
            } else {
                glyph.draw(in: rect)
            }
        }
        return image.withRenderingMode(.alwaysTemplate)
    }
}
