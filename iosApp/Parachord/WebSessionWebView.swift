import Foundation
import UIKit
import WebKit
import Shared

// MARK: - The webSession host bridge's web view (#779, desktop parachord#1356)
//
// iOS's half of a web session, behind the shared engine (`WebSessionHost`,
// `WebSessionBridge`, `IosWebSessionPlatform` in shared): a hidden WKWebView
// the plugin's session drives, and the visible sign-in screen. The rules (the
// five concurrency rules, URL scoping, budgets, the capture script and its
// parsing, which cookies a request sends, what `clear()` removes) live in
// Kotlin; this file only drives WebKit. Android's equivalent is
// `AndroidWebSessionPlatform` + `WebSessionSignInActivity`.
//
// - The hidden page is 1100×800 (a 0×0 single-page app never fires its
//   requests), in the key window behind everything (an orphan view's timers
//   can be throttled), never touchable.
// - The capture is a document-start user script in every frame (the shared
//   `WebSessionScripts.captureScript`, which only runs on the cookie domains),
//   reporting to a script message handler that accepts reports only from
//   frames on the cookie domains.
// - A main-frame navigation off the cookie domains is refused; so is a popup.
// - Cookies are the default data store's (shared with the SoundCloud and
//   Bandcamp pages): Kotlin picks which ones a clear removes, never all.
// - Never log a cookie value, header value or request body.
//
// Called on the main thread (the host's dispatcher); every callback comes
// back on it.

final class WebSessionWebView: NSObject, IosWebSessionView {

    private static let width: CGFloat = 1100
    private static let height: CGFloat = 800

    private var webView: WKWebView?
    private var handler: MessageProxy?
    private var delegate: NavigationProxy?
    private var captureHandler: ((String) -> Void)?

    /// The navigation `load` waits on, and its completion.
    private var pendingNavigation: WKNavigation?
    private var pendingCompletion: ((String?, String?) -> Void)?
    /// The page replaced our navigation before it finished (an identity hop):
    /// the next finished main-frame navigation completes the load.
    private var pendingAcceptsAny = false

    var isAvailable: Bool { true }

    // MARK: The hidden page

    @MainActor private func ensureView(captureScript: String, userAgent: String?) -> WKWebView {
        if let webView { return webView }
        let config = WKWebViewConfiguration()
        config.websiteDataStore = .default()
        config.allowsInlineMediaPlayback = true
        config.mediaTypesRequiringUserActionForPlayback = []
        let proxy = MessageProxy(owner: self)
        config.userContentController.add(proxy, name: WebSessionScripts.shared.IOS_CAPTURE_HANDLER)
        config.userContentController.addUserScript(
            WKUserScript(source: captureScript, injectionTime: .atDocumentStart, forMainFrameOnly: false)
        )
        let wv = WKWebView(frame: CGRect(x: 0, y: 0, width: Self.width, height: Self.height), configuration: config)
        let nav = NavigationProxy(owner: self)
        wv.navigationDelegate = nav
        wv.uiDelegate = nav
        if let userAgent { wv.customUserAgent = userAgent }
        wv.isUserInteractionEnabled = false
        wv.alpha = 0.01
        wv.accessibilityElementsHidden = true
        #if DEBUG
        if #available(iOS 16.4, *) { wv.isInspectable = true }
        #endif
        if let window = Self.keyWindow() { window.insertSubview(wv, at: 0) }
        webView = wv
        handler = proxy
        delegate = nav
        return wv
    }

    func load(url: String, captureScript: String, userAgent: String?,
              completion: @escaping (String?, String?) -> Void) {
        MainActor.assumeIsolated {
            guard let target = URL(string: url) else { _ = completion(nil, "invalid URL"); return }
            let wv = ensureView(captureScript: captureScript, userAgent: userAgent)
            failPending("navigation replaced")
            pendingCompletion = completion
            pendingAcceptsAny = false
            pendingNavigation = wv.load(URLRequest(url: target))
            if pendingNavigation == nil { pendingAcceptsAny = true }
        }
    }

