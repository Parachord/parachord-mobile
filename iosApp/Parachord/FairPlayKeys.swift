import AVFoundation
import Foundation
import Shared

// MARK: - A plug-in's FairPlay keys (#794, desktop #1627)
//
// One play's FairPlay key session. AVPlayer asks for each `skd://` key of the
// plug-in's HLS; this builds the SPC with the FairPlay application certificate
// the plug-in sent with its stream (Amazon Music sends Amazon's), hands it to
// the plug-in's `relayLicense` through the shared container (the plug-in posts
// it to the service with its own session and headers), and gives the CKC back
// to AVFoundation. The SPC and the CKC are never logged.
//
// FairPlay needs a real device: the simulator's AVContentKeySession throws for
// `.fairPlayStreaming`, so the app tells the shared side FairPlay isn't there
// (`IosContainer.fairPlayAvailable`, set by `AppBootstrap`) and a plug-in's
// plain stream (Amazon's purchases) plays first instead.

final class IosFairPlayKeys: NSObject, AVContentKeySessionDelegate {
    enum State: Equatable {
        /// No key has come back yet.
        case waiting
        /// A CKC came back and was handed to AVFoundation.
        case licensed
        /// The plug-in refused the licence: [kind] is the shared outcome
        /// (`IosPluginStream.REFUSED` / `NOT_SIGNED_IN`, `IosFairPlayOpen.LICENSE_DENIED`
        /// / `START_FAILED`), [message] its words.
        case refused(kind: String, message: String?)
        /// The key request failed on this side (no SPC: the simulator, a bad
        /// certificate; or AVFoundation rejected the CKC).
        case failed(String)
    }

    private let sessionId: String
    private let resolverId: String
    private let certificate: Data
    private let session: AVContentKeySession
    private let queue = DispatchQueue(label: "com.parachord.fairplay-keys")
    private let lock = NSLock()
    private var _state: State = .waiting

    /// Where the keys stand. Read from the main actor while the router waits.
    var state: State {
        lock.lock(); defer { lock.unlock() }
        return _state
    }

    private func set(_ new: State) {
        lock.lock()
        // A refusal or failure isn't overwritten by a later key; a licence
        // only by a refusal of a renewal.
        if case .waiting = _state { _state = new }
        else if case .licensed = _state, new != .licensed { _state = new }
        lock.unlock()
    }

    /// The simulator has no FairPlay: its `AVContentKeySession` throws an
    /// Objective-C exception for `.fairPlayStreaming`, which Swift can't catch.
    static var isAvailable: Bool {
        #if targetEnvironment(simulator)
        return false
        #else
        return true
        #endif
    }

    init?(sessionId: String, certificateB64: String, resolverId: String) {
        guard Self.isAvailable else { return nil }
        guard let cert = Data(base64Encoded: certificateB64), !cert.isEmpty else { return nil }
        self.sessionId = sessionId
        self.resolverId = resolverId
        self.certificate = cert
        self.session = AVContentKeySession(keySystem: .fairPlayStreaming)
        super.init()
        session.setDelegate(self, queue: queue)
    }

    /// The asset whose keys this session answers.
    func attach(_ asset: AVURLAsset) {
        session.addContentKeyRecipient(asset)
    }

    /// The item is gone: no more keys, and the plug-in drops its context.
    func close() {
        session.expire()
        let id = sessionId
        Task { try? await IosContainer.companion.shared.closeFairPlay(sessionId: id) }
    }

    // MARK: AVContentKeySessionDelegate

    func contentKeySession(_ session: AVContentKeySession, didProvide keyRequest: AVContentKeyRequest) {
        handle(keyRequest)
    }

    func contentKeySession(_ session: AVContentKeySession, didProvideRenewingContentKeyRequest keyRequest: AVContentKeyRequest) {
        handle(keyRequest)
    }

    func contentKeySession(_ session: AVContentKeySession, contentKeyRequest keyRequest: AVContentKeyRequest,
                           didFailWithError err: Error) {
        NSLog("FAIRPLAY key request failed (session %@): %@", sessionId, err.localizedDescription)
        set(.failed(err.localizedDescription))
    }

    func contentKeySession(_ session: AVContentKeySession, shouldRetry keyRequest: AVContentKeyRequest,
                           reason retryReason: AVContentKeyRequest.RetryReason) -> Bool {
        // AVFoundation's own timeouts: one more try, as Apple's sample does.
        retryReason == .timedOut || retryReason == .receivedResponseWithExpiredLease
    }

    private func handle(_ keyRequest: AVContentKeyRequest) {
        // `skd://<id>`: the content identifier the SPC is built for is the part
        // after the scheme (Amazon: a key id).
        guard let skd = keyRequest.identifier as? String else {
            fail(keyRequest, "the key request has no skd identifier")
            return
        }
        let contentId = skd.hasPrefix("skd://") ? String(skd.dropFirst("skd://".count)) : skd
        guard let contentIdData = contentId.data(using: .utf8) else {
            fail(keyRequest, "the skd identifier isn't text")
            return
        }
        keyRequest.makeStreamingContentKeyRequestData(
            forApp: certificate, contentIdentifier: contentIdData,
            options: [AVContentKeyRequestProtocolVersionsKey: [1]]
        ) { [weak self] spc, error in
            guard let self else { return }
            guard let spc else {
                self.fail(keyRequest, "no SPC: \(error?.localizedDescription ?? "unknown")")
                return
            }
            let sessionId = self.sessionId
            let resolverId = self.resolverId
            Task {
                let reply = try? await IosContainer.companion.shared.relayFairPlayLicense(
                    sessionId: sessionId, spcB64: spc.base64EncodedString(), resolverId: resolverId)
                guard let reply else {
                    self.fail(keyRequest, "the licence relay didn't answer")
                    return
                }
                if let ckcB64 = reply.ckcB64, let ckc = Data(base64Encoded: ckcB64) {
                    keyRequest.processContentKeyResponse(AVContentKeyResponse(fairPlayStreamingKeyResponseData: ckc))
                    self.set(.licensed)
                } else {
                    keyRequest.processContentKeyResponseError(
                        NSError(domain: "com.parachord.fairplay", code: 1,
                                userInfo: [NSLocalizedDescriptionKey: reply.message ?? "licence refused"]))
                    self.set(.refused(kind: reply.kind ?? IosFairPlayOpen.companion.START_FAILED, message: reply.message))
                }
            }
        }
    }

    private func fail(_ keyRequest: AVContentKeyRequest, _ why: String) {
        NSLog("FAIRPLAY key request for session %@ failed: %@", sessionId, why)
        keyRequest.processContentKeyResponseError(
            NSError(domain: "com.parachord.fairplay", code: 2, userInfo: [NSLocalizedDescriptionKey: why]))
        set(.failed(why))
    }
}
