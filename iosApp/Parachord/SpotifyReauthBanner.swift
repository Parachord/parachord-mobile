import SwiftUI
import Shared

/// Home reconnect banner shown when a Spotify token refresh fails terminally
/// (dead grant — revoked from accounts.spotify.com, or the six-month
/// refresh-token expiry effective 2026-07-20, #243). Mirrors Android's
/// `SpotifyReauthBanner`.
///
/// Driven reactively by `IosContainer.getSpotifyReauthRequiredFlow()`. The flag
/// is distinct from "not connected": a user who never linked Spotify is
/// disconnected but does NOT need re-auth, so the banner stays hidden for them.
/// Non-dismissible — the only resolution is to reconnect (which clears the
/// signal) or restart the app.
@MainActor
@Observable
final class SpotifyReauthModel {
    private let container = IosContainer.companion.shared
    private let watcher = FlowWatcher(scope: IosContainer.companion.shared.appScope)
    private var sub: Cancellable?
    /// Retains the auth session for its lifetime (same pattern as SettingsView).
    private var oauth: IosOAuthManager?

    var required = false
    var connecting = false

    func start() {
        guard sub == nil else { return }
        sub = watcher.watch(flow: container.getSpotifyReauthRequiredFlow()) { [weak self] v in
            let req = (v as? Bool) ?? ((v as? KotlinBoolean)?.boolValue ?? false)
            Task { @MainActor in self?.required = req }
        }
    }

    func stop() { sub?.cancel(); sub = nil }

    /// Re-run the Spotify OAuth flow with the user's stored Client ID. On
    /// success `connectSpotify` clears the reauth flag, so the banner
    /// disappears reactively. On failure the banner stays up for a retry.
    ///
    /// If no Client ID is stored (can't build the PKCE request), fall back to
    /// `onNeedsSetup` — the caller opens Settings, where the user enters the
    /// Client ID and connects. In production the banner only appears after a
    /// prior authorization, so the Client ID is normally present and the
    /// in-place OAuth fires directly.
    func reconnect(onNeedsSetup: @escaping () -> Void) {
        guard !connecting else { return }
        connecting = true
        Task { @MainActor in
            defer { connecting = false; oauth = nil }
            let clientId = (try? await container.settingsStore.getSpotifyClientId()) ?? ""
            guard !clientId.isEmpty else { onNeedsSetup(); return }
            let m = IosOAuthManager(); oauth = m
            let cfg = OAuthConfig.spotify(clientId: clientId)
            do {
                let r = try await m.authorize(cfg)
                try await container.connectSpotify(code: r.code, codeVerifier: r.codeVerifier)
            } catch {
                // Leave the banner up; the user can tap Reconnect again.
            }
        }
    }
}

struct PCSpotifyReauthBanner: View {
    let connecting: Bool
    let onReconnect: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text("Spotify session expired — tap to reconnect")
                .font(.system(size: 15, weight: .semibold))
                .foregroundStyle(PC.fg1)
            Text("Reconnect to keep playback, sync, and scrobbling working.")
                .font(.system(size: 13))
                .foregroundStyle(PC.fg2)
            Button(action: onReconnect) {
                HStack(spacing: 6) {
                    if connecting { ProgressView().controlSize(.small) }
                    Text(connecting ? "Reconnecting…" : "Reconnect Spotify")
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
        .padding(.horizontal, 20)
        .padding(.top, 12)
        .padding(.bottom, 4)
    }
}