    func callAsync(functionBody: String, argumentsJson: String,
                   completion: @escaping (String?, String?) -> Void) {
        MainActor.assumeIsolated {
            guard let wv = webView else { _ = completion(nil, "no-view"); return }
            guard let data = argumentsJson.data(using: .utf8),
                  let args = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] else {
                _ = completion(nil, "bad arguments"); return
            }
            wv.callAsyncJavaScript(functionBody, arguments: args, in: nil, in: .page) { result in
                switch result {
                case .success(let value):
                    if let text = value as? String { _ = completion(text, nil) } else { _ = completion(nil, "no answer") }
                case .failure(let error):
                    _ = completion(nil, error.localizedDescription)
                }
            }
        }
    }

    func currentUrl() -> String? {
        MainActor.assumeIsolated { webView?.url?.absoluteString }
    }

    func park() {
        MainActor.assumeIsolated {
            guard let wv = webView, let blank = URL(string: "about:blank") else { return }
            wv.load(URLRequest(url: blank))
        }
    }

    func destroy() {
        MainActor.assumeIsolated {
            failPending("the page was closed")
            guard let wv = webView else { return }
            webView = nil
            wv.stopLoading()
            wv.navigationDelegate = nil
            wv.uiDelegate = nil
            wv.configuration.userContentController.removeScriptMessageHandler(forName: WebSessionScripts.shared.IOS_CAPTURE_HANDLER)
            wv.configuration.userContentController.removeAllUserScripts()
            wv.removeFromSuperview()
            handler = nil
            delegate = nil
        }
    }

    private func failPending(_ reason: String) {
        guard let completion = pendingCompletion else { return }
        pendingCompletion = nil
        pendingNavigation = nil
        pendingAcceptsAny = false
        _ = completion(nil, reason)
    }

    fileprivate func navigationFinished(_ navigation: WKNavigation?, url: URL?) {
        guard let completion = pendingCompletion else { return }
        guard pendingAcceptsAny || navigation === pendingNavigation else { return }
        pendingCompletion = nil
        pendingNavigation = nil
        pendingAcceptsAny = false
        _ = completion(url?.absoluteString ?? "", nil)
    }

    fileprivate func navigationFailed(_ navigation: WKNavigation?, error: Error) {
        guard pendingCompletion != nil, pendingAcceptsAny || navigation === pendingNavigation else { return }
        let ns = error as NSError
        if ns.domain == NSURLErrorDomain && ns.code == NSURLErrorCancelled {
            // Another navigation replaced ours (a script or a link): wait for it.
            pendingAcceptsAny = true
            return
        }
        failPending("navigation failed (\(ns.code))")
    }

    fileprivate func contentProcessEnded() {
        NSLog("WebSession: hidden page's web content process ended")
        destroy()
    }

    /// The page may move only within the cookie domains (https), or to the
    /// idle park's blank page.
    fileprivate func allowsMainFrame(_ url: URL?) -> Bool {
        guard let url else { return false }
        if url.absoluteString == "about:blank" { return true }
        let allowed = url.scheme?.lowercased() == "https" &&
            IosContainer.companion.shared.webSessionUrlUnderCookieDomains(url: url.absoluteString)
        if !allowed { NSLog("WebSession: refused a main-frame navigation off the cookie domains") }
        return allowed
    }

    fileprivate func captureMessage(_ message: WKScriptMessage) {
        let origin = message.frameInfo.securityOrigin
        guard origin.protocol == "https",
              IosContainer.companion.shared.webSessionUrlUnderCookieDomains(url: "https://\(origin.host)/"),
              let body = message.body as? String,
              let handler = captureHandler else { return }
        _ = handler(body)
    }

    // MARK: Capture

    func setCaptureHandler(handler: ((String) -> Void)?) {
        captureHandler = handler
    }

    // MARK: Cookies

    private var cookieStore: WKHTTPCookieStore {
        MainActor.assumeIsolated { WKWebsiteDataStore.default().httpCookieStore }
    }

    func allCookies(completion: @escaping ([WebCookie]) -> Void) {
        cookieStore.getAllCookies { cookies in
            _ = completion(cookies.map { c in
                WebCookie(
                    name: c.name,
                    value: c.value,
                    domain: c.domain,
                    path: c.path,
                    secure: c.isSecure,
                    expiresAtMs: c.expiresDate.map { KotlinLong(value: Int64($0.timeIntervalSince1970 * 1000)) }
                )
            })
        }
    }

    func setCookies(url: String, setCookieLines: [String], completion: @escaping () -> Void) {
        guard let target = URL(string: url) else { _ = completion(); return }
        let store = cookieStore
        var cookies: [HTTPCookie] = []
        for line in setCookieLines {
            cookies += HTTPCookie.cookies(withResponseHeaderFields: ["Set-Cookie": line], for: target)
        }
        let group = DispatchGroup()
        for cookie in cookies {
            group.enter()
            store.setCookie(cookie) { group.leave() }
        }
        group.notify(queue: .main) { _ = completion() }
    }

    func deleteCookies(cookies: [WebCookie], completion: @escaping (KotlinBoolean) -> Void) {
        let store = cookieStore
        let wanted = Set(cookies.map { "\($0.name)\u{1}\($0.domain)\u{1}\($0.path)" })
        store.getAllCookies { all in
            let doomed = all.filter { wanted.contains("\($0.name)\u{1}\($0.domain)\u{1}\($0.path)") }
            let group = DispatchGroup()
            for cookie in doomed {
                group.enter()
                store.delete(cookie) { group.leave() }
            }
            group.notify(queue: .main) { _ = completion(KotlinBoolean(value: true)) }
        }
    }

    // MARK: Sign-in

    func openSignIn(url: String, title: String, userAgent: String?) -> SignInWindow {
        MainActor.assumeIsolated {
            let window = WebSessionSignInWindow()
            guard let target = URL(string: url), let presenter = Self.topViewController() else {
                NSLog("WebSession: no screen to show the sign-in on")
                window.markClosed()
                return window
            }
            let controller = WebSessionSignInController(start: target, title: title, userAgent: userAgent, handle: window)
            let nav = UINavigationController(rootViewController: controller)
            nav.modalPresentationStyle = .fullScreen
            presenter.present(nav, animated: true)
            return window
        }
    }

    // MARK: Windows

    @MainActor
    private static func keyWindow() -> UIWindow? {
        let windows = UIApplication.shared.connectedScenes
            .compactMap { $0 as? UIWindowScene }
            .flatMap { $0.windows }
        return windows.first { $0.isKeyWindow } ?? windows.first
    }

    @MainActor
    private static func topViewController() -> UIViewController? {
        var top = keyWindow()?.rootViewController
        while let presented = top?.presentedViewController { top = presented }
        return top
    }

    // MARK: Delegates (weak, so the configuration doesn't keep the owner alive)

    private final class MessageProxy: NSObject, WKScriptMessageHandler {
        weak var owner: WebSessionWebView?
        init(owner: WebSessionWebView) { self.owner = owner }
        func userContentController(_ controller: WKUserContentController, didReceive message: WKScriptMessage) {
            owner?.captureMessage(message)
        }
    }

    private final class NavigationProxy: NSObject, WKNavigationDelegate, WKUIDelegate {
        weak var owner: WebSessionWebView?
        init(owner: WebSessionWebView) { self.owner = owner }

        func webView(_ webView: WKWebView, decidePolicyFor action: WKNavigationAction,
                     decisionHandler: @escaping (WKNavigationActionPolicy) -> Void) {
            guard let frame = action.targetFrame else { decisionHandler(.cancel); return }  // a popup
            if !frame.isMainFrame { decisionHandler(.allow); return }
            decisionHandler(owner?.allowsMainFrame(action.request.url) == true ? .allow : .cancel)
        }

        func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
            owner?.navigationFinished(navigation, url: webView.url)
        }

        func webView(_ webView: WKWebView, didFail navigation: WKNavigation!, withError error: Error) {
            owner?.navigationFailed(navigation, error: error)
        }

        func webView(_ webView: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!, withError error: Error) {
            owner?.navigationFailed(navigation, error: error)
        }

        func webViewWebContentProcessDidTerminate(_ webView: WKWebView) {
            owner?.contentProcessEnded()
        }

        // No popups: window.open from the page opens nothing.
        func webView(_ webView: WKWebView, createWebViewWith configuration: WKWebViewConfiguration,
                     for action: WKNavigationAction, windowFeatures: WKWindowFeatures) -> WKWebView? { nil }
    }
}

