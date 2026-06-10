import SwiftUI
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

/// A handful of major cities for the Concerts location picker (mirrors Android's
/// CONCERT_CITIES). The full set can search Nominatim; this is the quick list.
struct PCCity: Identifiable { let id = UUID(); let name: String; let lat: Double; let lon: Double }
let pcConcertCities: [PCCity] = [
    .init(name: "New York", lat: 40.7128, lon: -74.0060),
    .init(name: "Los Angeles", lat: 34.0522, lon: -118.2437),
    .init(name: "Chicago", lat: 41.8781, lon: -87.6298),
    .init(name: "London", lat: 51.5074, lon: -0.1278),
    .init(name: "San Francisco", lat: 37.7749, lon: -122.4194),
    .init(name: "Austin", lat: 30.2672, lon: -97.7431),
    .init(name: "Seattle", lat: 47.6062, lon: -122.3321),
    .init(name: "Toronto", lat: 43.6532, lon: -79.3832),
    .init(name: "Berlin", lat: 52.5200, lon: 13.4050),
    .init(name: "Nashville", lat: 36.1627, lon: -86.7816),
]

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
    var spotifyConnected = false
    var spotifyError: String?
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
        PCServices.resolvers.map { $0.id }.filter { !mobileBlocked.contains($0) && !isEnabledUsable($0) }
    }

    /// service id → its stored key/token/username (empty = not configured).
    var values: [String: String] = [:]
    var aiModels: [String: String] = ["chatgpt": "", "claude": "", "gemini": ""]
    var selectedAiProvider = "chatgpt"
    var concertCity = ""
    var libreFmConnected = false
    var libreFmUser = ""
    var libreFmPass = ""
    var libreFmError: String?
    var libreFmBusy = false

    var pluginCount = 0
    /// Plugin ids declaring `capabilities.mobile == false` — hidden everywhere
    /// (youtube, ollama). Seeded with the known defaults so filtering holds even
    /// if the .axe layer hasn't loaded yet; augmented from plugin metadata.
    var mobileBlocked: Set<String> = ["youtube", "ollama"]
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
        subs.append(watcher.watch(flow: container.getSpotifyConnectedFlow()) { [weak self] v in
            self?.spotifyConnected = (v as? Bool) ?? ((v as? KotlinBoolean)?.boolValue ?? false)
            self?.recomputeResolvers()   // connect/disconnect moves Spotify in/out of the list
        })
        subs.append(watcher.watch(flow: container.getLibreFmConnectedFlow()) { [weak self] v in
            self?.libreFmConnected = (v as? Bool) ?? ((v as? KotlinBoolean)?.boolValue ?? false) })
        loadAll()
    }
    func stop() { subs.forEach { $0.cancel() }; subs.removeAll() }

    private func loadAll() {
        Task { @MainActor in
            selectedAiProvider = (try? await store.getSelectedChatProvider()) ?? "chatgpt"
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
            concertCity = ((try? await store.getConcertLocation())?.city) ?? ""

            // Mobile capability filter — mirror PluginManager.plugins (which keeps
            // capabilities["mobile"] != false). loadPlugins() is already
            // mobile-filtered; allLoadedPlugins surfaces the hidden ids.
            let all = (try? await container.loadAllPlugins()) ?? []
            var blocked = Set(all.filter { $0.capabilities["mobile"]?.boolValue == false }.map { $0.id })
            blocked.formUnion(["youtube", "ollama"])
            mobileBlocked = blocked
            pluginCount = ((try? await container.loadPlugins()) ?? []).count

            // Read Spotify connection up-front so the load-time recompute gates
            // it correctly (the connected-flow watcher only fires later).
            spotifyConnected = (try? await store.getSpotifyAccessToken()) != nil

            // Resolver intent (active_resolvers): empty stored = all catalogued.
            // Whether each actually reaches the priority list is gated by
            // isUsable (connected / no-auth) inside recomputeResolvers.
            let catalogIds = PCServices.resolvers.map { $0.id }
            let storedActive = ((try? await store.getActiveResolvers()) as? [String]) ?? []
            activeResolvers = storedActive.isEmpty ? Set(catalogIds) : Set(storedActive).intersection(catalogIds)
            resolverOrder = ((try? await store.getResolverOrder()) as? [String])?.filter { PCServices.find($0) != nil } ?? []
            resolversReady = true
            recomputeResolvers()
        }
    }

    // ── Connect status ────────────────────────────────────────────────
    func isConnected(_ id: String) -> Bool {
        switch id {
        case "spotify": return spotifyConnected
        case "librefm": return libreFmConnected
        case "localfiles", "bandcamp", "applemusic": return true   // no key needed / MusicKit at play time
        default: return !(values[id]?.isEmpty ?? true)
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
    func setResolverEnabled(_ id: String, _ enabled: Bool) {
        if enabled { activeResolvers.insert(id) } else { activeResolvers.remove(id) }
        recomputeResolvers()
    }

    /// Rebuild resolverOrder = enabled-usable resolvers (preserving the current
    /// order; canonical-insert any newly-usable one). Persists once load is done.
    func recomputeResolvers() {
        var order = resolverOrder.filter { isEnabledUsable($0) && PCServices.find($0) != nil }
        for id in PCServices.resolvers.map({ $0.id }) where isEnabledUsable(id) && !order.contains(id) {
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
            case "listenbrainz": try? await store.setListenBrainzToken(token: value)
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
    func setConcertLocation(_ city: String, _ lat: Double, _ lon: Double) {
        concertCity = city
        Task { try? await store.setConcertLocation(lat: lat, lon: lon, city: city, radiusMiles: 50) }
    }
    func setTheme(_ m: String) { themeMode = m; Task { try? await store.setThemeMode(mode: m) } }
    func setScrobbling(_ b: Bool) { scrobblingEnabled = b; Task { try? await store.setScrobblingEnabled(enabled: b) } }

    // ── Spotify OAuth ─────────────────────────────────────────────────
    func connectSpotify() {
        Task { @MainActor in
            do {
                let m = IosOAuthManager(); oauthManager = m
                let cfg = OAuthConfig.spotify(clientId: container.appConfig.spotifyClientId)
                let r = try await m.authorize(cfg)
                try await container.connectSpotify(code: r.code, codeVerifier: r.codeVerifier)
                spotifyError = nil
                activeResolvers.insert("spotify")   // connecting enables it (desktop parity)
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
                pluginCount = ((try? await container.loadAllPlugins()) ?? []).count
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

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            sectionLabel("Content Resolvers")
            Text("Drag priority sets which source plays first.")
                .font(.system(size: 12)).foregroundStyle(PC.fg3).padding(.horizontal, 20).padding(.bottom, 6)
            VStack(spacing: 0) {
                ForEach(Array(model.resolverOrder.enumerated()), id: \.element) { i, id in
                    if let svc = PCServices.find(id) {
                        resolverRow(svc, index: i)
                        if i < model.resolverOrder.count - 1 { Divider().padding(.leading, 64) }
                    }
                }
            }

            if !model.disabledResolvers.isEmpty {
                Text("DISABLED").font(.system(size: 10, weight: .bold)).tracking(1.2).foregroundStyle(PC.fg3)
                    .padding(.horizontal, 20).padding(.top, 14).padding(.bottom, 4)
                VStack(spacing: 0) {
                    ForEach(model.disabledResolvers, id: \.self) { id in
                        if let svc = PCServices.find(id) { disabledResolverRow(svc) }
                    }
                }
            }

            sectionLabel("Meta Services")
            serviceGrid(PCServices.meta, columns: 3)

            sectionLabel("Concerts & Events")
            serviceGrid(PCServices.concerts, columns: 2)

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
            VStack(spacing: 2) {
                Button { model.moveResolver(index, index - 1) } label: { Image(systemName: "chevron.up").font(.system(size: 12, weight: .bold)) }
                    .disabled(index == 0).buttonStyle(.plain).foregroundStyle(index == 0 ? PC.fg3.opacity(0.4) : PC.fg2)
                Button { model.moveResolver(index, index + 1) } label: { Image(systemName: "chevron.down").font(.system(size: 12, weight: .bold)) }
                    .disabled(index == model.resolverOrder.count - 1).buttonStyle(.plain)
                    .foregroundStyle(index == model.resolverOrder.count - 1 ? PC.fg3.opacity(0.4) : PC.fg2)
            }
        }
        .padding(.horizontal, 20).padding(.vertical, 9)
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
            Button(needsConnect ? "Connect" : "Enable") {
                if needsConnect { onConfig(svc) } else { model.setResolverEnabled(svc.id, true) }
            }
            .font(.system(size: 14, weight: .medium)).foregroundStyle(PC.accent).buttonStyle(.plain)
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

                switch service.id {
                case "spotify": spotifySection
                case "librefm": libreFmSection
                case "applemusic": infoSection("Apple Music is authorized at playback time via MusicKit. No key needed.")
                case "localfiles": infoSection("Local files are scanned from your device's music library automatically.")
                case "bandcamp": infoSection("Bandcamp needs no credentials — it resolves and opens tracks in the browser.")
                default: keySection
                }
            }
            .navigationTitle("Configure").navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } } }
            .onAppear { draft = model.values[service.id] ?? "" }
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

    private var spotifySection: some View {
        Section("Connection") {
            if model.spotifyConnected {
                Label("Spotify Premium connected", systemImage: "checkmark.circle.fill").foregroundStyle(.green)
                Button("Disconnect", role: .destructive) { model.disconnectSpotify() }
            } else {
                Button { model.connectSpotify() } label: {
                    Text("Connect Spotify").bold().frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent).tint(Color(uiColor: UIColor(hex: 0x1DB954)))
                .listRowInsets(EdgeInsets(top: 6, leading: 16, bottom: 6, trailing: 16))
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

        if service.kind == .concert { concertLocationSection }

        if isAi {
            Section("Model") {
                Picker("Model", selection: Binding(get: { model.aiModels[service.id] ?? "" }, set: { model.setAiModel(service.id, $0) })) {
                    Text("Default").tag("")
                    ForEach(SettingsViewModel.aiModelOptions[service.id] ?? [], id: \.self) { Text($0).tag($0) }
                }
                Toggle("Use as DJ provider", isOn: Binding(
                    get: { model.selectedAiProvider == service.id },
                    set: { if $0 { model.setSelectedAiProvider(service.id) } }))
            }
        }
    }

    private var concertLocationSection: some View {
        Section {
            if !model.concertCity.isEmpty {
                HStack { Text("Near"); Spacer(); Text(model.concertCity).foregroundStyle(.secondary) }
            }
            Menu {
                ForEach(pcConcertCities) { c in
                    Button(c.name) { model.setConcertLocation(c.name, c.lat, c.lon) }
                }
            } label: {
                Label(model.concertCity.isEmpty ? "Set your city" : "Change city", systemImage: "mappin.and.ellipse")
            }
        } header: { Text("Concert Location") } footer: {
            Text("Filters concerts and the On Tour indicator to shows near you. Shared across all concert services.")
        }
    }

    private func infoSection(_ s: String) -> some View {
        Section { Text(s).font(.system(size: 14)).foregroundStyle(.secondary) }
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
    private let themes = ["system", "light", "dark"]

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            label("Appearance")
            Picker("Theme", selection: Binding(get: { model.themeMode }, set: { model.setTheme($0) })) {
                ForEach(themes, id: \.self) { Text($0.capitalized).tag($0) }
            }.pickerStyle(.segmented).padding(.horizontal, 20)

            label("Scrobbling")
            Toggle("Send listening history", isOn: Binding(get: { model.scrobblingEnabled }, set: { model.setScrobbling($0) }))
                .padding(.horizontal, 20)
            Text("Connect Last.fm / ListenBrainz under Plug-ins to scrobble your plays.")
                .font(.system(size: 12)).foregroundStyle(PC.fg3).padding(.horizontal, 20).padding(.top, 6)
        }
        .padding(.bottom, 130)
    }
    private func label(_ t: String) -> some View {
        Text(t.uppercased()).font(.system(size: 11, weight: .bold)).tracking(1.4).foregroundStyle(PC.fg3)
            .padding(.horizontal, 20).padding(.top, 18).padding(.bottom, 8)
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
