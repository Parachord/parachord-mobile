import Foundation
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
    // Android has no equivalent problem: it re-derives ISRC from fresh resolution
    // every play (reselectBestSource / resolveOnTheFly), never from a stale blob.
    private static let cacheSchemaVersion = 2
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
            saveScheduled = false
            let blob = container.encodeResolverCache(map: cache)
            try? blob.data(using: .utf8)?.write(to: persistURL, options: .atomic)
        }
    }

    func cached(artist: String, title: String, album: String?) -> [ResolvedSource]? {
        cache[ResolveRequest(artist: artist, title: title, album: album).key]
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
            // chunk back into view fills it in.
            let active = ResolverPrefs.shared.active
            guard !active.isEmpty else { return }   // empty = all-on; full resolve already covered it
            let disabled = ResolverPrefs.shared.disabled
            var enqueued = false
            for r in active where !disabled.contains(r) {
                guard !existing.contains(where: { $0.resolver == r && !$0.noMatch }),
                      !inFlight.contains("\(key)\u{1}\(r)"),
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

    /// A resolver was just ENABLED in Settings (#1). Additively resolve ONLY that
    /// resolver for every track we've already resolved, and MERGE its source into
    /// the cached results — existing (still-good) sources are KEPT, never
    /// invalidated. Mirrors the user's intent: enabling Spotify adds Spotify to
    /// the cached Apple Music results, it doesn't re-resolve from scratch.
    func resolverEnabled(_ resolverId: String) {
        for (key, req) in requests {
            guard let existing = cache[key],
                  !existing.contains(where: { $0.resolver == resolverId && !$0.noMatch }),
                  !queue.contains(where: { $0.req.key == key && $0.merge == resolverId })
            else { continue }
            queue.append((order: 0, req: req, merge: resolverId))
        }
        pump()
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
                    if let resolved = try? await self.container.resolveSingleResolver(
                        resolverId: mergeResolver,
                        artist: item.req.artist, title: item.req.title, album: item.req.album) {
                        var merged = (self.cache[key] ?? []).filter { $0.resolver != mergeResolver }
                        merged.append(resolved)
                        let ranked = (try? await self.container.rankSources(sources: merged)) ?? merged
                        self.cache[key] = ranked
                        self.scheduleSave()
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
                let ranked = (try? await self.container.resolveSources(
                    artist: item.req.artist, title: item.req.title, album: item.req.album,
                    // #211: pass the metadata-known Spotify ID so the resolver emits
                    // it directly instead of re-searching Spotify per row. (Kotlin
                    // default args don't bridge to Swift — pass appleMusicId explicitly.)
                    spotifyId: item.req.spotifyId, appleMusicId: nil)) ?? []
                self.cache[key] = ranked
                // Persist only real results — never cache an empty/failed
                // resolve to disk (it may be a transient rate-limit miss).
                if !ranked.isEmpty { self.scheduleSave() }
                self.inFlight.remove(key)
                self.activeWorkers -= 1
                self.pump()
            }
        }
    }
}
