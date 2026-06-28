import SwiftUI
import UniformTypeIdentifiers
import StoreKit
import Shared

// MARK: - Settings (full, tabbed) — Plug-ins / General / About
//
// Mirrors Android's SettingsScreen: a Plug-ins tab with a reorderable Content
// Resolvers strip (priority drives the resolver pipeline), Meta Services +
// Concerts grids, and a Plugin Updates / marketplace row; a General tab
// (theme, scrobbling); and an About tab. Tapping any service tile opens a
// config sheet for OAuth / BYO API keys / model selection — all persisted to
// the shared SettingsStore (Keychain for secrets).

// ── Service catalog (id, display name, brand color, kind) ──────────────
struct PCService: Identifiable {
    let id: String
    let name: String
    let color: UInt32
    let icon: String
    enum Kind { case resolver, meta, concert }
    let kind: Kind
}

/// A plugin actually loaded by the shared PluginManager (mirrors Android's
/// dynamic `buildPluginList`). The Settings grids render from THESE — not the
/// static PCServices catalog — so marketplace-downloaded plugins (Achordion) and
/// bundled-but-uncataloged ones (Wikipedia) appear, and the "N plugins loaded"
/// count always matches what's shown.
struct PCLoadedPlugin: Identifiable {
    let id: String
    let name: String
    let colorHex: String
    let version: String
    let caps: [String]
    let kind: PCService.Kind
}

/// Parse a `.axe` hex color ("#1DB954") into the UInt32 PCService uses; falls
/// back to brand purple.
func pcParseHex(_ hex: String) -> UInt32 {
    let s = hex.hasPrefix("#") ? String(hex.dropFirst()) : hex
    return UInt32(s, radix: 16) ?? 0x7C3AED
}

enum PCServices {
    static let resolvers: [PCService] = [
        .init(id: "spotify", name: "Spotify", color: 0x1DB954, icon: "music.note", kind: .resolver),
        .init(id: "applemusic", name: "Apple Music", color: 0xFA243C, icon: "music.note", kind: .resolver),
        .init(id: "bandcamp", name: "Bandcamp", color: 0x629AA9, icon: "music.note", kind: .resolver),
        .init(id: "soundcloud", name: "SoundCloud", color: 0xFF5500, icon: "cloud", kind: .resolver),
        .init(id: "localfiles", name: "Local Files", color: 0xA855F7, icon: "internaldrive", kind: .resolver),
    ]
    static let meta: [PCService] = [
        .init(id: "lastfm", name: "Last.fm", color: 0xD51007, icon: "waveform", kind: .meta),
        .init(id: "listenbrainz", name: "ListenBrainz", color: 0xEB743B, icon: "waveform", kind: .meta),
        .init(id: "librefm", name: "Libre.fm", color: 0x4CAF50, icon: "waveform", kind: .meta),
        .init(id: "discogs", name: "Discogs", color: 0x333333, icon: "opticaldisc", kind: .meta),
        .init(id: "chatgpt", name: "ChatGPT", color: 0x10A37F, icon: "sparkles", kind: .meta),
        .init(id: "claude", name: "Claude", color: 0xD97757, icon: "sparkles", kind: .meta),
        .init(id: "gemini", name: "Gemini", color: 0x4285F4, icon: "sparkles", kind: .meta),
    ]
    static let concerts: [PCService] = [
        .init(id: "ticketmaster", name: "Ticketmaster", color: 0x026CDF, icon: "ticket", kind: .concert),
        .init(id: "seatgeek", name: "SeatGeek", color: 0xFF5B49, icon: "ticket", kind: .concert),
        .init(id: "bandsintown", name: "Bandsintown", color: 0x00B4B3, icon: "ticket", kind: .concert),
        .init(id: "songkick", name: "Songkick", color: 0xF80046, icon: "ticket", kind: .concert),
    ]
    static let all = resolvers + meta + concerts
    static func find(_ id: String) -> PCService? { all.first { $0.id == id } }

    /// "Where do I get my key?" dev-portal URLs (mirrors desktop + Android).
    static func helpUrl(_ id: String) -> String? {
        switch id {
        case "soundcloud":   return "https://soundcloud.com/you/apps"
        case "listenbrainz": return "https://listenbrainz.org/settings/"
        case "discogs":      return "https://www.discogs.com/settings/developers"
        case "chatgpt":      return "https://platform.openai.com/api-keys"
        case "claude":       return "https://console.anthropic.com/settings/keys"
        case "gemini":       return "https://aistudio.google.com/apikey"
        case "ticketmaster": return "https://developer-acct.ticketmaster.com/"
        case "seatgeek":     return "https://seatgeek.com/account/develop"
        case "bandsintown":  return "https://artists.bandsintown.com/support/api-installation"
        case "songkick":     return "https://www.songkick.com/developer"
        default:             return nil
        }
    }
    static func helpLabel(_ id: String) -> String {
        guard let url = helpUrl(id), let host = URL(string: url)?.host else { return "Get a key →" }
        return host.replacingOccurrences(of: "www.", with: "") + " →"
    }
}

// MARK: - ViewModel

@MainActor @Observable
final class SettingsViewModel {
    private let container = IosContainer.companion.shared
    private var store: SettingsStore { container.settingsStore }
    private let watcher = FlowWatcher(scope: IosContainer.companion.shared.appScope)
    private var subs: [Cancellable] = []
    private var oauthManager: IosOAuthManager?

    var themeMode = "system"
    var scrobblingEnabled = false
    var persistQueue = true   // "Remember queue" (#220) — default on, Android parity
    var spotifyConnected = false
    var spotifyClientId = ""        // BYO Developer Client ID (Parachord ships none)
    var spotifyError: String?
    /// Last.fm session-key connection (scrobbling). Driven by the session-key
    /// flow, NOT the read-only username — only the session key authenticates
    /// writes (#193).
    var lastFmConnected = false
    var lastFmError: String?
    var lastFmBusy = false
    /// Enabled-AND-usable resolvers, in priority order (mirrors desktop's
    /// `resolver_order`, which only holds resolvers that are actually usable).
    var resolverOrder: [String] = []
    /// User intent (`active_resolvers`): resolvers the user wants on. An
    /// auth-gated resolver only reaches the priority list once it's also
    /// connected (isUsable) — so a not-connected Spotify is never "enabled".
    var activeResolvers: Set<String> = Set(PCServices.resolvers.map { $0.id })
    private var resolversReady = false

    /// Resolvers needing an explicit connection before they can resolve.
    /// (Bandcamp / Local Files need none; Apple Music auths at play time.)
    func requiresConnection(_ id: String) -> Bool { id == "spotify" || id == "soundcloud" }
    /// Usable = no connection required, or connected.
    func isUsable(_ id: String) -> Bool { !requiresConnection(id) || isConnected(id) }
    /// In the priority list = user-enabled AND usable AND mobile-allowed.
    func isEnabledUsable(_ id: String) -> Bool {
        activeResolvers.contains(id) && !mobileBlocked.contains(id) && isUsable(id)
    }
    /// Below the list: user-disabled, or auth-gated and not yet connected.
    var disabledResolvers: [String] {
        resolverCatalog.map { $0.id }.filter { !mobileBlocked.contains($0) && !isEnabledUsable($0) }
    }

    /// The resolver catalog — DYNAMIC from the loaded resolve-capable plugins so a
    /// resolver downloaded/updated from the marketplace appears in the list and can
    /// be prioritised/enabled (Android parity). Falls back to the static catalog
    /// before the plugin layer has loaded.
    var resolverCatalog: [PCService] {
        let dynamic = displayServices(.resolver)
        return dynamic.isEmpty ? PCServices.resolvers : dynamic
    }
    func resolverService(_ id: String) -> PCService? { resolverCatalog.first { $0.id == id } }

    /// service id → its stored key/token/username (empty = not configured).
    var values: [String: String] = [:]
    var aiModels: [String: String] = ["chatgpt": "", "claude": "", "gemini": ""]
    var selectedAiProvider = "chatgpt"
    /// Global "let the DJ see my listening data" gate (shared `send_listening_history`).
    var sendListeningHistory = false

    var libreFmConnected = false
    var libreFmUser = ""
    var libreFmPass = ""
    var libreFmError: String?
    var libreFmBusy = false

    var pluginCount = 0
    /// The live set of loaded plugins, mapped from PluginManager (Android parity).
    /// Drives the Meta/Concerts grids + the count.
    var loadedPlugins: [PCLoadedPlugin] = []
    /// Plugin ids the user has explicitly disabled (shared `disabled_plugins`).
    /// Gates resolution (IosResolverCoordinator) + metadata (getDisabledProviders).
    var disabledPlugins: Set<String> = []
    func setPluginEnabled(_ id: String, _ enabled: Bool) {
        Task { try? await store.setPluginEnabled(pluginId: id, enabled: enabled) }
    }
    /// Plugin ids declaring `capabilities.mobile == false` — hidden everywhere
    /// (youtube, ollama). Seeded with the known defaults so filtering holds even
    /// if the .axe layer hasn't loaded yet; augmented from plugin metadata.
    var mobileBlocked: Set<String> = ["youtube", "ollama"]

    /// Re-read the loaded plugins (mobile-filtered — same set everywhere) and map
    /// them to display models. `pluginCount` == `loadedPlugins.count` so the count
    /// and the grids never disagree.
    func refreshLoadedPlugins() async {
        let loaded = (try? await container.loadPlugins()) ?? []
        loadedPlugins = loaded.map { p in
            let kind: PCService.Kind =
                (p.capabilities["resolve"]?.boolValue == true) ? .resolver
                : (p.capabilities["concerts"]?.boolValue == true) ? .concert : .meta
            let caps = p.capabilities.compactMap { $0.value.boolValue ? $0.key.capitalized : nil }.sorted()
            return PCLoadedPlugin(id: p.id, name: p.name, colorHex: p.color, version: p.version, caps: caps, kind: kind)
        }
        pluginCount = loadedPlugins.count
        // A marketplace-downloaded/updated resolver just changed the catalog —
        // fold it into the priority list (canonical-insert if newly usable).
        if resolversReady { recomputeResolvers() }
    }

