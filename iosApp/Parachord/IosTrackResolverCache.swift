import Foundation
import UIKit
import Shared

// MARK: - Track resolver cache (playback-loop phase, two-layer resolution)
//
// CLAUDE.md "On-the-fly Track Resolution" is a two-layer strategy:
//   1. Background pre-resolution — when a tracklist loads, resolve the whole
//      visible list in the background and cache the ranked sources. This makes
//      resolver badges appear AND playback start instantly from cache on tap.
//   2. On-the-fly fallback — resolve at play time only on a cache miss.
//
// This is layer 1. The coordinator (playTrack) reads `cached(...)` first and
// falls back to `container.resolveSources` on a miss.
//
// Throttled because every resolve runs through ONE JavaScriptCore context
// (all `.axe` plugins share it). A 25-track playlist must not fire 25×N
// concurrent JSC resolves — `cap` bounds in-flight work.

struct ResolveRequest: Hashable {
    let artist: String
    let title: String
    let album: String?
    /// Already-known Spotify ID from the metadata layer (#211). When set, the
    /// resolver emits it directly instead of firing a redundant Spotify *search*
    /// — the redundancy that bursts the shared client_id on Album / Artist-Top-
    /// Tracks pages (those rows arrive with a `spotifyId` from the provider
    /// cascade). nil for ID-less content. NOT part of `key` (the cache is keyed
    /// by artist/title/album, so a hinted and un-hinted request share one entry).
    let spotifyId: String?

    init(artist: String, title: String, album: String?, spotifyId: String? = nil) {
        self.artist = artist
        self.title = title
        self.album = album
        self.spotifyId = spotifyId
    }

    var key: String {
        "\(artist.lowercased())\u{1}\(title.lowercased())\u{1}\((album ?? "").lowercased())"
    }
}

@MainActor
@Observable
final class IosTrackResolverCache {
    static let shared = IosTrackResolverCache()

    private let container = IosContainer.companion.shared

    /// key → ranked sources (best first). Observed by badge rows so they
    /// render as each track resolves.
    private(set) var cache: [String: [ResolvedSource]] = [:]
    private var inFlight: Set<String> = []

    /// Last-seen request per key, so a newly-enabled resolver (#1) can
    /// additively re-resolve a cached track (we need its artist/title/album).
    private var requests: [String: ResolveRequest] = [:]

    /// Pending work, drained LOWEST-`order`-first so a tracklist resolves
    /// top-down (the row at the top of the page resolves before the row below
    /// it), never bottom-up. Mirrors desktop ResolutionScheduler's
    /// visibility-index priority. `merge` = a resolver id for an additive
    /// single-resolver merge (#1); nil for a normal full resolve.
    private var queue: [(order: Int, req: ResolveRequest, merge: String?)] = []

    /// In-flight worker count, bounded by `cap`. This is a SHARED pool across
    /// every submission — submitting tracks one-per-row must not exceed the cap
    /// (the previous per-call task group let N rows run N concurrent resolves).
    private var activeWorkers = 0
    /// When a resolver may be asked again for a track, per
    /// "key\u{1}resolver". A track that genuinely isn't on a service used to
    /// re-search it every time its row scrolled into view, every session; now
    /// a no-match waits `ResolverRetry.MISS_RETRY_MS` (30 min) before the scroll
    /// path asks again. A resolver that couldn't answer (offline, a rate-limit
    /// cooldown, a service error) is asked again sooner, after 2, 4, 8 … 60
    /// minutes (`ResolverRetry.failedRetryDelayMs`, shared with Android, #566).
    private var retryAfter: [String: Date] = [:]
    /// Consecutive failures per "key\u{1}resolver", for the back-off.
    private var failureCount: [String: Int] = [:]

    /// Max concurrent resolves through the single JSC context (`.axe` plugins
    /// all share it) + the shared Spotify gate. Matches desktop's 4-ish pool.
    private let cap = 3

