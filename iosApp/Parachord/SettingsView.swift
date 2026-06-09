import SwiftUI
import Shared

// MARK: - Settings ViewModel (phase 5.0)
//
// First real screen wired to the shared module. Observes the shared
// `SettingsStore`'s Kotlin Flows via `FlowWatcher` and republishes them
// as @Observable state; writes go straight back to the store's suspend
// setters. The store is the SAME class Android uses (KMP commonMain) —
// backed on iOS by NSUserDefaults (KvStore) + Keychain (SecureTokenStore).
//
// This establishes the ViewModel-shim pattern every subsequent screen
// follows: hold a shared service, bridge its Flows to @Observable, call
// its suspend functions from button taps.

@MainActor
@Observable
final class SettingsViewModel {

    private let container = IosContainer.companion.shared
    private let store = IosContainer.companion.shared.settingsStore
    private let watcher = FlowWatcher(scope: IosContainer.companion.shared.appScope)
    private var subscriptions: [Cancellable] = []

    // Retained for the duration of the OAuth session — ASWebAuthenticationSession
    // inside IosOAuthManager must stay alive until the callback fires, so it
    // can't be a Task-local that deallocates mid-flow.
    private var oauthManager: IosOAuthManager?

    // Mirrored settings state.
    var themeMode: String = "system"
    var scrobblingEnabled: Bool = false
    var lastFmUsername: String = ""
    var listenBrainzUsername: String = ""
    var spotifyConnected: Bool = false
    var spotifyError: String? = nil

    // BYO keys/tokens (Keychain-backed via the shared SecureTokenStore). These
    // are one-shot secure reads (not Flows), loaded on start().
    var listenBrainzToken: String = ""
    var selectedAiProvider: String = "chatgpt"
    var aiKeys: [String: String] = ["chatgpt": "", "claude": "", "gemini": ""]
    var ticketmasterKey: String = ""
    var seatGeekId: String = ""

    static let aiProviders = ["chatgpt", "claude", "gemini"]

    func start() {
        guard subscriptions.isEmpty else { return }
        // Each `watch` collects a shared Flow; the closure receives the
        // emission as `Any?` (generics erase across the bridge) and casts.
        subscriptions.append(
            watcher.watch(flow: store.themeMode) { [weak self] value in
                if let v = value as? String { self?.themeMode = v }
            }
        )
        subscriptions.append(
            watcher.watch(flow: store.scrobblingEnabled) { [weak self] value in
                if let v = value as? Bool { self?.scrobblingEnabled = v }
            }
        )
        subscriptions.append(
            watcher.watch(flow: store.getLastFmUsernameFlow()) { [weak self] value in
                self?.lastFmUsername = (value as? String) ?? ""
            }
        )
        subscriptions.append(
            watcher.watch(flow: store.getListenBrainzUsernameFlow()) { [weak self] value in
                self?.listenBrainzUsername = (value as? String) ?? ""
            }
        )
        subscriptions.append(
            watcher.watch(flow: container.getSpotifyConnectedFlow()) { [weak self] value in
                self?.spotifyConnected = (value as? Bool) ?? ((value as? KotlinBoolean)?.boolValue ?? false)
            }
        )
        loadKeys()
    }

    /// One-shot secure reads of the BYO keys (Keychain, not Flow-backed).
    private func loadKeys() {
        Task { @MainActor in
            listenBrainzToken = (try? await store.getListenBrainzToken()) ?? ""
            selectedAiProvider = (try? await store.getSelectedChatProvider()) ?? "chatgpt"
            for p in Self.aiProviders {
                aiKeys[p] = (try? await store.getAiProviderApiKey(providerId: p)) ?? ""
            }
            ticketmasterKey = (try? await store.getTicketmasterApiKey()) ?? ""
            seatGeekId = (try? await store.getSeatGeekClientId()) ?? ""
        }
    }

    func stop() {
        subscriptions.forEach { $0.cancel() }
        subscriptions.removeAll()
    }

    // Writes — fire-and-forget into the shared store's suspend setters.

    func setTheme(_ mode: String) {
        themeMode = mode  // optimistic; the flow will confirm
        Task { try? await store.setThemeMode(mode: mode) }
    }

    func setScrobbling(_ enabled: Bool) {
        scrobblingEnabled = enabled
        Task { try? await store.setScrobblingEnabled(enabled: enabled) }
    }

    func setLastFmUsername(_ username: String) {
        Task { try? await store.setLastFmUsername(username: username) }
    }

    func setListenBrainzUsername(_ username: String) {
        Task { try? await store.setListenBrainzUsername(username: username) }
    }

    func setListenBrainzToken(_ token: String) {
        listenBrainzToken = token
        Task { try? await store.setListenBrainzToken(token: token) }
    }

    func setSelectedAiProvider(_ provider: String) {
        selectedAiProvider = provider
        Task { try? await store.setSelectedChatProvider(providerId: provider) }
    }

    func setAiKey(_ provider: String, _ key: String) {
        aiKeys[provider] = key
        Task { try? await store.setAiProviderApiKey(providerId: provider, apiKey: key) }
    }

    func setTicketmasterKey(_ key: String) {
        ticketmasterKey = key
        Task { try? await store.setTicketmasterApiKey(key: key) }
    }

    func setSeatGeekId(_ id: String) {
        seatGeekId = id
        Task { try? await store.setSeatGeekClientId(id: id) }
    }

    // Spotify OAuth — drives the PKCE authorize flow then exchanges the code
    // for tokens via the shared container. The connected flow republishes
    // the result into `spotifyConnected`.