    /// The services to render for a category — built from the LIVE loaded plugins,
    /// using the PCServices catalog for rich presentation when available and
    /// synthesizing a tile (axe name/color, generic icon) for uncataloged ones.
    func displayServices(_ kind: PCService.Kind) -> [PCService] {
        loadedPlugins.filter { $0.kind == kind }.map { p in
            PCServices.find(p.id)
                ?? PCService(id: p.id, name: p.name, color: pcParseHex(p.colorHex),
                             icon: "puzzlepiece.extension.fill", kind: kind)
        }
    }
    enum SyncState: Equatable { case idle, syncing, done(String), failed }
    var syncState: SyncState = .idle

    static let aiProviders = ["chatgpt", "claude", "gemini"]
    static let aiModelOptions: [String: [String]] = [
        "chatgpt": ["gpt-4o-mini", "gpt-4o", "gpt-4-turbo"],
        "claude": ["claude-sonnet-4-20250514", "claude-3-5-sonnet-20241022", "claude-3-5-haiku-20241022"],
        "gemini": ["gemini-2.5-flash", "gemini-2.0-flash", "gemini-1.5-pro"],
    ]

    func start() {
        guard subs.isEmpty else { return }
        subs.append(watcher.watch(flow: store.themeMode) { [weak self] v in if let s = v as? String { self?.themeMode = s } })
        subs.append(watcher.watch(flow: store.scrobblingEnabled) { [weak self] v in if let b = v as? Bool { self?.scrobblingEnabled = b } })
        subs.append(watcher.watch(flow: store.persistQueue) { [weak self] v in if let b = v as? Bool { self?.persistQueue = b } })
        subs.append(watcher.watch(flow: store.getDisabledPluginsFlow()) { [weak self] v in self?.disabledPlugins = (v as? Set<String>) ?? [] })
        subs.append(watcher.watch(flow: container.getSpotifyConnectedFlow()) { [weak self] v in
            self?.spotifyConnected = (v as? Bool) ?? ((v as? KotlinBoolean)?.boolValue ?? false)
            self?.recomputeResolvers()   // connect/disconnect moves Spotify in/out of the list
        })
        subs.append(watcher.watch(flow: container.getLibreFmConnectedFlow()) { [weak self] v in
            self?.libreFmConnected = (v as? Bool) ?? ((v as? KotlinBoolean)?.boolValue ?? false) })
        subs.append(watcher.watch(flow: container.getLastFmConnectedFlow()) { [weak self] v in
            self?.lastFmConnected = (v as? Bool) ?? ((v as? KotlinBoolean)?.boolValue ?? false) })
        loadAll()
    }
    func stop() { subs.forEach { $0.cancel() }; subs.removeAll() }

    private func loadAll() {
        Task { @MainActor in
            selectedAiProvider = (try? await store.getSelectedChatProvider()) ?? "chatgpt"
            sendListeningHistory = (try? await store.getSendListeningHistory())?.boolValue ?? false
            values["lastfm"] = (try? await store.getLastFmUsername()) ?? ""
            values["listenbrainz"] = (try? await store.getListenBrainzToken()) ?? ""
            values["discogs"] = (try? await store.getDiscogsToken()) ?? ""
            values["soundcloud"] = (try? await store.getSoundCloudClientId()) ?? ""
            values["ticketmaster"] = (try? await store.getTicketmasterApiKey()) ?? ""
            values["seatgeek"] = (try? await store.getSeatGeekClientId()) ?? ""
            for p in Self.aiProviders {
                values[p] = (try? await store.getAiProviderApiKey(providerId: p)) ?? ""
                aiModels[p] = (try? await store.getAiProviderModel(providerId: p)) ?? ""
            }
            values["bandsintown"] = (try? await store.getAiProviderApiKey(providerId: "bandsintown")) ?? ""
            values["songkick"] = (try? await store.getAiProviderApiKey(providerId: "songkick")) ?? ""

            // Mobile capability filter — mirror PluginManager.plugins (which keeps
            // capabilities["mobile"] != false). loadPlugins() is already
            // mobile-filtered; allLoadedPlugins surfaces the hidden ids.
            let all = (try? await container.loadAllPlugins()) ?? []
            var blocked = Set(all.filter { $0.capabilities["mobile"]?.boolValue == false }.map { $0.id })
            blocked.formUnion(["youtube", "ollama"])
            mobileBlocked = blocked
            await refreshLoadedPlugins()

            spotifyClientId = (try? await store.getSpotifyClientId()) ?? ""
            // Read Spotify connection up-front so the load-time recompute gates
            // it correctly (the connected-flow watcher only fires later).
            spotifyConnected = (try? await store.getSpotifyAccessToken()) != nil

            // Resolver intent (active_resolvers): empty stored = all catalogued.
            // Whether each actually reaches the priority list is gated by
            // isUsable (connected / no-auth) inside recomputeResolvers.
            let catalogIds = resolverCatalog.map { $0.id }
            let storedActive = (try? await store.getActiveResolvers()) ?? []
            // Fresh install: only no-auth resolvers are enabled by default (matches
            // desktop's ['bandcamp','localfiles']); auth-gated ones (Spotify /
            // SoundCloud) join the enabled set when connected. A NEW marketplace
            // resolver on an EXISTING install lands in the disabled section with an
            // Enable button (we can't distinguish "new" from "user-disabled" here,
            // so we don't auto-enable it — avoids un-disabling a user's choice).
            let defaultActive = Set(catalogIds.filter { !requiresConnection($0) })
            activeResolvers = storedActive.isEmpty ? defaultActive : Set(storedActive).intersection(catalogIds)
            resolverOrder = ((try? await store.getResolverOrder())?.filter { resolverService($0) != nil }) ?? []
            resolversReady = true
            recomputeResolvers()
        }
    }

    // ── Connect status ────────────────────────────────────────────────
    func isConnected(_ id: String) -> Bool {
        switch id {
        // A stale token alone (e.g. iCloud-Keychain-synced) isn't a usable
        // connection without the BYO Client ID — both are required.
        case "spotify": return spotifyConnected && !spotifyClientId.isEmpty
        case "librefm": return libreFmConnected
        // Scrobbling needs the session key (OAuth), not the read-only username.
        case "lastfm": return lastFmConnected
        case "localfiles", "bandcamp", "applemusic": return true   // no key needed / MusicKit at play time
        // No-key meta (Discogs) + any uncataloged loaded plugin (Wikipedia,
        // Achordion): they work without credentials, so "active" == enabled.
        case "discogs": return !disabledPlugins.contains(id)
        default:
            if PCServices.find(id) == nil { return !disabledPlugins.contains(id) }
            return !(values[id]?.isEmpty ?? true)
        }
    }

    // ── Resolver priority ─────────────────────────────────────────────
    func moveResolver(_ from: Int, _ to: Int) {
        guard from >= 0, to >= 0, from < resolverOrder.count, to < resolverOrder.count else { return }
        let item = resolverOrder.remove(at: from)
        resolverOrder.insert(item, at: to)
        persistResolvers()
    }

    func isResolverEnabled(_ id: String) -> Bool { activeResolvers.contains(id) }

    /// Toggle user intent; recompute rebuilds the usable priority list + persists.
    /// A not-connected auth-gated resolver won't reach the list — connecting it
    /// (which calls recompute) is what enables it.
    ///
    /// Gates ONLY the resolver, via `active_resolvers`: the coordinator skips
    /// natives not in the set, `ResolverScoring` filters cached badges/selection
    /// on it, and `axeResolverIds` gates `.axe` on it (active is seeded to all,
    /// so removing one leaves the rest on). We deliberately do NOT also write
    /// `disabled_plugins` — that key gates the METADATA cascade
    /// (`getDisabledProviders`), and disabling a resolver should not silence that
    /// source's album-art metadata. A resolver toggle ≠ a service kill. This
    /// keeps resolver-disable and metadata-disable independent, matching Android
    /// (#213).
    func setResolverEnabled(_ id: String, _ enabled: Bool) {
        if enabled { activeResolvers.insert(id) } else { activeResolvers.remove(id) }
        recomputeResolvers()
    }

    /// Rebuild resolverOrder = enabled-usable resolvers (preserving the current
    /// order; canonical-insert any newly-usable one). Persists once load is done.
    func recomputeResolvers() {
        // Auth-gated resolvers that aren't usable (no connection / no key) must
        // not linger in the enabled set — desktop drops them on disconnect. This
        // also clears a stale-token Spotify on launch when no Client ID is set.
        for id in resolverCatalog.map({ $0.id }) where requiresConnection(id) && !isUsable(id) {
            activeResolvers.remove(id)
        }
        var order = resolverOrder.filter { isEnabledUsable($0) && resolverService($0) != nil }
        for id in resolverCatalog.map({ $0.id }) where isEnabledUsable(id) && !order.contains(id) {
            order = container.resolverScoring.insertInCanonicalOrder(order: order, newId: id)
        }
        resolverOrder = order
        if resolversReady { persistResolvers() }
    }

    private func persistResolvers() {
        let active = Array(activeResolvers); let order = resolverOrder
        Task {
            try? await store.setActiveResolvers(resolvers: active)
            try? await store.setResolverOrder(order: order)
        }
    }

