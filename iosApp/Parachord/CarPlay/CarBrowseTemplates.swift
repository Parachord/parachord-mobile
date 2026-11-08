import CarPlay
import Shared
import UIKit

// MARK: - CarPlay browse lists
//
// Renders the shared car browse tree (`CarBrowse` in shared/commonMain, the
// tree Android Auto shows) as `CPListTemplate`s. The tree decides the rows,
// their order and caps (500 playlist tracks, 100 loved songs) and what a tap
// plays; this file only draws it:
//
// - A browsable row pushes the list of its children.
// - A playable row asks `carPlayRequest` what to play, starts it on the app's
//   one coordinator (`AppPlayback.shared`) and shows `CPNowPlayingTemplate`.
//   A stale id (null request) plays nothing and pushes nothing.
// - Rows beyond `CPListTemplate.maximumItemCount` (the car's own limit) are
//   dropped. An empty list shows one disabled "Nothing here yet" row.
// - Artwork loads after the list is shown (`CarArtwork`), at most a few at a
//   time, and stops when the list is popped.

@MainActor
final class CarBrowseTemplates: NSObject, CPInterfaceControllerDelegate {
    private weak var interfaceController: CPInterfaceController?
    private let container = IosContainer.companion.shared
    /// Artwork loads per list, cancelled once that list leaves the stack.
    private var artworkTasks: [ObjectIdentifier: Task<Void, Never>] = [:]

    private static let maxArtworkLoads = 4

    init(interfaceController: CPInterfaceController) {
        self.interfaceController = interfaceController
    }

    /// The list of [parentId]'s children, titled [title].
    func list(for parentId: String, title: String) async -> CPListTemplate {
        let children: [CarItem]
        do {
            children = try await container.carChildren(parentId: parentId)
        } catch {
            NSLog("CARPLAY children(\(parentId)) failed: \(error)")
            children = []
        }
        let limit = CPListTemplate.maximumItemCount
        let shown = Array(children.prefix(limit))
        if children.count > shown.count {
            NSLog("CARPLAY \(parentId): showing \(shown.count) of \(children.count) rows (car limit \(limit))")
        }

        var rows: [CPListItem] = []
        var artwork: [(CPListItem, CarItem)] = []
        for item in shown {
            let row = makeRow(item, parentId: parentId, listTitle: title)
            rows.append(row)
            if item.kind == .playlist || item.kind == .track { artwork.append((row, item)) }
        }
        if rows.isEmpty {
            let empty = CPListItem(text: "Nothing here yet", detailText: nil)
            empty.isEnabled = false
            rows = [empty]
        }

        let template = CPListTemplate(title: title, sections: [CPListSection(items: rows)])
        loadArtwork(artwork, for: template)
        return template
    }

    /// Stop every artwork load (the car disconnected).
    func cancelAll() {
        artworkTasks.values.forEach { $0.cancel() }
        artworkTasks.removeAll()
    }

    // MARK: Rows

    private func makeRow(_ item: CarItem, parentId: String, listTitle: String) -> CPListItem {
        let row = CPListItem(text: item.title, detailText: item.subtitle,
                             image: CarArtwork.placeholder(for: item))
        if item.isBrowsable { row.accessoryType = .disclosureIndicator }
        row.handler = { [weak self] _, completion in
            Task { @MainActor in
                if item.isBrowsable {
                    await self?.open(item)
                } else if item.isPlayable {
                    await self?.play(item, parentId: parentId, listTitle: listTitle)
                }
                completion()
            }
        }
        return row
    }

    private func open(_ item: CarItem) async {
        let child = await list(for: item.id, title: item.title)
        interfaceController?.pushTemplate(child, animated: true, completion: nil)
    }

    private func play(_ item: CarItem, parentId: String, listTitle: String) async {
        // A CarPlay-only launch may still be loading the plugin host; nothing
        // resolves without it.
        await AppBootstrap.shared.start()
        let request: CarPlayRequest?
        do {
            request = try await container.carPlayRequest(id: item.id)
        } catch {
            NSLog("CARPLAY playRequest(\(item.id)) failed: \(error)")
            request = nil
        }
        guard let request else {
            NSLog("CARPLAY nothing to play for \(item.id)")
            return
        }
        let context = container.carPlaybackContext(parentId: parentId, title: listTitle)
        AppPlayback.shared.coordinator.setQueue(
            request.tracks,
            startIndex: Int(request.startIndex),
            context: context,
            shuffle: request.shuffle?.boolValue
        )
        showNowPlaying()
    }

    private func showNowPlaying() {
        guard let ic = interfaceController else { return }
        let nowPlaying = CPNowPlayingTemplate.shared
        if ic.topTemplate === nowPlaying { return }
        ic.pushTemplate(nowPlaying, animated: true, completion: nil)
    }

    // MARK: Artwork

    private func loadArtwork(_ rows: [(CPListItem, CarItem)], for template: CPListTemplate) {
        guard !rows.isEmpty else { return }
        let key = ObjectIdentifier(template)
        // Read the candidates now: the resolver-cache fallback is main-actor state.
        let jobs = rows.map { ($0.0, CarArtwork.candidates(for: $0.1)) }.filter { !$0.1.isEmpty }
        artworkTasks[key] = Task { @MainActor [weak self] in
            await withTaskGroup(of: Void.self) { group in
                var running = 0
                for (row, urls) in jobs {
                    if Task.isCancelled { break }
                    if running >= Self.maxArtworkLoads {
                        await group.next()
                        running -= 1
                    }
                    group.addTask { @MainActor in
                        if let image = await CarArtwork.image(forFirstOf: urls), !Task.isCancelled {
                            row.setImage(image)
                        }
                    }
                    running += 1
                }
            }
            self?.artworkTasks[key] = nil
        }
    }

    // MARK: CPInterfaceControllerDelegate

    nonisolated func templateDidDisappear(_ aTemplate: CPTemplate, animated: Bool) {
        MainActor.assumeIsolated {
            // Covered by a pushed list it's still on the stack; popped, it isn't.
            guard let ic = interfaceController,
                  !ic.templates.contains(where: { $0 === aTemplate }) else { return }
            let key = ObjectIdentifier(aTemplate)
            artworkTasks[key]?.cancel()
            artworkTasks[key] = nil
        }
    }
}