// MARK: - The sign-in screen

/// The `SignInWindow` the shared host ticks over: the screen's URL, and
/// whether it's gone.
final class WebSessionSignInWindow: NSObject, SignInWindow {
    fileprivate var url: String?
    private var closed = false
    fileprivate weak var controller: UIViewController?

    func currentUrl() -> String? { url }
    var isClosed: Bool { closed }

    func close() {
        MainActor.assumeIsolated {
            if let controller, controller.presentingViewController != nil {
                controller.dismiss(animated: true)
            }
            markClosed()
        }
    }

    fileprivate func markClosed() { closed = true }
}

/// A full-screen web view on the same data store as the hidden page (so the
/// session's cookies land where it reads them), with the config's title and
/// a close button. No capture script and no message handler run in it, so a
/// sign-in form's body (the password included) never reaches a capture. It
/// follows the service's identity hops; its start URL is scoped by the bridge.
private final class WebSessionSignInController: UIViewController, WKNavigationDelegate, WKUIDelegate {
    private let start: URL
    private let userAgent: String?
    private let handle: WebSessionSignInWindow
    private var webView: WKWebView?
    private var urlObservation: NSKeyValueObservation?

    init(start: URL, title: String, userAgent: String?, handle: WebSessionSignInWindow) {
        self.start = start
        self.userAgent = userAgent
        self.handle = handle
        super.init(nibName: nil, bundle: nil)
        self.title = title
        handle.url = start.absoluteString
    }