    // ── BYO key/token writes (routes to the right SettingsStore method) ─
    func setValue(_ id: String, _ value: String, secret: String? = nil) {
        values[id] = value
        if requiresConnection(id) {            // soundcloud: creds gate usability
            if !value.isEmpty { activeResolvers.insert(id) }
            recomputeResolvers()
        }
        Task {
            switch id {
            case "lastfm": try? await store.setLastFmUsername(username: value)
            case "listenbrainz":
                try? await store.setListenBrainzToken(token: value)
                // Derive + persist the username the public `createdfor` weekly
                // endpoint needs (mirrors desktop/Android validateToken). Without
                // this the token saves but Weekly Jams/Exploration never load.
                if value.isEmpty {
                    try? await store.clearListenBrainzUsername()
                } else if let username = try? await container.listenBrainzClient.validateToken(token: value),
                          !username.isEmpty {
                    try? await store.setListenBrainzUsername(username: username)
                }
            case "discogs": try? await store.setDiscogsToken(token: value)
            case "ticketmaster": try? await store.setTicketmasterApiKey(key: value)
            case "seatgeek": try? await store.setSeatGeekClientId(id: value)
            case "soundcloud": try? await store.setSoundCloudCredentials(clientId: value, clientSecret: secret ?? "")
            case "chatgpt", "claude", "gemini", "bandsintown", "songkick":
                try? await store.setAiProviderApiKey(providerId: id, apiKey: value)
            default: break
            }
        }
    }
    func setAiModel(_ id: String, _ model: String) {
        aiModels[id] = model
        Task { try? await store.setAiProviderModel(providerId: id, model: model) }
    }
    func setSelectedAiProvider(_ p: String) {
        selectedAiProvider = p
        Task { try? await store.setSelectedChatProvider(providerId: p) }
    }

    func setSendListeningHistory(_ on: Bool) {
        sendListeningHistory = on
        Task { try? await store.setSendListeningHistory(enabled: on) }
    }

    // Dynamic model lists fetched live from each provider's API (the static
    // `aiModelOptions` below is only a fallback when there's no key / fetch fails).
    var dynamicModels: [String: [String]] = [:]
    var pluginDefault: [String: String] = [:]
    var loadingModels: Set<String> = []
    func loadModels(_ id: String) {
        guard ["chatgpt", "claude", "gemini"].contains(id), !loadingModels.contains(id) else { return }
        // No key gate: static `select` plugins (e.g. claude) expose their curated
        // options without one; dynamic-select plugins return [] when the key's blank.
        let key = values[id] ?? ""
        loadingModels.insert(id)
        Task { @MainActor in
            if let def = try? await container.chatModelDefault(providerId: id), !def.isEmpty { pluginDefault[id] = def }
            let models = (try? await container.chatListModels(providerId: id, apiKey: key)) ?? []
            if !models.isEmpty { dynamicModels[id] = models }
            loadingModels.remove(id)
        }
    }
    /// Live model list if fetched, else the static fallback.
    func modelOptions(_ id: String) -> [String] {
        if let dyn = dynamicModels[id], !dyn.isEmpty { return dyn }
        return Self.aiModelOptions[id] ?? []
    }
    /// Picker selection: the user's stored choice, else the plugin's `default`
    /// (desktop: `metaServiceConfigs[id].model || modelSetting.default`).
    func selectedModel(_ id: String) -> String {
        let v = aiModels[id] ?? ""
        return v.isEmpty ? (pluginDefault[id] ?? "") : v
    }
    func setTheme(_ m: String) { themeMode = m; Task { try? await store.setThemeMode(mode: m) } }
    func setScrobbling(_ b: Bool) { scrobblingEnabled = b; Task { try? await store.setScrobblingEnabled(enabled: b) } }
    func setPersistQueue(_ b: Bool) { persistQueue = b; Task { try? await store.setPersistQueue(enabled: b) } }

    // ── Spotify OAuth (BYO Client ID) ─────────────────────────────────
    func setSpotifyClientId(_ id: String) {
        let trimmed = id.trimmingCharacters(in: .whitespacesAndNewlines)
        spotifyClientId = trimmed
        Task { try? await container.setSpotifyClientId(clientId: trimmed) }
        recomputeResolvers()   // a Client ID gain/loss flips Spotify's usability
    }
    func connectSpotify() {
        guard !spotifyClientId.isEmpty else {
            spotifyError = "Enter your Spotify Client ID first."; return
        }
        Task { @MainActor in
            do {
                let m = IosOAuthManager(); oauthManager = m
                let cfg = OAuthConfig.spotify(clientId: spotifyClientId)
                let r = try await m.authorize(cfg)
                try await container.connectSpotify(code: r.code, codeVerifier: r.codeVerifier)
                spotifyError = nil
                spotifyConnected = true              // before recompute, so the prune keeps it
                activeResolvers.insert("spotify")    // connecting enables it (desktop parity)
                setPluginEnabled("spotify", true)    // clear any prior disabled flag
                recomputeResolvers()
            } catch { spotifyError = error.localizedDescription }
            oauthManager = nil
        }
    }
    func disconnectSpotify() {
        activeResolvers.remove("spotify")   // auth-gated removal (desktop parity)
        Task { try? await container.disconnectSpotify() }
        recomputeResolvers()
    }

    // ── Last.fm (web-auth token → auth.getSession session key) ────────
    func connectLastFm() {
        lastFmBusy = true; lastFmError = nil
        Task { @MainActor in
            do {
                let m = IosOAuthManager(); oauthManager = m
                let token = try await m.authorizeLastFm(apiKey: container.appConfig.lastFmApiKey)
                let ok = (try? await container.connectLastFm(token: token))?.boolValue ?? false
                lastFmError = ok ? nil : "Couldn't connect to Last.fm. Please try again."
            } catch {
                // A user cancel isn't an error worth surfacing.
                if case OAuthError.cancelled = error { lastFmError = nil }
                else { lastFmError = error.localizedDescription }
            }
            oauthManager = nil
            lastFmBusy = false
        }
    }
    func disconnectLastFm() { Task { try? await container.disconnectLastFm() } }

    // ── Libre.fm (username + password → session) ──────────────────────
    func connectLibreFm() {
        guard !libreFmUser.isEmpty, !libreFmPass.isEmpty else { return }
        libreFmBusy = true; libreFmError = nil
        Task { @MainActor in
            let ok = (try? await container.connectLibreFm(username: libreFmUser, password: libreFmPass))?.boolValue ?? false
            if ok { libreFmPass = ""; libreFmError = nil }
            else { libreFmError = "Couldn't sign in — check your username and password." }
            libreFmBusy = false
        }
    }
    func disconnectLibreFm() { Task { try? await container.disconnectLibreFm() } }

    // ── Plugin marketplace ────────────────────────────────────────────
    func syncPlugins() {
        guard syncState != .syncing else { return }
        syncState = .syncing
        Task { @MainActor in
            if let r = try? await container.syncPluginsNow() {
                let changed = Int(r.added.count) + Int(r.updated.count)
                syncState = .done(changed > 0 ? "\(changed) updated" : "Up to date")
                await refreshLoadedPlugins()   // same (mobile-filtered) source as initial load
            } else { syncState = .failed }
            try? await Task.sleep(nanoseconds: 4_000_000_000)
            syncState = .idle
        }
    }
}

// MARK: - Settings screen

struct SettingsView: View {
    @State private var model = SettingsViewModel()
    @State private var tab = 0
    @State private var configService: PCService?
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        VStack(spacing: 0) {
            PCTopBar(title: "Settings", leading: .back, onLeading: { dismiss() })
            PCTabs(tabs: ["Plug-ins", "General", "About"], selection: $tab)
            ScrollView {
                switch tab {
                case 0: PlugInsTab(model: model, onConfig: { configService = $0 })
                case 1: GeneralTab(model: model)
                default: AboutTab()
                }
            }
        }
        .toolbar(.hidden, for: .navigationBar)
        .sheet(item: $configService) { svc in
            PluginConfigSheet(service: svc, model: model)
        }
        .onAppear { model.start() }
        .onDisappear { model.stop() }
    }
}

// MARK: - Plug-ins tab

private struct PlugInsTab: View {
    @Bindable var model: SettingsViewModel
    let onConfig: (PCService) -> Void
    @State private var draggingResolver: String?

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            sectionLabel("Content Resolvers")
            Text("Drag priority sets which source plays first.")
                .font(.system(size: 12)).foregroundStyle(PC.fg3).padding(.horizontal, 20).padding(.bottom, 6)
            VStack(spacing: 0) {
                ForEach(Array(model.resolverOrder.enumerated()), id: \.element) { i, id in
                    if let svc = model.resolverService(id) {
                        resolverRow(svc, index: i)
                            .opacity(draggingResolver == id ? 0.4 : 1)
                            .draggable(id) {
                                resolverRow(svc, index: i)
                                    .frame(width: 300)
                                    .background(PC.bgElevated)
                                    .onAppear { draggingResolver = id }
                            }
                            .dropDestination(for: String.self) { dropped, _ in
                                draggingResolver = nil
                                guard let dragged = dropped.first, dragged != id,
                                      let from = model.resolverOrder.firstIndex(of: dragged),
                                      let to = model.resolverOrder.firstIndex(of: id) else { return false }
                                withAnimation { model.moveResolver(from, to) }
                                return true
                            } isTargeted: { _ in }
                        if i < model.resolverOrder.count - 1 { Divider().padding(.leading, 64) }
                    }
                }
            }

            if !model.disabledResolvers.isEmpty {
                Text("DISABLED").font(.system(size: 10, weight: .bold)).tracking(1.2).foregroundStyle(PC.fg3)
                    .padding(.horizontal, 20).padding(.top, 14).padding(.bottom, 4)
                VStack(spacing: 0) {
                    ForEach(model.disabledResolvers, id: \.self) { id in
                        if let svc = model.resolverService(id) { disabledResolverRow(svc) }
                    }
                }
            }

            sectionLabel("Meta Services")
            serviceGrid(model.displayServices(.meta), columns: 3)

            sectionLabel("Concerts & Events")
            serviceGrid(model.displayServices(.concert), columns: 2)

