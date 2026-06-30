import SwiftUI
import Shared

/// Reconnect banner shown when an Apple Music LIBRARY call 401s (stale
/// Music-User-Token). Mirrors Android's `AppleMusicReauthBanner`. Driven
/// reactively by `IosContainer.watchAppleMusicReauthRequired`; the CTA re-mints
/// the MUT via StoreKit and clears the flag, so the banner disappears reactively.
@MainActor @Observable
final class AppleMusicReauthModel {
    private let container = IosContainer.companion.shared
    private var sub: Cancellable?

    var required = false
    var connecting = false

    func start() {
        guard sub == nil else { return }
        sub = container.watchAppleMusicReauthRequired { [weak self] v in
            Task { @MainActor in self?.required = v.boolValue }
        }
    }

    func stop() { sub?.cancel(); sub = nil }

    /// Re-mint the Music User Token (the library API credential) via StoreKit.
    /// On success, clear the reauth flag — the banner disappears reactively. On
    /// failure leave it up for a retry.
    func reconnect() {
        guard !connecting else { return }
        connecting = true
        Task { @MainActor in
            defer { connecting = false }
            let dev = (try? await container.appleMusicDeveloperToken()) ?? ""
            if let mut = await acquireAppleMusicMUT(developerToken: dev), !mut.isEmpty {
                try? await container.setAppleMusicUserToken(token: mut)
                container.clearAppleMusicReauth()
            }
        }
    }
}

struct PCAppleMusicReauthBanner: View {
    let connecting: Bool
    let onReconnect: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text("Apple Music session expired — tap to reconnect")
                .font(.system(size: 15, weight: .semibold))
                .foregroundStyle(PC.fg1)
            Text("Reconnect to keep your Apple Music library and playlists syncing.")
                .font(.system(size: 13))
                .foregroundStyle(PC.fg2)
            Button(action: onReconnect) {
                HStack(spacing: 6) {
                    if connecting { ProgressView().controlSize(.small) }
                    Text(connecting ? "Reconnecting…" : "Reconnect Apple Music")
                        .font(.system(size: 14, weight: .semibold))
                }
                .foregroundStyle(PC.accent)
            }
            .buttonStyle(.plain)
            .disabled(connecting)
            .padding(.top, 2)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(14)
        .background(PC.accent.opacity(0.10), in: RoundedRectangle(cornerRadius: 14, style: .continuous))
        .padding(.horizontal, 16)
    }
}