    required init?(coder: NSCoder) { fatalError("not used") }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = UIColor(red: 0x16 / 255, green: 0x16 / 255, blue: 0x16 / 255, alpha: 1)
        navigationItem.rightBarButtonItem = UIBarButtonItem(
            barButtonSystemItem: .close, target: self, action: #selector(closeTapped))
        let config = WKWebViewConfiguration()
        config.websiteDataStore = .default()
        let wv = WKWebView(frame: view.bounds, configuration: config)
        wv.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        wv.navigationDelegate = self
        wv.uiDelegate = self
        if let userAgent { wv.customUserAgent = userAgent }
        view.addSubview(wv)
        webView = wv
        handle.controller = navigationController ?? self
        urlObservation = wv.observe(\.url, options: [.new]) { [weak self] webView, _ in
            MainActor.assumeIsolated {
                if let url = webView.url?.absoluteString { self?.handle.url = url }
            }
        }
        wv.load(URLRequest(url: start))
    }

    override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        handle.controller = navigationController ?? self
    }

    override func viewDidDisappear(_ animated: Bool) {
        super.viewDidDisappear(animated)
        // Full screen, so it disappears only when it's dismissed.
        if (navigationController ?? self).presentingViewController == nil || isBeingDismissed ||
            navigationController?.isBeingDismissed == true {
            urlObservation = nil
            webView?.stopLoading()
            handle.markClosed()
        }
    }

    @objc private func closeTapped() {
        (navigationController ?? self).dismiss(animated: true)
        handle.markClosed()
    }

    // Identity hops are followed; only http(s) loads here.
    func webView(_ webView: WKWebView, decidePolicyFor action: WKNavigationAction,
                 decisionHandler: @escaping (WKNavigationActionPolicy) -> Void) {
        let scheme = action.request.url?.scheme?.lowercased()
        decisionHandler(scheme == "https" || scheme == "http" || scheme == "about" ? .allow : .cancel)
    }

    // A form that opens a new window loads here instead.
    func webView(_ webView: WKWebView, createWebViewWith configuration: WKWebViewConfiguration,
                 for action: WKNavigationAction, windowFeatures: WKWindowFeatures) -> WKWebView? {
        if action.targetFrame == nil, let url = action.request.url { webView.load(URLRequest(url: url)) }
        return nil
    }
}