            pluginUpdates
        }
        .padding(.bottom, 130)
    }

    private func resolverRow(_ svc: PCService, index: Int) -> some View {
        HStack(spacing: 12) {
            Text("\(index + 1)").font(.system(size: 13, weight: .bold, design: .monospaced)).foregroundStyle(PC.fg3).frame(width: 18)
            tileIcon(svc, size: 34)
            VStack(alignment: .leading, spacing: 1) {
                Text(svc.name).font(.system(size: 15, weight: .medium)).foregroundStyle(PC.fg1)
                Text(model.isConnected(svc.id) ? "Connected" : "Not connected")
                    .font(.system(size: 12)).foregroundStyle(model.isConnected(svc.id) ? Color(uiColor: UIColor(hex: 0x10B981)) : PC.fg3)
            }
            Spacer(minLength: 0)
            Button { onConfig(svc) } label: { Image(systemName: "gearshape").foregroundStyle(PC.fg2) }.buttonStyle(.plain)
            // Drag handle — long-press a row and drag to reorder priority.
            Image(systemName: "line.3.horizontal").font(.system(size: 16)).foregroundStyle(PC.fg3)
        }
        .padding(.horizontal, 20).padding(.vertical, 9)
        .contentShape(Rectangle())
    }

    private func disabledResolverRow(_ svc: PCService) -> some View {
        // Auth-gated + not connected → "Connect" (opens config); otherwise the
        // user disabled it → "Enable" re-adds it directly.
        let needsConnect = model.requiresConnection(svc.id) && !model.isConnected(svc.id)
        return HStack(spacing: 12) {
            tileIcon(svc, size: 34).opacity(0.4).grayscale(1)
            VStack(alignment: .leading, spacing: 1) {
                Text(svc.name).font(.system(size: 15, weight: .medium)).foregroundStyle(PC.fg3)
                if needsConnect {
                    Text("Connect to enable").font(.system(size: 12)).foregroundStyle(PC.fg3)
                }
            }
            Spacer(minLength: 0)
            Button { onConfig(svc) } label: { Image(systemName: "gearshape").foregroundStyle(PC.fg3) }.buttonStyle(.plain)
            // Fixed-width action so the gear sits at the same x on every row
            // regardless of the "Connect" vs "Enable" label width.
            Button(needsConnect ? "Connect" : "Enable") {
                if needsConnect { onConfig(svc) } else { model.setResolverEnabled(svc.id, true) }
            }
            .font(.system(size: 14, weight: .medium)).foregroundStyle(PC.accent).buttonStyle(.plain)
            .frame(width: 72, alignment: .trailing)
        }
        .padding(.horizontal, 20).padding(.vertical, 9)
    }

    private func serviceGrid(_ services: [PCService], columns: Int) -> some View {
        LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 12), count: columns), spacing: 12) {
            ForEach(services.filter { !model.mobileBlocked.contains($0.id) }) { svc in
                Button { onConfig(svc) } label: {
                    VStack(spacing: 6) {
                        ZStack(alignment: .topTrailing) {
                            tileIcon(svc, size: 48)
                            if model.isConnected(svc.id) {
                                Image(systemName: "checkmark.circle.fill").font(.system(size: 15))
                                    .foregroundStyle(Color(uiColor: UIColor(hex: 0x10B981)), .white).offset(x: 5, y: -5)
                            }
                        }
                        .opacity(model.isConnected(svc.id) ? 1 : 0.55)
                        Text(svc.name).font(.system(size: 11)).foregroundStyle(PC.fg1).lineLimit(1)
                    }
                }
                .buttonStyle(.plain)
            }
        }
        .padding(.horizontal, 20).padding(.bottom, 8)
    }

    private var pluginUpdates: some View {
        HStack {
            VStack(alignment: .leading, spacing: 2) {
                Text("Plugin Updates").font(.system(size: 15, weight: .medium)).foregroundStyle(PC.fg1)
                Text("\(model.pluginCount) plugins loaded").font(.system(size: 12)).foregroundStyle(PC.fg3)
            }
            Spacer()
            Button { model.syncPlugins() } label: {
                switch model.syncState {
                case .idle: Text("Check for updates").font(.system(size: 14, weight: .medium)).foregroundStyle(PC.accent)
                case .syncing: HStack(spacing: 6) { ProgressView().controlSize(.small); Text("Checking…").font(.system(size: 14)).foregroundStyle(PC.fg2) }
                case .done(let s): Text("✓ \(s)").font(.system(size: 14)).foregroundStyle(Color(uiColor: UIColor(hex: 0x10B981)))
                case .failed: Text("⚠ Failed").font(.system(size: 14)).foregroundStyle(PC.error)
                }
            }
            .buttonStyle(.plain).disabled(model.syncState == .syncing)
        }
        .padding(.horizontal, 20).padding(.vertical, 14)
        .overlay(Rectangle().fill(PC.border).frame(height: 1), alignment: .top)
        .padding(.top, 10)
    }

    private func sectionLabel(_ t: String) -> some View {
        Text(t.uppercased()).font(.system(size: 11, weight: .bold)).tracking(1.4).foregroundStyle(PC.fg3)
            .padding(.horizontal, 20).padding(.top, 18).padding(.bottom, 8)
    }
    private func tileIcon(_ svc: PCService, size: CGFloat) -> some View {
        RoundedRectangle(cornerRadius: size * 0.22, style: .continuous)
            .fill(Color(uiColor: UIColor(hex: svc.color)))
            .frame(width: size, height: size)
            .overlay(Image(systemName: svc.icon).font(.system(size: size * 0.42, weight: .semibold)).foregroundStyle(.white))
    }
}

// MARK: - Plugin config sheet

private struct PluginConfigSheet: View {
    let service: PCService
    @Bindable var model: SettingsViewModel
    @Environment(\.dismiss) private var dismiss
    @State private var draft = ""
    @State private var secretDraft = ""
    @State private var spotifyIdDraft = ""
    @State private var scanner = MediaLibraryScanner.shared
    @State private var showImporter = false