    // ── Disk persistence ───────────────────────────────────────────────
    // iOS has no DB yet, so without this every app session re-searches every
    // tracklist via Spotify/iTunes catalog search — the volume that keeps
    // re-arming Spotify's shared-key abuse window. Persist the resolved-source
    // map to disk and reload on launch so a track resolved once never
    // re-searches. Mirrors Android's resolver-ID backfill.
    //
    // SCHEMA VERSION — the filename carries the version, so a bump makes the
    // previous file unreadable (we never open it) → every track re-resolves once
    // and repopulates the new file. Bump whenever a `ResolvedSource` field change
    // makes OLD cached entries semantically wrong to reuse.
    //   v2 (Jun 2026, commit ccea230): `ResolvedSource` gained `isrc`. v1 caches
    //   stored Apple Music / Spotify sources with no ISRC and were reused verbatim
    //   without re-resolving, so a track cached pre-v2 never gained an ISRC and its
    //   Achordion submit (recording- or ISRC-keyed) silently skipped. Bumping to v2
    //   discards those so the catalog re-resolve captures `attributes.isrc`.
    //   v3 (Jul 2026, #287): shipped a Spotify/AM ID-hint connection gate that has
    //   since been REVERTED (the gate keyed off the same access-token signal as the
    //   router's canPlay, so it didn't change connected behavior, and the forced
    //   re-resolve fed a Spotify rate-limit window). The version stays at 3 on
    //   purpose — the v3 purge already ran on shipped builds, and reverting the
    //   number to 2 would trigger ANOTHER purge → another full re-resolve → more
    //   Spotify search traffic (exactly the rate-limit risk we're avoiding). Bump to
    //   4+ only for a genuinely NEW ResolvedSource-shape reason, ideally gated on
    //   rateLimitRemainingMs()==0 so a schema bump can't force a re-resolve into a
    //   Spotify cooldown (follow-up).
    // Android has no equivalent problem: it re-derives ISRC from fresh resolution
    // every play (reselectBestSource / resolveOnTheFly), never from a stale blob.
    private static let cacheSchemaVersion = 3
    private let persistURL: URL = {
        let dir = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
        return dir.appendingPathComponent("resolver-cache-v\(IosTrackResolverCache.cacheSchemaVersion).json")
    }()
    private var saveScheduled = false

    init() {
        purgeStaleCacheFiles()
        loadFromDisk()
    }

    /// Delete any resolver-cache file that isn't the current schema version — the
    /// legacy unversioned `resolver-cache.json` plus any prior `-vN`. Without this
    /// each schema bump would leak a stale blob in the caches dir forever.
    private func purgeStaleCacheFiles() {
        let fm = FileManager.default
        let dir = fm.urls(for: .cachesDirectory, in: .userDomainMask)[0]
        let current = persistURL.lastPathComponent
        let contents = (try? fm.contentsOfDirectory(at: dir, includingPropertiesForKeys: nil)) ?? []
        for url in contents {
            let name = url.lastPathComponent
            guard name == "resolver-cache.json" || name.hasPrefix("resolver-cache-v") else { continue }
            if name != current { try? fm.removeItem(at: url) }
        }
    }

    private func loadFromDisk() {
        guard let data = try? Data(contentsOf: persistURL),
              let blob = String(data: data, encoding: .utf8) else { return }
        cache = container.decodeResolverCache(blob: blob)
    }

    /// Debounced full-map write (coalesces a burst of resolves into one save).
    private func scheduleSave() {
        guard !saveScheduled else { return }
        saveScheduled = true
        Task { @MainActor in
            try? await Task.sleep(nanoseconds: 2_000_000_000)
            guard saveScheduled else { return }   // flushed already
            saveNow()
        }
    }

    /// Write any pending save immediately. Called when the app goes to the
    /// background: a process suspended and killed inside the 2s debounce
    /// otherwise lost every resolve since the last save.
    func flushPendingSave() {
        guard saveScheduled else { return }
        saveNow()
    }

    private func saveNow() {
        saveScheduled = false
        let blob = container.encodeResolverCache(map: cache)
        try? blob.data(using: .utf8)?.write(to: persistURL, options: .atomic)
    }

    /// Store sources found outside the resolvers for a track: a shared link's
    /// own source plus the others (#502). The play path then reads them from
    /// here instead of its direct-URL fast path (the link isn't audio).
    func seed(artist: String, title: String, album: String?, sources: [ResolvedSource]) {
        guard !sources.isEmpty else { return }
        cache[ResolveRequest(artist: artist, title: title, album: album).key] = sources
        scheduleSave()
    }

    func cached(artist: String, title: String, album: String?) -> [ResolvedSource]? {
        cache[ResolveRequest(artist: artist, title: title, album: album).key]
    }

    /// True while a full resolve for this key is in flight and has NOT yet produced
    /// a cached result — drives the art skeleton (e.g. after a metadata edit, the
    /// new title/artist key re-resolves and art appears once it lands). `@Observable`
    /// tracks `cache`/`inFlight`, so a view reading this re-renders when either flips.
    func isResolving(artist: String, title: String, album: String?) -> Bool {
        let key = ResolveRequest(artist: artist, title: title, album: album).key
        return cache[key] == nil && inFlight.contains(key)
    }