    func connectSpotify() {
        Task { @MainActor in
            do {
                let manager = IosOAuthManager()
                oauthManager = manager  // retain for the auth session
                let cfg = OAuthConfig.spotify(clientId: container.appConfig.spotifyClientId)
                let result = try await manager.authorize(cfg)
                try await container.connectSpotify(
                    code: result.code,
                    codeVerifier: result.codeVerifier
                )
                spotifyError = nil
            } catch let e as OAuthError {
                spotifyError = e.localizedDescription
            } catch {
                spotifyError = error.localizedDescription
            }
            oauthManager = nil
        }
    }

    func disconnectSpotify() {
        Task { try? await container.disconnectSpotify() }
    }
}

// MARK: - Settings screen

struct SettingsView: View {
    @State private var model = SettingsViewModel()

    private static let themeOptions = ["system", "light", "dark"]

    var body: some View {
        NavigationStack {
            Form {
                Section("Appearance") {
                    Picker("Theme", selection: themeBinding) {
                        ForEach(Self.themeOptions, id: \.self) { option in
                            Text(option.capitalized).tag(option)
                        }
                    }
                    .pickerStyle(.segmented)
                }

                Section("Scrobbling") {
                    Toggle("Enable scrobbling", isOn: scrobblingBinding)
                }

                Section("Last.fm") {
                    TextField("Username", text: lastFmBinding)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                }

                Section {
                    TextField("Username", text: listenBrainzBinding)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                    SecureField("User token", text: lbTokenBinding)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                } header: { Text("ListenBrainz") } footer: {
                    Text("The user token enables playlist sync, follows, and loved-track push. Find it at listenbrainz.org/settings.")
                }

                Section("Spotify") {
                    if model.spotifyConnected {
                        HStack(spacing: 8) {
                            Image(systemName: "checkmark.circle.fill")
                                .foregroundStyle(.green)
                            Text("Spotify · Connected")
                        }
                        Button("Disconnect", role: .destructive) {
                            model.disconnectSpotify()
                        }
                    } else {
                        Button("Connect Spotify") {
                            model.connectSpotify()
                        }
                        if let error = model.spotifyError {
                            Text(error)
                                .font(.caption)
                                .foregroundStyle(.red)
                        }
                    }
                }

                Section {
                    Picker("Provider", selection: aiProviderBinding) {
                        Text("ChatGPT").tag("chatgpt")
                        Text("Claude").tag("claude")
                        Text("Gemini").tag("gemini")
                    }
                    SecureField("ChatGPT API key", text: aiKeyBinding("chatgpt"))
                        .textInputAutocapitalization(.never).autocorrectionDisabled()
                    SecureField("Claude API key", text: aiKeyBinding("claude"))
                        .textInputAutocapitalization(.never).autocorrectionDisabled()
                    SecureField("Gemini API key", text: aiKeyBinding("gemini"))
                        .textInputAutocapitalization(.never).autocorrectionDisabled()
                } header: { Text("AI Providers") } footer: {
                    Text("Bring your own API key for Shuffleupagus (DJ chat) and AI recommendations. Only the selected provider is used.")
                }

                Section {
                    SecureField("Ticketmaster API key", text: ticketmasterBinding)
                        .textInputAutocapitalization(.never).autocorrectionDisabled()
                    SecureField("SeatGeek client ID", text: seatGeekBinding)
                        .textInputAutocapitalization(.never).autocorrectionDisabled()
                } header: { Text("Concerts & Events") } footer: {
                    Text("Keys for concert discovery. Without them the On Tour and Concerts features stay empty.")
                }

                Section {
                    Text(
                        "These read/write the SHARED SettingsStore (KMP " +
                        "commonMain) — the same class Android uses. Backed " +
                        "on iOS by NSUserDefaults + Keychain. Changes persist " +
                        "across launches."
                    )
                    .font(.caption)
                    .foregroundStyle(.secondary)
                }
            }
            .navigationTitle("Settings")
        }
        .onAppear { model.start() }
        .onDisappear { model.stop() }
    }

    // Bindings translate SwiftUI control changes into shared-store writes.

    private var themeBinding: Binding<String> {
        Binding(get: { model.themeMode }, set: { model.setTheme($0) })
    }
    private var scrobblingBinding: Binding<Bool> {
        Binding(get: { model.scrobblingEnabled }, set: { model.setScrobbling($0) })
    }
    private var lastFmBinding: Binding<String> {
        Binding(get: { model.lastFmUsername }, set: { model.setLastFmUsername($0) })
    }
    private var listenBrainzBinding: Binding<String> {
        Binding(get: { model.listenBrainzUsername }, set: { model.setListenBrainzUsername($0) })
    }
    private var lbTokenBinding: Binding<String> {
        Binding(get: { model.listenBrainzToken }, set: { model.setListenBrainzToken($0) })
    }
    private var aiProviderBinding: Binding<String> {
        Binding(get: { model.selectedAiProvider }, set: { model.setSelectedAiProvider($0) })
    }
    private func aiKeyBinding(_ provider: String) -> Binding<String> {
        Binding(get: { model.aiKeys[provider] ?? "" }, set: { model.setAiKey(provider, $0) })
    }
    private var ticketmasterBinding: Binding<String> {
        Binding(get: { model.ticketmasterKey }, set: { model.setTicketmasterKey($0) })
    }
    private var seatGeekBinding: Binding<String> {
        Binding(get: { model.seatGeekId }, set: { model.setSeatGeekId($0) })
    }
}