    private var isAi: Bool { ["chatgpt", "claude", "gemini"].contains(service.id) }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    HStack(spacing: 12) {
                        RoundedRectangle(cornerRadius: 12, style: .continuous).fill(Color(uiColor: UIColor(hex: service.color)))
                            .frame(width: 52, height: 52)
                            .overlay(Image(systemName: service.icon).font(.system(size: 22, weight: .semibold)).foregroundStyle(.white))
                        VStack(alignment: .leading, spacing: 3) {
                            Text(service.name).font(.system(size: 18, weight: .semibold))
                            Text(model.isConnected(service.id) ? "Connected" : "Not connected")
                                .font(.system(size: 13)).foregroundStyle(model.isConnected(service.id) ? .green : .secondary)
                        }
                        Spacer()
                    }
                }

                // The enable toggle only makes sense once a resolver is usable
                // (no-auth, or connected). A not-connected Spotify/SoundCloud
                // shows just its Connect/credentials section — connecting is what
                // enables it.
                if service.kind == .resolver && model.isUsable(service.id) {
                    Section {
                        Toggle("Enable resolver", isOn: Binding(
                            get: { model.isResolverEnabled(service.id) },
                            set: { model.setResolverEnabled(service.id, $0) }))
                    } footer: {
                        Text("Include in search and playback. Disabling removes it from the priority order.")
                    }
                }

                // Enable/disable for non-resolver plugins (meta + concert + any
                // marketplace plugin). Resolvers use the priority-list toggle above.
                if service.kind != .resolver {
                    Section {
                        Toggle("Enabled", isOn: Binding(
                            get: { !model.disabledPlugins.contains(service.id) },
                            set: { model.setPluginEnabled(service.id, $0) }))
                    } footer: {
                        Text(service.kind == .meta
                            ? "Turn off to stop using this plugin for metadata, bios, scrobbling, or AI."
                            : "Turn off to stop using this plugin.")
                    }
                }

                // Scrobbling (#193): the "send my plays" toggle lives in each
                // scrobbler's sheet (Android parity) rather than buried in
                // General. Shown once the service is connected; backed by the
                // single global scrobblingEnabled flag.
                if ["lastfm", "listenbrainz", "librefm"].contains(service.id) && model.isConnected(service.id) {
                    Section {
                        Toggle("Scrobble my plays", isOn: Binding(
                            get: { model.scrobblingEnabled },
                            set: { model.setScrobbling($0) }))
                    } footer: {
                        Text("Send your listening history to connected scrobblers (Last.fm, ListenBrainz, Libre.fm).")
                    }
                }

                switch service.id {
                case "spotify": spotifySection
                case "lastfm": lastFmSection
                case "librefm": libreFmSection
                case "applemusic": infoSection("Apple Music is authorized at playback time via MusicKit. No key needed.")
                case "localfiles": localFilesSection
                case "bandcamp": infoSection("Bandcamp needs no credentials — it resolves and opens tracks in the browser.")
                // No key required (matches Android's toggle-only Discogs) — the
                // public API works unauthenticated; a token would only raise rate
                // limits, which we don't surface.
                case "discogs": infoSection("Discogs provides artist bios and images from the community database — no API key required.")
                // Uncataloged loaded plugins (e.g. Wikipedia, Achordion) have no
                // BYO-key config — show read-only info instead of a stray key field.
                case let id where PCServices.find(id) == nil: pluginInfoSection
                default: keySection
                }
            }
            .navigationTitle("Configure").navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } } }
            .onAppear {
                draft = model.values[service.id] ?? ""
                spotifyIdDraft = model.spotifyClientId
            }
        }
    }

    @ViewBuilder private var localFilesSection: some View {
        Section {
            Text("Play local songs two ways: scan your device's Music library (downloaded, non-DRM tracks only), or import audio files from the Files app — iOS sandboxing hides Files-app audio from the library scan, so a downloaded WAV/MP3 needs Import.")
                .font(.system(size: 13)).foregroundStyle(PC.fg2)
            switch scanner.phase {
            case .requesting:
                HStack(spacing: 8) { ProgressView().controlSize(.small); Text("Requesting access…") }
            case .scanning(let done, let total):
                HStack(spacing: 8) { ProgressView().controlSize(.small); Text("Scanning… \(done)/\(total)") }
            case .importing(let done, let total):
                HStack(spacing: 8) { ProgressView().controlSize(.small); Text("Importing… \(done)/\(total)") }
            case .imported(let n):
                Text("Imported \(n) file\(n == 1 ? "" : "s").").font(.system(size: 13)).foregroundStyle(PC.fg3)
            case .denied:
                Text("Music access denied. Enable it in iOS Settings › Privacy › Media & Apple Music.")
                    .font(.system(size: 13)).foregroundStyle(.red)
            case .failed(let m):
                Text("Failed: \(m)").font(.system(size: 13)).foregroundStyle(.red)
            case .done(let n):
                Text("Scanned \(n) tracks.").font(.system(size: 13)).foregroundStyle(PC.fg3)
            case .idle:
                if scanner.libraryCount > 0 {
                    Text("\(scanner.libraryCount) local tracks in your library.").font(.system(size: 13)).foregroundStyle(PC.fg3)
                }
            }
            Button { scanner.scan() } label: {
                Label(scanner.libraryCount > 0 ? "Re-scan Library" : "Scan Music Library",
                      systemImage: "arrow.triangle.2.circlepath")
            }
            .disabled(localFilesBusy)
            Button { showImporter = true } label: {
                Label("Import Files…", systemImage: "folder.badge.plus")
            }
            .disabled(localFilesBusy)
            .fileImporter(isPresented: $showImporter,
                          allowedContentTypes: [.audio],
                          allowsMultipleSelection: true) { result in
                if case .success(let urls) = result, !urls.isEmpty { scanner.importFiles(urls) }
            }
        }
        .onAppear { scanner.refreshCount() }
    }

    private var localFilesBusy: Bool {
        switch scanner.phase {
        case .scanning, .requesting, .importing: return true
        default: return false
        }
    }

    @ViewBuilder private var lastFmSection: some View {
        Section {
            if model.lastFmConnected {
                Label("Last.fm connected", systemImage: "checkmark.circle.fill").foregroundStyle(.green)
                Button("Disconnect", role: .destructive) { model.disconnectLastFm() }
            } else {
                Button { model.connectLastFm() } label: {
                    HStack {
                        if model.lastFmBusy { ProgressView().controlSize(.small) }
                        Text("Connect Last.fm").bold().frame(maxWidth: .infinity)
                    }
                }
                .buttonStyle(.borderedProminent).tint(Color(uiColor: UIColor(hex: 0xD51007)))
                .disabled(model.lastFmBusy)
                .listRowInsets(EdgeInsets(top: 6, leading: 16, bottom: 6, trailing: 16))
                if let e = model.lastFmError { Text(e).font(.caption).foregroundStyle(.red) }
            }
        } header: { Text("Connection") } footer: {
            Text("Sign in through Last.fm to authorize scrobbling. Opens Last.fm in a secure in-app browser; Parachord never sees your password.")
        }
    }

    @ViewBuilder private var libreFmSection: some View {
        Section {
            if model.libreFmConnected {
                Label("Libre.fm connected", systemImage: "checkmark.circle.fill").foregroundStyle(.green)
                Button("Disconnect", role: .destructive) { model.disconnectLibreFm() }
            } else {
                TextField("Username", text: $model.libreFmUser)
                    .textInputAutocapitalization(.never).autocorrectionDisabled()
                SecureField("Password", text: $model.libreFmPass)
                Button { model.connectLibreFm() } label: {
                    HStack {
                        if model.libreFmBusy { ProgressView().controlSize(.small) }
                        Text("Sign in").bold().frame(maxWidth: .infinity)
                    }
                }
                .buttonStyle(.borderedProminent).tint(Color(uiColor: UIColor(hex: 0x4CAF50)))
                .disabled(model.libreFmUser.isEmpty || model.libreFmPass.isEmpty || model.libreFmBusy)
                .listRowInsets(EdgeInsets(top: 6, leading: 16, bottom: 6, trailing: 16))
                if let e = model.libreFmError { Text(e).font(.caption).foregroundStyle(.red) }
            }
        } header: { Text("Connection") } footer: {
            Text("Sign in with your Libre.fm account — the free, open scrobbling service. Password is sent once to get a session key and never stored.")
        }
    }

    @ViewBuilder private var spotifySection: some View {
        Section {
            SecureField("Client ID", text: $spotifyIdDraft)
                .textInputAutocapitalization(.never).autocorrectionDisabled()
                .font(.system(.body, design: .monospaced))
            Button("Save Client ID") { model.setSpotifyClientId(spotifyIdDraft) }
                .disabled(spotifyIdDraft.trimmingCharacters(in: .whitespaces).isEmpty || spotifyIdDraft == model.spotifyClientId)
            if !model.spotifyClientId.isEmpty {
                Button("Clear", role: .destructive) { spotifyIdDraft = ""; model.setSpotifyClientId("") }
            }
            Link(destination: URL(string: "https://developer.spotify.com/dashboard")!) {
                Label("Get a Client ID — developer.spotify.com →", systemImage: "key.fill").font(.system(size: 14))
            }
        } header: { Text("Spotify Developer Client ID") } footer: {
            Text("Parachord ships no Spotify key. Create a free app at developer.spotify.com/dashboard, add the redirect URI parachord://auth/callback/spotify, then paste the Client ID. Spotify Premium is required for playback.")
        }

        Section("Connection") {
            if model.spotifyConnected {
                Label("Spotify Premium connected", systemImage: "checkmark.circle.fill").foregroundStyle(.green)
                Button("Disconnect", role: .destructive) { model.disconnectSpotify() }
            } else {
                Button { model.connectSpotify() } label: {
                    Text("Connect Spotify").bold().frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent).tint(Color(uiColor: UIColor(hex: 0x1DB954)))
                .disabled(model.spotifyClientId.isEmpty)
                .listRowInsets(EdgeInsets(top: 6, leading: 16, bottom: 6, trailing: 16))
                if model.spotifyClientId.isEmpty {
                    Text("Save your Client ID above to enable Connect.").font(.caption).foregroundStyle(.secondary)
                }
                if let e = model.spotifyError { Text(e).font(.caption).foregroundStyle(.red) }
            }
        }
    }

    @ViewBuilder private var keySection: some View {
        Section {
            SecureField(keyPlaceholder, text: $draft)
                .textInputAutocapitalization(.never).autocorrectionDisabled()
            if service.id == "soundcloud" {
                SecureField("Client Secret", text: $secretDraft).textInputAutocapitalization(.never).autocorrectionDisabled()
            }
            Button {
                model.setValue(service.id, draft, secret: service.id == "soundcloud" ? secretDraft : nil)
            } label: {
                Text("Save").bold().frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
            .tint(PC.accent)
            .disabled(draft.isEmpty)
            .listRowInsets(EdgeInsets(top: 6, leading: 16, bottom: 6, trailing: 16))
            if !(model.values[service.id]?.isEmpty ?? true) {
                Button("Clear", role: .destructive) { draft = ""; model.setValue(service.id, "") }
            }
            if let url = PCServices.helpUrl(service.id), let u = URL(string: url) {
                Link(destination: u) {
                    Label("Get a key — \(PCServices.helpLabel(service.id))", systemImage: "key.fill").font(.system(size: 14))
                }
            }
        } header: { Text(keyHeader) } footer: { Text(keyFooter) }

        if isAi {
            Section("Model") {
                Picker("Model", selection: Binding(get: { model.selectedModel(service.id) }, set: { model.setAiModel(service.id, $0) })) {
                    Text("Default").tag("")
                    ForEach(model.modelOptions(service.id), id: \.self) { Text($0).tag($0) }
                }
                .task(id: model.values[service.id] ?? "") { model.loadModels(service.id) }
                if model.loadingModels.contains(service.id) {
                    HStack(spacing: 8) {
                        ProgressView().controlSize(.small)
                        Text("Loading models…").font(.caption).foregroundStyle(.secondary)
                    }
                }
                Toggle("Use as DJ provider", isOn: Binding(
                    get: { model.selectedAiProvider == service.id },
                    set: { if $0 { model.setSelectedAiProvider(service.id) } }))
            }
            Section {
                Toggle("Share my listening history", isOn: Binding(
                    get: { model.sendListeningHistory },
                    set: { model.setSendListeningHistory($0) }))
            } footer: {
                Text("Lets the DJ use your recent listening, top artists, and library to personalize replies. Applies to all AI providers.")
            }
        }
    }

    private func infoSection(_ s: String) -> some View {
        Section { Text(s).font(.system(size: 14)).foregroundStyle(.secondary) }
    }

    /// Read-only detail for a loaded plugin that has no user configuration
    /// (version + capabilities), mirroring Android's plugin detail sheet.
    @ViewBuilder private var pluginInfoSection: some View {
        let plugin = model.loadedPlugins.first { $0.id == service.id }
        Section {
            if let plugin {
                HStack { Text("Version"); Spacer(); Text("v\(plugin.version)").foregroundStyle(.secondary) }
                if !plugin.caps.isEmpty {
                    HStack(alignment: .top) {
                        Text("Capabilities"); Spacer()
                        Text(plugin.caps.joined(separator: ", "))
                            .foregroundStyle(.secondary).multilineTextAlignment(.trailing)
                    }
                }
            }
        } header: { Text("Plugin") } footer: {
            Text("This plugin runs automatically and needs no configuration.")
        }
    }

    private var keyPlaceholder: String {
        switch service.id {
        case "lastfm": return "Last.fm username"
        case "listenbrainz": return "ListenBrainz user token"
        case "seatgeek": return "SeatGeek client ID"
        case "soundcloud": return "Client ID"
        default: return "API key"
        }
    }
    private var keyHeader: String { service.id == "lastfm" ? "Username" : "Credentials" }
    private var keyFooter: String {
        switch service.id {
        case "lastfm": return "Enables Top Songs/Albums/Artists, recent plays, and charts."
        case "listenbrainz": return "Find it at listenbrainz.org/settings. Enables playlists, follows, and recent plays."
        case "chatgpt", "claude", "gemini": return "Bring your own key for Shuffleupagus (DJ chat) and AI recommendations."
        case "ticketmaster", "seatgeek", "bandsintown", "songkick": return "Powers the Concerts page + On Tour indicators."
        case "discogs": return "Supplements artist/album metadata."
        case "soundcloud": return "Client ID + Secret enable SoundCloud streaming."
        default: return ""
        }
    }
}