    /// Submit ONE track for resolution with a priority `order` (its row index).
    /// Lower `order` resolves first → top-down. Skips already-cached, in-flight,
    /// or already-queued keys. Call from a row's `.onAppear` so only visible
    /// rows resolve, in the order they sit on the page.
    func resolve(_ req: ResolveRequest, order: Int) {
        let key = req.key
        requests[key] = req   // record for additive re-resolution (#1), even if cached
        if let existing = cache[key] {
            // Already resolved — but a resolver ENABLED after this track cached may
            // be missing. The one-shot `resolverEnabled` burst fires an additive
            // search for EVERY cached track at once; the batch that lands after
            // Spotify's rate-limit window trips comes back nil, leaving a "chunk"
            // with no Spotify badge that never retries. Re-attempt any active,
            // not-disabled resolver that's absent here — scoped to this now-visible
            // row, deduped against in-flight/queued work. Spotify's gate makes this
            // cheap during cooldown and effective once it clears, so scrolling the
            // chunk back into view fills it in. A resolver that answered "no match"
            // for this track isn't asked again for 30 minutes — otherwise a track
            // that isn't on that service re-searched it on every scroll — and one
            // that couldn't answer waits the shorter failure back-off (#566).
            let active = ResolverPrefs.shared.active
            guard !active.isEmpty else { return }   // empty = all-on; full resolve already covered it
            let disabled = ResolverPrefs.shared.disabled
            var enqueued = false
            for r in active where !disabled.contains(r) {
                let flightKey = "\(key)\u{1}\(r)"
                guard !existing.contains(where: { $0.resolver == r && !$0.noMatch }),
                      !waitingToRetry(flightKey),
                      !inFlight.contains(flightKey),
                      !queue.contains(where: { $0.req.key == key && $0.merge == r })
                else { continue }
                queue.append((order: order, req: req, merge: r))
                enqueued = true
            }
            if enqueued { pump() }
            return
        }
        guard !inFlight.contains(key),
              !queue.contains(where: { $0.req.key == key && $0.merge == nil })
        else { return }
        queue.append((order, req, nil))
        pump()
    }

    private func waitingToRetry(_ flightKey: String) -> Bool {
        guard let until = retryAfter[flightKey] else { return false }
        return Date() < until
    }

    /// The resolver answered with no match: ask again after 30 minutes.
    private func noteMissed(_ flightKey: String) {
        failureCount[flightKey] = nil
        retryAfter[flightKey] = Date().addingTimeInterval(TimeInterval(ResolverRetry.shared.MISS_RETRY_MS) / 1000)
    }

    /// The resolver couldn't answer (#566): ask again after the shared back-off.
    private func noteFailed(_ flightKey: String) {
        let failures = (failureCount[flightKey] ?? 0) + 1
        failureCount[flightKey] = failures
        let delayMs = ResolverRetry.shared.failedRetryDelayMs(failures: Int32(failures))
        retryAfter[flightKey] = Date().addingTimeInterval(TimeInterval(delayMs) / 1000)
    }

    /// A resolver was just ENABLED in Settings (#1). Additively resolve ONLY that
    /// resolver for every track we've already resolved, and MERGE its source into
    /// the cached results — existing (still-good) sources are KEPT, never
    /// invalidated. Mirrors the user's intent: enabling Spotify adds Spotify to
    /// the cached Apple Music results, it doesn't re-resolve from scratch.
    func resolverEnabled(_ resolverId: String) {
        // An explicit enable asks every track again, misses included.
        retryAfter = retryAfter.filter { !$0.key.hasSuffix("\u{1}\(resolverId)") }
        failureCount = failureCount.filter { !$0.key.hasSuffix("\u{1}\(resolverId)") }
        for (key, req) in requests {
            guard let existing = cache[key],
                  !existing.contains(where: { $0.resolver == resolverId && !$0.noMatch }),
                  !queue.contains(where: { $0.req.key == key && $0.merge == resolverId })
            else { continue }
            queue.append((order: 0, req: req, merge: resolverId))
        }
        pump()
    }

    /// A resolver was DISCONNECTED (desktop `removeResolverSources`): drop its
    /// sources from every cached track so its badge goes and nothing routes to
    /// it. The other resolvers' sources stay. Reconnecting merges it back in
    /// through `resolverEnabled`.
    func resolverRemoved(_ resolverId: String) {
        var changed = false
        for (key, sources) in cache where sources.contains(where: { $0.resolver == resolverId }) {
            cache[key] = sources.filter { $0.resolver != resolverId }
            changed = true
        }
        queue.removeAll { $0.merge == resolverId }
        retryAfter = retryAfter.filter { !$0.key.hasSuffix("\u{1}\(resolverId)") }
        failureCount = failureCount.filter { !$0.key.hasSuffix("\u{1}\(resolverId)") }
        if changed { scheduleSave() }
    }

