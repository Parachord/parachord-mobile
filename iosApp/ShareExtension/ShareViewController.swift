import UIKit
import UniformTypeIdentifiers

/// Parachord Share Extension (#17). Lets the user Share a Spotify / Apple Music
/// link from any app (Spotify, Safari, Messages) into Parachord. It extracts the
/// URL, hands it to the host app via `parachord://external?url=<encoded>`, and
/// dismisses — the host classifies + plays/navigates (IosContainer.resolveExternalLink).
///
/// No compose UI: this is a headless pass-through (extract → open host → done),
/// so it doesn't link the shared KMP framework. Host filtering (Spotify/Apple
/// only) happens here; the broad web-URL activation rule keeps the predicate simple.
final class ShareViewController: UIViewController {

    override func viewDidLoad() {
        super.viewDidLoad()
        extractSharedURL { [weak self] url in
            DispatchQueue.main.async {
                if let url = url, Self.isSupported(url) {
                    self?.openInHostApp(url)
                }
                self?.extensionContext?.completeRequest(returningItems: nil, completionHandler: nil)
            }
        }
    }

    // Forward any web URL (or spotify: scheme) and let the HOST decide via its
    // resolver registry — so a new resolver "just works" without an extension
    // change (#281). Host-scoping the share sheet via an NSExtensionActivationRule
    // predicate (vs. the current broad web-URL rule) is a tracked follow-up.
    private static func isSupported(_ url: URL) -> Bool {
        if url.scheme == "spotify" { return true }
        let s = url.scheme?.lowercased()
        return s == "http" || s == "https"
    }

    /// Pull the first URL out of the shared item — a `public.url` attachment, or a
    /// URL embedded in shared `public.plain-text` (some apps share the link as text).
    private func extractSharedURL(_ completion: @escaping (URL?) -> Void) {
        guard let items = extensionContext?.inputItems as? [NSExtensionItem] else { completion(nil); return }
        let urlType = UTType.url.identifier
        let textType = UTType.plainText.identifier
        let attachments = items.compactMap { $0.attachments }.flatMap { $0 }

        if let urlAtt = attachments.first(where: { $0.hasItemConformingToTypeIdentifier(urlType) }) {
            urlAtt.loadItem(forTypeIdentifier: urlType, options: nil) { item, _ in
                completion((item as? URL) ?? (item as? NSURL) as URL?)
            }
            return
        }
        if let textAtt = attachments.first(where: { $0.hasItemConformingToTypeIdentifier(textType) }) {
            textAtt.loadItem(forTypeIdentifier: textType, options: nil) { item, _ in
                completion((item as? String).flatMap { Self.firstURL(in: $0) })
            }
            return
        }
        completion(nil)
    }

    private static func firstURL(in text: String) -> URL? {
        let detector = try? NSDataDetector(types: NSTextCheckingResult.CheckingType.link.rawValue)
        return detector?.firstMatch(in: text, range: NSRange(text.startIndex..., in: text))?.url
    }

    /// Build `parachord://external?url=<percent-encoded original URL>` and open it.
    /// Share extensions can't use `UIApplication.shared`; walk the responder chain.
    private func openInHostApp(_ url: URL) {
        let encoded = url.absoluteString.addingPercentEncoding(withAllowedCharacters: .alphanumerics) ?? url.absoluteString
        guard let deeplink = URL(string: "parachord://external?url=\(encoded)") else { return }
        var responder: UIResponder? = self
        while let r = responder {
            if let app = r as? UIApplication {
                app.open(deeplink, options: [:], completionHandler: nil)
                return
            }
            responder = r.next
        }
    }
}