// MARK: - General tab

private struct GeneralTab: View {
    @Bindable var model: SettingsViewModel
    @State private var sync = SyncModel()
    @State private var configProvider: ProviderConfigTarget?
    @State private var showMigrationPreview = false
    private let themes = ["system", "light", "dark"]

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            label("Appearance")
            Picker("Theme", selection: Binding(get: { model.themeMode }, set: { model.setTheme($0) })) {
                ForEach(themes, id: \.self) { Text($0.capitalized).tag($0) }
            }.pickerStyle(.segmented).padding(.horizontal, 20)

            label("Playback")
            Toggle(isOn: Binding(get: { model.persistQueue }, set: { model.setPersistQueue($0) })) {
                VStack(alignment: .leading, spacing: 2) {
                    Text("Remember queue").font(.system(size: 15)).foregroundStyle(PC.fg1)
                    Text("Restore your queue and current track when the app restarts.")
                        .font(.system(size: 12)).foregroundStyle(PC.fg3)
                }
            }
            .tint(PC.accent)
            .padding(.horizontal, 20).padding(.top, 4)

            syncSection

            label("Sync engine")
            Button { showMigrationPreview = true } label: {
                HStack(alignment: .top) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Use new sync").font(.system(size: 15)).foregroundStyle(PC.fg1)
                        Text("Preview what switching to the new sync engine would change — then accept, report a problem, or cancel. Nothing is armed until you accept.")
                            .font(.system(size: 12)).foregroundStyle(PC.fg3)
                            .multilineTextAlignment(.leading).fixedSize(horizontal: false, vertical: true)
                    }
                    Spacer(minLength: 12)
                    Text("Preview").font(.system(size: 14, weight: .semibold)).foregroundStyle(PC.accent)
                }
            }
            .buttonStyle(.plain)
            .padding(.horizontal, 20).padding(.top, 8)
        }
        .padding(.bottom, 130)
        .task { await sync.load() }
        // Keep-or-remove on disable (#194 / cross-provider dedup). Dismissing
        // without a choice defaults to keep (the provider is disabled either way).
        .confirmationDialog(
            "Disable \(syncProviderName(sync.pendingDisable)) sync",
            isPresented: Binding(get: { sync.pendingDisable != nil },
                                 set: { presented in if !presented { sync.confirmDisable(removeItems: false) } }),
            titleVisibility: .visible
        ) {
            Button("Keep synced items") { sync.confirmDisable(removeItems: false) }
            Button("Remove from this device", role: .destructive) { sync.confirmDisable(removeItems: true) }
        } message: {
            Text("Remove the items that came only from this service? Items also synced from another service are kept.")
        }
        .onDisappear { sync.stopWatching() }
        .sheet(item: $configProvider) { target in
            ProviderSyncConfigSheet(target: target)
        }
        .sheet(isPresented: $showMigrationPreview) { MigrationPreviewView() }
    }

    private func syncProviderName(_ id: String?) -> String {
        switch id {
        case "spotify": return "Spotify"
        case "listenbrainz": return "ListenBrainz"
        case "applemusic": return "Apple Music"
        default: return "service"
        }
    }

    // Library Sync (#194, Phase 1). Per-service toggles mirror "what we support":
    // Spotify syncs saved tracks/albums/artists + playlists; ListenBrainz syncs
    // playlists. Apple Music sync is a follow-up.
    @ViewBuilder private var syncSection: some View {
        label("Library Sync")
        // Each toggle is gated on the service being connected: Spotify (OAuth)
        // and ListenBrainz (token) must be set up under Plug-ins first. Apple
        // Music has no separate connect flow — its toggle triggers Apple Music
        // authorization + MUT acquisition itself, so it stays enabled.
        syncToggle("Spotify", desc: "Sync your saved tracks, albums, artists, and playlists.",
                   isOn: sync.spotifyOn, connected: model.isConnected("spotify")) { sync.setProvider("spotify", $0) }
        if sync.spotifyOn { configureRow("spotify", "Spotify") }
        syncToggle("ListenBrainz", desc: "Sync your playlists.",
                   isOn: sync.listenBrainzOn, connected: model.isConnected("listenbrainz")) { sync.setProvider("listenbrainz", $0) }
        if sync.listenBrainzOn { configureRow("listenbrainz", "ListenBrainz") }
        syncToggle(sync.appleMusicBusy ? "Apple Music (authorizing…)" : "Apple Music",
                   desc: "Sync your saved tracks, albums, artists, and playlists. (No delete/rename — Apple's API limitation.)",
                   isOn: sync.appleMusicOn) { sync.setAppleMusic($0) }
        if sync.appleMusicOn { configureRow("applemusic", "Apple Music") }
        if let cd = sync.spotifyCooldownText {
            HStack(spacing: 6) {
                Image(systemName: "clock.badge.exclamationmark").font(.system(size: 12))
                Text(cd).font(.system(size: 12, weight: .medium))
            }
            .foregroundStyle(PC.warning)
            .padding(.horizontal, 20).padding(.top, 8)
        }
        HStack {
            Button {
                Task { await sync.syncNow() }
            } label: {
                Text(sync.syncing ? (sync.syncPhase.map { "Syncing… (\($0))" } ?? "Syncing…") : "Sync now")
                    .font(.system(size: 14, weight: .semibold)).foregroundStyle(.white)
                    .padding(.horizontal, 16).padding(.vertical, 9)
                    .background(Capsule().fill(sync.anyOn ? PC.accent : PC.fg3))
            }
            .disabled(!sync.anyOn || sync.syncing)
            Spacer()
            if let s = sync.status {
                Text(s).font(.system(size: 12)).foregroundStyle(PC.fg3)
            }
        }
        .padding(.horizontal, 20).padding(.top, 8)
    }

    private func syncToggle(_ title: String, desc: String, isOn: Bool, connected: Bool = true, set: @escaping (Bool) -> Void) -> some View {
        // Disabled (greyed) until the service is connected; a stale "on" can't
        // sit enabled for a disconnected service (get returns isOn && connected).
        Toggle(isOn: Binding(get: { isOn && connected }, set: { if connected { set($0) } })) {
            VStack(alignment: .leading, spacing: 2) {
                Text(title).font(.system(size: 15)).foregroundStyle(connected ? PC.fg1 : PC.fg3)
                Text(connected ? desc : "Connect \(title) under Plug-ins first.")
                    .font(.system(size: 12)).foregroundStyle(PC.fg3)
            }
        }
        .tint(PC.accent)
        .disabled(!connected)
        .padding(.horizontal, 20).padding(.top, 12)
    }

    /// Tappable "Configure what syncs ›" row shown under an enabled provider.
    private func configureRow(_ id: String, _ name: String) -> some View {
        Button {
            configProvider = ProviderConfigTarget(id: id, name: name)
        } label: {
            HStack(spacing: 6) {
                Text("Configure what syncs").font(.system(size: 13, weight: .medium)).foregroundStyle(PC.accent)
                Image(systemName: "chevron.right").font(.system(size: 11, weight: .semibold)).foregroundStyle(PC.accent)
                Spacer()
            }
            .padding(.horizontal, 20).padding(.top, 2).padding(.bottom, 6)
        }
        .buttonStyle(.plain)
    }

    private func label(_ t: String) -> some View {
        Text(t.uppercased()).font(.system(size: 11, weight: .bold)).tracking(1.4).foregroundStyle(PC.fg3)
            .padding(.horizontal, 20).padding(.top, 18).padding(.bottom, 8)
    }
}

/// Identifies which provider's sync-config sheet is open.
struct ProviderConfigTarget: Identifiable {
    let id: String
    let name: String
}

// MARK: - Per-provider sync config sheet

/// A keep-or-remove prompt shown when the user deselects sync axes that already
/// have items in the collection.
struct AxisRemovalPrompt: Identifiable {
    let id = UUID()
    let summary: String       // e.g. "2 albums and 84 artists"
    let axes: [String]        // dropped axes that have items
}

/// Keep-or-remove prompt when the user deselects imported playlists in a
/// pull-source provider's picker.
struct PullRemovalPrompt: Identifiable {
    let id = UUID()
    let names: [String]
    var count: Int { names.count }
}

@MainActor @Observable
private final class ProviderConfigModel {
    private let container = IosContainer.companion.shared
    let providerId: String
    var axes: Set<String> = []
    var playlistMode: String = "ALL"     // ALL | NONE | SELECTED
    var selectedIds: Set<String> = []
    var pushable: [IosSyncPlaylist] = []
    var loading = true
    private var originalAxes: Set<String> = []   // axes at load, for drop detection
    var pendingRemoval: AxisRemovalPrompt?

    // Pull-source (Spotify / Apple Music) picker: choose which of the service's
    // imported playlists to keep syncing.
    var imported: [IosSyncPlaylist] = []
    var pullChecked: Set<String> = []            // local ids the user keeps syncing
    var pendingPullRemoval: PullRemovalPrompt?
    var isPull: Bool { container.isPullProvider(providerId: providerId) }

    init(providerId: String) { self.providerId = providerId }

    private func remoteId(_ localId: String) -> String {
        let prefix = "\(providerId)-"
        return localId.hasPrefix(prefix) ? String(localId.dropFirst(prefix.count)) : localId
    }

    /// Axes a provider can sync. ListenBrainz is playlists-only (loved tracks go
    /// via the scrobbler, not collection sync).
    var supportedAxes: [String] {
        providerId == "listenbrainz" ? ["playlists"] : ["tracks", "albums", "artists", "playlists"]
    }