    /// Nothing queued or running. The background sweep asks for its next
    /// batch only then (desktop's scheduler calls back when its queue drains).
    var isIdle: Bool { queue.isEmpty && activeWorkers == 0 }

    /// "title|artist" (lowercased, trimmed) of every cached track with a
    /// playable source: `SweepItem.key`, so the sweep skips them.
    var playableSweepKeys: Set<String> {
        var keys = Set<String>()
        for (key, sources) in cache where ResolverBadges.shared.hasPlayableSource(sources: sources) {
            let parts = key.components(separatedBy: "\u{1}")
            guard parts.count >= 2 else { continue }
            keys.insert("\(parts[1].trimmingCharacters(in: .whitespaces))|\(parts[0].trimmingCharacters(in: .whitespaces))")
        }
        return keys
    }

    /// The background sweep's batch: queued behind every row on screen.
    func resolveBackground(_ requests: [ResolveRequest]) {
        for (i, req) in requests.enumerated() { resolve(req, order: Self.backgroundOrder + i) }
    }

    /// Sweep work sorts after any visible row's `order`.
    private static let backgroundOrder = 1_000_000

    /// A row left the screen (#181): drop its track's queued, not-yet-started
    /// work, as desktop's `ResolutionScheduler.updateVisibility` aborts rows that
    /// scroll away. A resolve already running finishes. Call from the row's
    /// `.onDisappear`, beside the `.onAppear` that submitted it; the row asks
    /// again if it comes back.
    func leave(artist: String, title: String) {
        let a = artist.lowercased(), t = title.lowercased()
        queue.removeAll { $0.req.artist.lowercased() == a && $0.req.title.lowercased() == t }
    }

    /// Batch convenience — submits each request with `order` = its array index,
    /// so a list passed top-to-bottom resolves top-down.
    func resolveInBackground(_ requests: [ResolveRequest]) {
        for (i, req) in requests.enumerated() { resolve(req, order: i) }
    }

    /// Fill free worker slots from the front of the priority queue (lowest
    /// `order` first). Re-invoked by each worker on completion.
    private func pump() {
        while activeWorkers < cap, !queue.isEmpty {
            queue.sort { $0.order < $1.order }           // top-of-page first
            let item = queue.removeFirst()
            let key = item.req.key

            if let mergeResolver = item.merge {
                // ── Additive single-resolver merge (#1) ──────────────────────
                guard let existing = cache[key],
                      !existing.contains(where: { $0.resolver == mergeResolver && !$0.noMatch })
                else { continue }
                let flightKey = "\(key)\u{1}\(mergeResolver)"
                if inFlight.contains(flightKey) { continue }
                inFlight.insert(flightKey)
                activeWorkers += 1
                Task { @MainActor in
                    do {
                        if let resolved = try await self.container.resolveSingleResolver(
                            resolverId: mergeResolver,
                            artist: item.req.artist, title: item.req.title, album: item.req.album) {
                            var merged = (self.cache[key] ?? []).filter { $0.resolver != mergeResolver }
                            merged.append(resolved)
                            let ranked = (try? await self.container.rankSources(sources: merged)) ?? merged
                            self.cache[key] = ranked
                            self.scheduleSave()
                            self.retryAfter[flightKey] = nil
                            self.failureCount[flightKey] = nil
                        } else {
                            self.noteMissed(flightKey)
                        }
                    } catch {
                        // Couldn't answer, not a no-match: sooner retry (#566).
                        self.noteFailed(flightKey)
                    }
                    self.inFlight.remove(flightKey)
                    self.activeWorkers -= 1
                    self.pump()
                }
                continue
            }

            // ── Normal full resolve ──────────────────────────────────────────
            if cache[key] != nil || inFlight.contains(key) { continue }
            inFlight.insert(key)
            activeWorkers += 1
            Task { @MainActor in
                let report = try? await self.container.resolveSourcesReport(
                    artist: item.req.artist, title: item.req.title, album: item.req.album,
                    // #211: pass the metadata-known Spotify ID so the resolver emits
                    // it directly instead of re-searching Spotify per row. (Kotlin
                    // default args don't bridge to Swift — pass appleMusicId explicitly.)
                    spotifyId: item.req.spotifyId, appleMusicId: nil,
                    // The row request carries no recording identity; name-only
                    // scoring here. Play time passes the track's ISRC/MBID (#402).
                    targetIsrc: nil, targetMbid: nil)
                let ranked = report?.sources ?? []
                self.cache[key] = ranked
                // #566: a resolver that answered "no match" waits 30 min before
                // the row's fill asks it again; one that couldn't answer is
                // asked again after the shorter failure back-off.
                for r in report?.missed ?? [] { self.noteMissed("\(key)\u{1}\(r)") }
                for r in report?.failed ?? [] { self.noteFailed("\(key)\u{1}\(r)") }
                // Persist only real results — never cache an empty/failed
                // resolve to disk (it may be a transient rate-limit miss). The
                // empty entry stays in memory for this session only: the
                // encoder drops empties from every save (ResolverCacheCodec).
                if !ranked.isEmpty { self.scheduleSave() }
                self.inFlight.remove(key)
                self.activeWorkers -= 1
                self.pump()
            }
        }
    }
}


