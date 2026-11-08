import CarPlay
import Shared
import UIKit

// MARK: - CarPlay scene
//
// Declared in Info.plist (`UIApplicationSceneManifest` → `UISceneConfigurations`
// → `CPTemplateApplicationSceneSessionRoleApplication`). CarPlay can connect
// with the phone locked and no phone window scene, so everything playback
// needs comes from `AppBootstrap`, never from a phone view.
//
// The root is one list with the shared browse tree's three rows (Playlists,
// Loved Songs, Collection Radio), the same tree Android Auto shows
// (`CarBrowse`, rendered by `CarBrowseTemplates`). `CarNowPlaying` adds the
// heart, Spinoff, Up Next and playback alerts to the Now Playing screen.

final class CarPlaySceneDelegate: UIResponder, CPTemplateApplicationSceneDelegate {
    private var interfaceController: CPInterfaceController?
    private var browse: CarBrowseTemplates?
    private var nowPlaying: CarNowPlaying?

    func templateApplicationScene(_ templateApplicationScene: CPTemplateApplicationScene,
                                  didConnect interfaceController: CPInterfaceController) {
        MainActor.assumeIsolated {
            self.interfaceController = interfaceController
            CarPlaySession.shared.connected()
            CarArtwork.displayScale = interfaceController.carTraitCollection.displayScale

            let browse = CarBrowseTemplates(interfaceController: interfaceController)
            self.browse = browse
            interfaceController.delegate = browse

            // Now Playing's buttons, Up Next and alerts follow the coordinator
            // once the bootstrap (which `start()` runs) has made it.
            let nowPlaying = CarNowPlaying(interfaceController: interfaceController)
            self.nowPlaying = nowPlaying
            browse.onTemplateDidDisappear = { [weak nowPlaying] template in
                nowPlaying?.templateDidDisappear(template)
            }
            Task { @MainActor in await nowPlaying.start() }
            // The root is read from the database only, so it doesn't wait for
            // the plugin host; a tap awaits the bootstrap before it plays.
            Task { @MainActor [weak self] in
                let root = await browse.list(for: CarIds.shared.ROOT, title: "Parachord")
                guard let self, self.interfaceController === interfaceController else { return }
                interfaceController.setRootTemplate(root, animated: false, completion: nil)
            }
        }
    }

    func templateApplicationScene(_ templateApplicationScene: CPTemplateApplicationScene,
                                  didDisconnectInterfaceController interfaceController: CPInterfaceController) {
        MainActor.assumeIsolated {
            CarPlaySession.shared.disconnected()
            browse?.cancelAll()
            browse = nil
            nowPlaying?.stop()
            nowPlaying = nil
            self.interfaceController = nil
        }
    }
}