    func load() async {
        let ax = (try? await container.getSyncCollections(providerId: providerId)) as? [String] ?? []
        let mode = (try? await container.getPlaylistSelectionMode(providerId: providerId)) ?? "ALL"
        let ids = (try? await container.getPlaylistSelectionIds(providerId: providerId)) as? [String] ?? []
        let pl = (try? await container.getPushablePlaylists(providerId: providerId)) as? [IosSyncPlaylist] ?? []
        axes = Set(ax)
        originalAxes = Set(ax)
        playlistMode = mode
        selectedIds = Set(ids)
        pushable = pl
        if isPull {
            let imp = (try? await container.getImportedProviderPlaylists(providerId: providerId)) as? [IosSyncPlaylist] ?? []
            let allow = (try? await container.getPullAllowlist(providerId: providerId)) as? [String] ?? []
            imported = imp
            let allowSet = Set(allow)
            // Empty allowlist = sync all → everything checked. Otherwise check
            // only the imported rows whose remote id is in the allowlist.
            pullChecked = allow.isEmpty
                ? Set(imp.map { $0.id })
                : Set(imp.filter { allowSet.contains(remoteId($0.id)) }.map { $0.id })
        }
        loading = false
    }

    func togglePullChecked(_ id: String) {
        if pullChecked.contains(id) { pullChecked.remove(id) } else { pullChecked.insert(id) }
    }

    /// Called on Done for a pull provider. If any imported playlist was
    /// unchecked, raise a keep/remove prompt and return true. Otherwise persist
    /// (allowlist = checked) and return false.
    func needsPullPrompt() async -> Bool {
        guard isPull else { return false }
        let deselected = imported.filter { !pullChecked.contains($0.id) }
        if deselected.isEmpty {
            await applyPull(removeIds: [], keepIds: [])
            return false
        }
        pendingPullRemoval = PullRemovalPrompt(names: deselected.map { $0.name })
        return true
    }

    private func applyPull(removeIds: [String], keepIds: [String]) async {
        // allowlist = checked remote ids; empty when everything is checked (so
        // new service playlists keep auto-syncing).
        let allChecked = pullChecked.count == imported.count
        let allowlist = allChecked ? [] : imported.filter { pullChecked.contains($0.id) }.map { remoteId($0.id) }
        try? await container.applyPullSelection(
            providerId: providerId, allowlist: allowlist,
            removeLocalIds: removeIds, keepLocalIds: keepIds,
        )
    }

    func confirmPullRemove() async {
        let d = imported.filter { !pullChecked.contains($0.id) }.map { $0.id }
        await applyPull(removeIds: d, keepIds: [])
        pendingPullRemoval = nil
    }

    func confirmPullKeep() async {
        let d = imported.filter { !pullChecked.contains($0.id) }.map { $0.id }
        await applyPull(removeIds: [], keepIds: d)
        pendingPullRemoval = nil
    }

    /// Called on Done. If the user turned OFF any axis that still has synced
    /// items in the collection, build a keep/remove prompt and return true
    /// (caller waits for the choice). Otherwise return false → safe to dismiss.
    func needsRemovalPrompt() async -> Bool {
        let dropped = originalAxes.subtracting(axes)
        guard !dropped.isEmpty else { return false }
        var withItems: [(String, Int)] = []
        for axis in dropped.sorted() {
            let n = Int((try? await container.countSyncedItems(providerId: providerId, axis: axis)) ?? 0)
            if n > 0 { withItems.append((axis, n)) }
        }
        guard !withItems.isEmpty else { return false }
        let parts = withItems.map { "\($0.1) \(axisNoun($0.0, $0.1))" }
        pendingRemoval = AxisRemovalPrompt(summary: joinParts(parts), axes: withItems.map { $0.0 })
        return true
    }

    /// Remove the dropped axes' provider-sourced items, then clear the prompt.
    func confirmRemoval() async {
        if let p = pendingRemoval {
            for axis in p.axes { _ = try? await container.removeSyncedItems(providerId: providerId, axis: axis) }
        }
        originalAxes = axes
        pendingRemoval = nil
    }

    func keepItems() { originalAxes = axes; pendingRemoval = nil }

    private func axisNoun(_ axis: String, _ n: Int) -> String {
        switch axis {
        case "tracks": return n == 1 ? "saved song" : "saved songs"
        case "albums": return n == 1 ? "album" : "albums"
        case "artists": return n == 1 ? "artist" : "artists"
        case "playlists": return n == 1 ? "playlist" : "playlists"
        default: return "items"
        }
    }

    private func joinParts(_ parts: [String]) -> String {
        switch parts.count {
        case 0: return ""
        case 1: return parts[0]
        case 2: return "\(parts[0]) and \(parts[1])"
        default: return parts.dropLast().joined(separator: ", ") + ", and " + parts.last!
        }
    }

    func toggleAxis(_ axis: String, _ on: Bool) {
        if on { axes.insert(axis) } else { axes.remove(axis) }
        let snapshot = Array(axes)
        Task { try? await container.setSyncCollections(providerId: providerId, axes: snapshot) }
    }

    func setMode(_ m: String) { playlistMode = m; persistSelection() }

    func toggleSelected(_ id: String) {
        if selectedIds.contains(id) { selectedIds.remove(id) } else { selectedIds.insert(id) }
        persistSelection()
    }

    private func persistSelection() {
        let m = playlistMode
        let ids = Array(selectedIds)
        Task { try? await container.setPlaylistSelection(providerId: providerId, mode: m, ids: ids) }
    }
}

private struct ProviderSyncConfigSheet: View {
    let target: ProviderConfigTarget
    @Environment(\.dismiss) private var dismiss
    @State private var m: ProviderConfigModel

    init(target: ProviderConfigTarget) {
        self.target = target
        _m = State(initialValue: ProviderConfigModel(providerId: target.id))
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    sectionLabel("What syncs")
                    ForEach(m.supportedAxes, id: \.self) { axis in axisToggle(axis) }

                    if m.axes.contains("playlists") {
                        if m.isPull {
                            // Pull provider: choose which of YOUR service playlists to sync.
                            sectionLabel("\(target.name) playlists to sync")
                            if m.imported.isEmpty {
                                Text("No \(target.name) playlists imported yet. Run a sync first.")
                                    .font(.system(size: 13)).foregroundStyle(PC.fg3)
                                    .padding(.horizontal, 20).padding(.top, 10)
                            } else {
                                ForEach(m.imported, id: \.id) { pl in pullRow(pl) }
                            }
                        } else {
                            // Push provider (ListenBrainz): which local playlists to mirror up.
                            sectionLabel("Playlists to mirror to \(target.name)")
                            Picker("", selection: Binding(get: { m.playlistMode }, set: { m.setMode($0) })) {
                                Text("All").tag("ALL")
                                Text("Choose").tag("SELECTED")
                                Text("None").tag("NONE")
                            }
                            .pickerStyle(.segmented)
                            .padding(.horizontal, 20).padding(.top, 4)

                            if m.playlistMode == "SELECTED" { selectedList }
                        }
                    }
                    Spacer(minLength: 40)
                }
                .padding(.top, 8)
            }
            .background(PC.bgPrimary.ignoresSafeArea())
            .navigationTitle(target.name)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") {
                        Task {
                            // Axis-off keep/remove first, then playlist-deselect keep/remove.
                            if await m.needsRemovalPrompt() { return }
                            if await m.needsPullPrompt() { return }
                            dismiss()
                        }
                    }.foregroundStyle(PC.accent)
                }
            }
        }
        .task { await m.load() }
        // Keep-or-remove when an axis with existing items was switched off.
        .confirmationDialog(
            "Stop syncing from \(target.name)?",
            isPresented: Binding(get: { m.pendingRemoval != nil }, set: { if !$0 { m.pendingRemoval = nil } }),
            titleVisibility: .visible
        ) {
            Button("Remove \(m.pendingRemoval?.summary ?? "items")", role: .destructive) {
                Task { await m.confirmRemoval(); dismiss() }
            }
            Button("Keep in collection") { m.keepItems(); dismiss() }
            Button("Cancel", role: .cancel) { m.pendingRemoval = nil }
        } message: {
            Text("You turned off syncing for \(m.pendingRemoval?.summary ?? "items") from \(target.name). Remove them from your collection, or keep them? Items also synced from another service are kept either way.")
        }
        // Keep-or-remove when imported playlists were deselected in the pull picker.
        .confirmationDialog(
            "Stop syncing \(m.pendingPullRemoval?.count ?? 0) playlist\((m.pendingPullRemoval?.count ?? 0) == 1 ? "" : "s")?",
            isPresented: Binding(get: { m.pendingPullRemoval != nil }, set: { if !$0 { m.pendingPullRemoval = nil } }),
            titleVisibility: .visible
        ) {
            Button("Remove from Parachord", role: .destructive) {
                Task { await m.confirmPullRemove(); dismiss() }
            }
            Button("Keep local copy") { Task { await m.confirmPullKeep(); dismiss() } }
            Button("Cancel", role: .cancel) { m.pendingPullRemoval = nil }
        } message: {
            Text("These playlists will stop syncing from \(target.name). Remove them from Parachord (they stay on \(target.name)), or keep them in your library? (A playlist also synced from another service keeps syncing there.)")
        }
    }

    private func pullRow(_ pl: IosSyncPlaylist) -> some View {
        Button { m.togglePullChecked(pl.id) } label: {
            HStack(spacing: 10) {
                Image(systemName: m.pullChecked.contains(pl.id) ? "checkmark.circle.fill" : "circle")
                    .font(.system(size: 18)).foregroundStyle(m.pullChecked.contains(pl.id) ? PC.accent : PC.fg3)
                VStack(alignment: .leading, spacing: 1) {
                    Text(pl.name).font(.system(size: 14)).foregroundStyle(PC.fg1).lineLimit(1)
                    Text("\(pl.trackCount) track\(pl.trackCount == 1 ? "" : "s")")
                        .font(.system(size: 11)).foregroundStyle(PC.fg3)
                }
                Spacer()
            }
            .padding(.horizontal, 20).padding(.vertical, 7)
        }
        .buttonStyle(.plain)
    }

    @ViewBuilder private var selectedList: some View {
        if m.pushable.isEmpty {
            Text("No playlists eligible to mirror to \(target.name) yet.")
                .font(.system(size: 13)).foregroundStyle(PC.fg3)
                .padding(.horizontal, 20).padding(.top, 10)
        } else {
            ForEach(m.pushable, id: \.id) { pl in playlistRow(pl) }
        }
    }

    private func axisToggle(_ axis: String) -> some View {
        Toggle(isOn: Binding(get: { m.axes.contains(axis) }, set: { m.toggleAxis(axis, $0) })) {
            Text(axisLabel(axis)).font(.system(size: 15)).foregroundStyle(PC.fg1)
        }
        .tint(PC.accent)
        .padding(.horizontal, 20).padding(.vertical, 6)
    }

    private func playlistRow(_ pl: IosSyncPlaylist) -> some View {
        Button { m.toggleSelected(pl.id) } label: {
            HStack(spacing: 10) {
                Image(systemName: m.selectedIds.contains(pl.id) ? "checkmark.circle.fill" : "circle")
                    .font(.system(size: 18)).foregroundStyle(m.selectedIds.contains(pl.id) ? PC.accent : PC.fg3)
                VStack(alignment: .leading, spacing: 1) {
                    Text(pl.name).font(.system(size: 14)).foregroundStyle(PC.fg1).lineLimit(1)
                    Text("\(pl.trackCount) track\(pl.trackCount == 1 ? "" : "s")")
                        .font(.system(size: 11)).foregroundStyle(PC.fg3)
                }
                Spacer()
            }
            .padding(.horizontal, 20).padding(.vertical, 7)
        }
        .buttonStyle(.plain)
    }

    private func sectionLabel(_ t: String) -> some View {
        Text(t.uppercased()).font(.system(size: 11, weight: .bold)).tracking(1.4).foregroundStyle(PC.fg3)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 20).padding(.top, 18).padding(.bottom, 6)
    }

    private func axisLabel(_ a: String) -> String {
        switch a {
        case "tracks": return "Saved Songs"
        case "albums": return "Saved Albums"
        case "artists": return "Followed Artists"
        case "playlists": return "Playlists"
        default: return a.capitalized
        }
    }
}