// MARK: - Background resolve sweep

/// Desktop's idle background pre-resolution, driven by the shared
/// `BackgroundResolveSweep` rules: while the app is active and nothing else is
/// resolving, the collection's tracks, then the playlists', that have no source
/// yet are resolved a small batch at a time, queued behind every row on screen.
/// Results are saved like any other resolve, so the next look is cached.
/// Touches pause it for a few seconds (desktop's activity gate).
@MainActor
final class BackgroundResolveSweeper: NSObject, UIGestureRecognizerDelegate {
    static let shared = BackgroundResolveSweeper()

    private var sweep: BackgroundResolveSweep?
    private var task: Task<Void, Never>?
    private var lastActivityMs: Int64 = 0
    private var touchWatcher: TouchWatcher?

    /// Run while the app is active; stop when it isn't.
    func setActive(_ active: Bool) {
        guard active else {
            task?.cancel()
            task = nil
            return
        }
        guard task == nil else { return }
        installTouchWatcher()
        let sweep = self.sweep ?? IosContainer.companion.shared.makeBackgroundResolveSweep()
        self.sweep = sweep
        task = Task { @MainActor [weak self] in await self?.run(sweep) }
    }

    private func run(_ sweep: BackgroundResolveSweep) async {
        let cache = IosTrackResolverCache.shared
        while !Task.isCancelled {
            let prefs = ResolverPrefs.shared
            let anyOn = prefs.active.isEmpty || prefs.active.contains { !prefs.disabled.contains($0) }
            let now = Int64(Date().timeIntervalSince1970 * 1000)
            let step: SweepStep
            do {
                step = try await sweep.next(nowMs: now, lastActivityMs: lastActivityMs,
                                            anyResolverOn: anyOn, playableKeys: cache.playableSweepKeys)
            } catch {
                step = SweepStepWait(ms: BackgroundResolveSweep.companion.NOTHING_TO_DO_WAIT_MS)
            }
            if let wait = step as? SweepStepWait {
                try? await Task.sleep(nanoseconds: UInt64(max(wait.ms, 0)) * 1_000_000)
            } else if let batch = step as? SweepStepResolve {
                cache.resolveBackground(batch.items.map {
                    ResolveRequest(artist: $0.artist, title: $0.title, album: $0.album)
                })
                // Ask again only once the cache is idle.
                while !Task.isCancelled && !cache.isIdle {
                    try? await Task.sleep(nanoseconds: 1_000_000_000)
                }
            }
        }
    }

    /// Notes every touch on the app's window without taking it.
    private func installTouchWatcher() {
        guard touchWatcher == nil,
              let window = UIApplication.shared.connectedScenes
                .compactMap({ ($0 as? UIWindowScene)?.keyWindow }).first
        else { return }
        let watcher = TouchWatcher { [weak self] in
            self?.lastActivityMs = Int64(Date().timeIntervalSince1970 * 1000)
        }
        watcher.delegate = self
        window.addGestureRecognizer(watcher)
        touchWatcher = watcher
    }

    nonisolated func gestureRecognizer(_ gestureRecognizer: UIGestureRecognizer,
                                       shouldRecognizeSimultaneouslyWith other: UIGestureRecognizer) -> Bool { true }
}

/// A gesture recognizer that only reports that a touch began, then fails, so
/// the app's own gestures and controls get every touch.
private final class TouchWatcher: UIGestureRecognizer {
    private let onTouch: () -> Void

    init(onTouch: @escaping () -> Void) {
        self.onTouch = onTouch
        super.init(target: nil, action: nil)
        cancelsTouchesInView = false
        delaysTouchesBegan = false
        delaysTouchesEnded = false
    }

    override func touchesBegan(_ touches: Set<UITouch>, with event: UIEvent) {
        onTouch()
        state = .failed
    }
}
