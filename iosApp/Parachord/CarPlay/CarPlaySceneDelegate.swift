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
// (`CarBrowse`, rendered by `CarBrowseTemplates`).

final class CarPlaySceneDelegate: UIResponder, CPTemplateApplicationSceneDelegate {
    private var interfaceController: CPInterfaceController?
    private var browse: CarBrowseTemplates?

    func templateApplicationScene(_ templateApplicationScene: CPTemplateApplicationScene,
                                  didConnect interfaceController: CPInterfaceController) {
        MainActor.assumeIsolated {
            self.interfaceController = interfaceController
            CarPlaySession.shared.connected()
            CarArtwork.displayScale = interfaceController.carTraitCollection.displayScale

            let browse = CarBrowseTemplates(interfaceController: interfaceController)
            self.browse = browse
            interfaceController.delegate = browse

            // The root is read from the database only, so it doesn't wait for
            // the plugin host; a tap awaits the bootstrap before it plays.
            Task { @MainActor in await AppBootstrap.shared.start() }
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
            self.interfaceController = nil
        }
    }
}