@MainActor @Observable
private final class SyncModel {
    private let container = IosContainer.companion.shared
    var spotifyOn = false
    var listenBrainzOn = false
    var appleMusicOn = false
    var appleMusicBusy = false   // acquiring the MUT
    var pendingDisable: String?  // provider awaiting a keep/remove choice
    var syncing = false          // driven by the shared engine state (any trigger)
    var syncPhase: String?       // coarse phase (tracks/albums/artists/playlists)
    var status: String?
    var spotifyCooldownMs: Int64 = 0
    private var pendingResync = false   // re-sync once an in-flight sync finishes
    private var syncWatcher: Cancellable?
    private var phaseWatcher: Cancellable?
    var anyOn: Bool { spotifyOn || listenBrainzOn || appleMusicOn }

    /// Local read (no network) — "retry in 3h 12m" while Spotify is in a 429 cooldown.
    var spotifyCooldownText: String? {
        guard spotifyCooldownMs > 0 else { return nil }
        let mins = Int(spotifyCooldownMs / 60_000)
        let h = mins / 60, m = mins % 60
        let when_ = h > 0 ? "\(h)h \(m)m" : "\(max(m, 1))m"
        return "Spotify rate-limited — retry in \(when_)"
    }

    func load() async {
        spotifyOn = (try? await container.isProviderSyncEnabled(providerId: "spotify"))?.boolValue ?? false
        listenBrainzOn = (try? await container.isProviderSyncEnabled(providerId: "listenbrainz"))?.boolValue ?? false
        appleMusicOn = (try? await container.isProviderSyncEnabled(providerId: "applemusic"))?.boolValue ?? false
        spotifyCooldownMs = container.spotifyCooldownRemainingMs()
        startWatching()
    }

    /// Reflect the shared engine's live "is a sync running" state (any trigger),
    /// and re-run a deferred sync once an in-flight one finishes.
    private func startWatching() {
        guard syncWatcher == nil else { return }
        syncWatcher = container.watchSyncing { [weak self] running in
            Task { @MainActor in
                guard let self else { return }
                self.syncing = running.boolValue
                if !running.boolValue && self.pendingResync {
                    self.pendingResync = false
                    await self.syncNow()
                }
            }
        }
        phaseWatcher = container.watchSyncPhase { [weak self] phase in
            Task { @MainActor in self?.syncPhase = phase as String? }
        }
    }

    func stopWatching() {
        syncWatcher?.cancel(); syncWatcher = nil
        phaseWatcher?.cancel(); phaseWatcher = nil
    }

    func setProvider(_ id: String, _ on: Bool) {
        if id == "spotify" { spotifyOn = on } else { listenBrainzOn = on }
        if on {
            Task {
                try? await container.setSyncProviderEnabled(providerId: id, enabled: true)
                await syncNow()
            }
        } else {
            pendingDisable = id   // prompt keep-or-remove before tearing down
        }
    }

    /// Resolve a pending disable with the user's keep/remove choice. Clears the
    /// pending state FIRST so the dialog's dismissal binding doesn't double-fire.
    func confirmDisable(removeItems: Bool) {
        guard let id = pendingDisable else { return }
        pendingDisable = nil
        Task { try? await container.disableSyncProvider(providerId: id, removeItems: removeItems) }
    }

    /// Apple Music needs a Music User Token for the library API. Acquire it via
    /// StoreKit the first time the user enables sync; revert the toggle if the
    /// user denies authorization.
    func setAppleMusic(_ on: Bool) {
        if !on {
            appleMusicOn = false
            pendingDisable = "applemusic"   // prompt keep-or-remove
            return
        }
        Task {
            appleMusicBusy = true; status = nil
            let hasMut = (try? await container.hasAppleMusicUserToken())?.boolValue ?? false
            if !hasMut {
                let devToken = (try? await container.appleMusicDeveloperToken()) ?? ""
                if let mut = await acquireAppleMusicMUT(developerToken: devToken), !mut.isEmpty {
                    try? await container.setAppleMusicUserToken(token: mut)
                } else {
                    appleMusicBusy = false
                    appleMusicOn = false
                    status = "Apple Music authorization failed"
                    return
                }
            }
            try? await container.setSyncProviderEnabled(providerId: "applemusic", enabled: true)
            appleMusicOn = true
            appleMusicBusy = false
            await syncNow()
        }
    }

    func syncNow() async {
        guard anyOn else { return }
        status = nil
        let err = (try? await container.syncNow()) ?? "Sync error"   // "" = success
        spotifyCooldownMs = container.spotifyCooldownRemainingMs()   // a 429 may have armed it
        // A sync was already running (launch/15-min timer, or another toggle) —
        // benign, NOT a failure. Re-run once it finishes so the just-enabled
        // provider gets included; the live `syncing` state shows "Syncing…".
        if err == container.syncInProgressMessage {
            pendingResync = true
            return
        }
        status = err.isEmpty ? "Synced ✓" : "Failed: \(err)"
    }
}

/// Acquire the Apple Music **Music User Token** (for the library Web API) via
/// StoreKit — `SKCloudServiceController.requestUserToken(forDeveloperToken:)`.
/// Distinct from the MusicKit playback token. Returns nil on denial/error.
/// Requires the ES256 developer token + the user's Apple Music authorization
/// (same grant MusicKit playback uses).
private func acquireAppleMusicMUT(developerToken: String) async -> String? {
    guard !developerToken.isEmpty else { return nil }
    let current = SKCloudServiceController.authorizationStatus()
    let status: SKCloudServiceAuthorizationStatus
    if current == .notDetermined {
        status = await withCheckedContinuation { cont in
            SKCloudServiceController.requestAuthorization { cont.resume(returning: $0) }
        }
    } else {
        status = current
    }
    guard status == .authorized else { return nil }
    let controller = SKCloudServiceController()
    return await withCheckedContinuation { (cont: CheckedContinuation<String?, Never>) in
        controller.requestUserToken(forDeveloperToken: developerToken) { token, error in
            cont.resume(returning: error == nil ? token : nil)
        }
    }
}

// MARK: - About tab

private struct AboutTab: View {
    var body: some View {
        VStack(spacing: 12) {
            Image("ParachordWordmark").renderingMode(.template).resizable().scaledToFit()
                .frame(height: 30).foregroundStyle(PC.fg1).padding(.top, 32)
            Text("A modern multi-source music player inspired by Tomahawk.")
                .font(.system(size: 14)).foregroundStyle(PC.fg2).multilineTextAlignment(.center).padding(.horizontal, 40)
            Divider().frame(width: 64).padding(.vertical, 6)
            Text("OPEN SOURCE SOFTWARE").font(.system(size: 11, weight: .semibold)).tracking(1.2).foregroundStyle(PC.fg3)
            Text("Built with Kotlin Multiplatform, SwiftUI, and JavaScriptCore.")
                .font(.system(size: 13)).foregroundStyle(PC.fg2).multilineTextAlignment(.center).padding(.horizontal, 40)
            Link("View on GitHub →", destination: URL(string: "https://github.com/Parachord/parachord-mobile")!)
                .font(.system(size: 13)).foregroundStyle(PC.accent)
            Text("© Jason Herskowitz · Licensed under the MIT License")
                .font(.system(size: 11)).foregroundStyle(PC.fg3).padding(.top, 4)
        }
        .frame(maxWidth: .infinity).padding(.bottom, 130)
    }
}
