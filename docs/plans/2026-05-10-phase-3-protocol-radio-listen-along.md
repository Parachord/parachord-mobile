# Phase 3 — Protocol play/radio + listen-along (#121)

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Wire `parachord://play/radio` (Mode B + Mode C) and `parachord://listen-along` end-to-end, matching desktop parity for refill semantics, LB token auto-attach, transient-friend listen-along, and pool-based spinoff banner rendering.

**Architecture:**
- Mode B reuses the existing `PlaybackController.startSpinoff()` machinery, lifted to accept an explicit `(artist, title?)` seed instead of the currently-playing track.
- Mode C introduces a sibling entry point `startPoolBasedSpinoff(initialPool, displayName, refillUrl?)` that piggybacks on the existing `spinoffPool` data structure but feeds tracks from a parsed JSPF/XSPF/JSON payload (or inline `tracks=` base64) instead of Last.fm `getsimilar`.
- A long-lived refill loop (in `PlaybackController`) polls `refillUrl` when the pool drops below 3, with rate-limit, 3-empty-stop, and (mbid → isrc → artist|title) dedup.
- ListenBrainz token auto-attach is implemented as a tiny Ktor `HttpRequestPipeline` plugin keyed by host-suffix (`api.listenbrainz.org`), reading the token from `SettingsStore.getListenBrainzToken()`. Applied at HttpClient-construction time so every shared call (initial Mode C fetch, refill loop, transient-friend `/playing-now` fetch) benefits.
- `listen-along` extends `FriendsRepository` with a `fetchTransientFriendNowPlaying(service, user)` that hits `/1/user/{name}/playing-now` (LB) or `user.getrecenttracks?limit=1` (Last.fm) and returns a synthetic `Friend` carrying a non-null `cachedTrackName`. The synthetic record is passed straight to the existing `MainViewModel.startListenAlong(friend)` flow.
- Spinoff banner branches on `playbackContext.id == "pool-based"` (a new sentinel) — for pool-based, render only `name`; for the existing `spinoff` type, render the established "Spinoff from <title>" template.
- UX polish: (a) immediate 30s acknowledgment toast on radio + listen-along entry; (b) playbar `isLoading` flag set true during pool-fetch; (c) listen-along friend tracks routed through `ResolverScoring.selectBest` (already done — we just verify and add a regression test).

**Tech Stack:** Kotlin (KMP commonMain + Android `:app`), Ktor, kotlinx.serialization, kotlinx.coroutines, Koin, Compose, JUnit + Robolectric (for `DeepLinkHandlerTest` style smoke tests).

**Branch:** `feature/protocol-phase-3` off `main`.

---

## Task 0: Branch setup

**Step 1: Create branch from main and confirm clean tree.**

Run:
```bash
cd /Users/jherskowitz/Development/parachord/parachord-mobile \
  && git checkout main \
  && git pull --ff-only \
  && git checkout -b feature/protocol-phase-3 \
  && git status
```

Expected: `On branch feature/protocol-phase-3` / `working tree clean`.

**Step 2: Confirm Phase 1+2 baseline tests pass.**

Run:
```bash
./gradlew :app:testDebugUnitTest :shared:testDebugUnitTest --tests "*deeplink*" --tests "*ProtocolPlay*" --tests "*ProtocolTracklist*"
```

Expected: BUILD SUCCESSFUL, 0 failures. Establishes the baseline so anything we break in this phase is visible immediately.

---

## Task 1: Re-shape `parsePlayRadio` to match the issue spec

The Phase 2 parser currently uses `?refillUrl=` as the URL-fed knob. Issue #121's input table says `?url=` is the **initial pool URL** and `?refill=` is the (optional) URL for **subsequent** refills. We need to swap the parser to that contract before wiring downstream consumers.

**Files:**
- Modify: `app/src/main/java/com/parachord/android/deeplink/DeepLinkHandler.kt:286-306`
- Modify: `shared/src/commonMain/kotlin/com/parachord/shared/deeplink/DeepLinkAction.kt:38-44` (rename `refillUrl` → `refillUrl` semantics — see below)
- Modify: `app/src/test/java/com/parachord/android/deeplink/DeepLinkHandlerTest.kt` (radio cases)

**Decision:** keep the field name `PlayRadio.refillUrl` since it's already in place; redefine its semantics as **the URL to use for refills only** (NOT the initial pool fetch). Initial pool is sourced from `input.url` OR `input.tracks`. This stays parity-faithful and avoids a churny rename.

**Step 1: Update the dispatch logic in `parsePlayRadio`.**

Old logic in `DeepLinkHandler.kt:286-306` selects mode by checking `refillUrl` first. New logic (per issue):
- `?artist=` AND no `?tracks=` AND no `?url=` → Mode B (`ArtistSeed(artist, title)`)
- otherwise (`?url=` or `?tracks=` present) → Mode C (`PoolBased`)
- neither → `Unknown`

`?refill=` is read separately (NOT used for mode dispatch) and forwarded into `PlayRadio.refillUrl`.

Replace `parsePlayRadio` body:

```kotlin
private fun parsePlayRadio(uri: Uri): DeepLinkAction {
    val input = parseProtocolPlayInput(uri)
    // ?refill= is the explicit refill URL for Mode C; falls back to nothing.
    // (We accept ?refillUrl= as a legacy alias for back-compat with anything
    // generated against the Phase 2 build.)
    val refillUrl = uri.clampedParam("refill") ?: uri.clampedParam("refillUrl")
    val name = uri.clampedParam("name")
    val shuffle = uri.clampedParam("shuffle") == "1"
    val artist = uri.clampedParam("artist")
    val title = uri.clampedParam("title")

    val hasUrl = !input?.url.isNullOrBlank()
    val hasTracks = input?.tracks?.isNotEmpty() == true
    val hasArtist = !artist.isNullOrBlank()

    val mode: RadioMode = when {
        // Mode B: artist seed, no inline pool, no URL pool
        hasArtist && !hasUrl && !hasTracks -> RadioMode.ArtistSeed(artist!!, title)
        // Mode C: explicit pool (URL or inline)
        hasUrl || hasTracks -> RadioMode.PoolBased
        else -> return DeepLinkAction.Unknown(uri.toString())
    }
    return DeepLinkAction.PlayRadio(
        mode = mode,
        input = input,
        refillUrl = refillUrl,
        name = name,
        shuffle = shuffle,
    )
}
```

**Step 2: Run the existing radio parser tests to see what breaks.**

Run:
```bash
./gradlew :app:testDebugUnitTest --tests "com.parachord.android.deeplink.DeepLinkHandlerTest"
```

Expected: failures around `?refillUrl=` mode dispatch (because we now require `?url=`/`?tracks=` for Mode C even when `?refillUrl=` is set). Read each failure and adjust the URI in the test fixture: replace `?refillUrl=…` with either `?url=…` (if testing initial pool from URL) or `?tracks=…&refill=…` (if testing inline pool with refill).

**Step 3: Add a new test for the issue's `?name=` precedence rule.**

Add to `DeepLinkHandlerTest.kt`:

```kotlin
@Test
fun parsePlayRadio_nameTakesPrecedenceOverTitle() {
    val a = handler.parse(Uri.parse("parachord://play/radio?url=https%3A%2F%2Fexample.com%2Fp.jspf&name=My%20Station&title=ignored"))
    assertTrue(a is DeepLinkAction.PlayRadio)
    val pr = a as DeepLinkAction.PlayRadio
    assertEquals("My Station", pr.name)
}

@Test
fun parsePlayRadio_modeBOnlyWhenNoUrlOrTracks() {
    val a = handler.parse(Uri.parse("parachord://play/radio?artist=Slowdive"))
    val pr = a as DeepLinkAction.PlayRadio
    assertTrue(pr.mode is RadioMode.ArtistSeed)
}

@Test
fun parsePlayRadio_modeCEvenIfArtistAlsoPresent() {
    // Artist + tracks → Mode C (pool wins per issue spec).
    val a = handler.parse(Uri.parse("parachord://play/radio?artist=Foo&tracks=W3sidGl0bGUiOiJUIiwiYXJ0aXN0IjoiQSJ9XQ%3D%3D"))
    val pr = a as DeepLinkAction.PlayRadio
    assertTrue(pr.mode is RadioMode.PoolBased)
}
```

**Step 4: Run all parser tests, confirm green.**

Run: `./gradlew :app:testDebugUnitTest --tests "com.parachord.android.deeplink.DeepLinkHandlerTest"`
Expected: all green.

**Step 5: Commit.**

```bash
git add app/src/main/java/com/parachord/android/deeplink/DeepLinkHandler.kt \
        app/src/test/java/com/parachord/android/deeplink/DeepLinkHandlerTest.kt
git commit -m "deeplink: re-shape parsePlayRadio to ?url= / ?tracks= / ?refill= (#121 [1/N])"
```

---

## Task 2: ListenBrainz token auto-attach (Ktor plugin)

Issue §D: every outbound request to `api.listenbrainz.org` must carry `Authorization: Token <token>` when the user has a token configured. Apply at HttpClient construction so every shared call benefits transparently.

**Files:**
- Create: `shared/src/commonMain/kotlin/com/parachord/shared/api/ListenBrainzAuthPlugin.kt`
- Modify: `shared/src/commonMain/kotlin/com/parachord/shared/api/HttpClientFactory.kt` (look up actual file: search for where the shared `HttpClient` is built — `find shared -name "HttpClient*.kt"`)
- Modify: shared `SharedModule.kt` if the plugin needs SettingsStore at construction time
- Test: `shared/src/commonTest/kotlin/com/parachord/shared/api/ListenBrainzAuthPluginTest.kt`

**Step 1: Locate the HttpClient construction site.**

Run: `grep -rn "HttpClient(" shared/src/commonMain --include="*.kt" -l`. There's a `HttpClientFactory` — read it before editing. Record the file path here in the plan as a comment for the next step.

**Step 2: Write the failing test.**

```kotlin
// shared/src/commonTest/kotlin/com/parachord/shared/api/ListenBrainzAuthPluginTest.kt
package com.parachord.shared.api

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ListenBrainzAuthPluginTest {

    @Test
    fun attachesTokenForListenBrainzHost() = runTest {
        var seenAuth: String? = null
        val engine = MockEngine { req ->
            seenAuth = req.headers["Authorization"]
            respond("{}", HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
        }
        val client = HttpClient(engine) {
            install(ListenBrainzAuthPlugin) { tokenProvider = { "secret-token" } }
        }
        client.get("https://api.listenbrainz.org/1/user/foo/playing-now")
        assertEquals("Token secret-token", seenAuth)
    }

    @Test
    fun doesNotAttachTokenForOtherHosts() = runTest {
        var seenAuth: String? = null
        val engine = MockEngine { req ->
            seenAuth = req.headers["Authorization"]
            respond("{}", HttpStatusCode.OK)
        }
        val client = HttpClient(engine) {
            install(ListenBrainzAuthPlugin) { tokenProvider = { "secret-token" } }
        }
        client.get("https://example.com/something")
        assertNull(seenAuth)
    }

    @Test
    fun noTokenConfigured_passesThroughWithoutAuthHeader() = runTest {
        var seenAuth: String? = null
        val engine = MockEngine { req ->
            seenAuth = req.headers["Authorization"]
            respond("{}", HttpStatusCode.Unauthorized)
        }
        val client = HttpClient(engine) {
            install(ListenBrainzAuthPlugin) { tokenProvider = { null } }
        }
        client.get("https://api.listenbrainz.org/1/user/foo/playing-now")
        assertNull(seenAuth)
    }

    @Test
    fun preservesExistingAuthHeader() = runTest {
        // submitRecordingFeedback already sets its own Authorization header.
        // The plugin must NOT clobber it.
        var seenAuth: String? = null
        val engine = MockEngine { req ->
            seenAuth = req.headers["Authorization"]
            respond("{}", HttpStatusCode.OK)
        }
        val client = HttpClient(engine) {
            install(ListenBrainzAuthPlugin) { tokenProvider = { "plugin-token" } }
        }
        client.get("https://api.listenbrainz.org/1/user/foo/playing-now") {
            io.ktor.client.request.headers { append("Authorization", "Token explicit-token") }
        }
        assertEquals("Token explicit-token", seenAuth)
    }
}
```

Run: `./gradlew :shared:testDebugUnitTest --tests "*ListenBrainzAuthPlugin*"`
Expected: FAIL — `ListenBrainzAuthPlugin` not defined.

**Step 3: Implement the plugin.**

```kotlin
// shared/src/commonMain/kotlin/com/parachord/shared/api/ListenBrainzAuthPlugin.kt
package com.parachord.shared.api

import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.http.HttpHeaders

/**
 * Auto-attaches `Authorization: Token <token>` to every outbound request
 * targeting `api.listenbrainz.org` (or any subdomain thereof).
 *
 * Mirrors desktop's `69116a4` / `31078cf` — the LB token configured for
 * scrobbling is the same token that authenticates radio fetches and
 * `playing-now` lookups. Centralizing this in a plugin means every call
 * site (initial Mode C fetch, refill loop, listen-along nowplaying) gets
 * it for free.
 *
 * Configuration takes a token provider lambda rather than a string so
 * the plugin always picks up the current token (the user can change it
 * at runtime via Settings without restarting the HttpClient).
 *
 * If the call site has already set an `Authorization` header explicitly
 * (e.g. `ListenBrainzClient.submitRecordingFeedback`), the plugin does
 * NOT overwrite it.
 */
class ListenBrainzAuthConfig {
    var tokenProvider: suspend () -> String? = { null }
}

val ListenBrainzAuthPlugin = createClientPlugin("ListenBrainzAuth", ::ListenBrainzAuthConfig) {
    val tokenProvider = pluginConfig.tokenProvider
    onRequest { request, _ ->
        val host = request.url.host.lowercase()
        val isLbHost = host == "api.listenbrainz.org" || host.endsWith(".api.listenbrainz.org")
        if (!isLbHost) return@onRequest
        if (request.headers.contains(HttpHeaders.Authorization)) return@onRequest
        val token = try {
            tokenProvider()
        } catch (e: Throwable) {
            // SettingsStore lookup should never throw, but defend anyway —
            // a thrown exception here would kill every LB call.
            null
        }
        if (!token.isNullOrBlank()) {
            request.headers.append(HttpHeaders.Authorization, "Token $token")
        }
    }
}
```

Run the test: `./gradlew :shared:testDebugUnitTest --tests "*ListenBrainzAuthPlugin*"`
Expected: PASS.

**Step 4: Wire the plugin into the shared HttpClient.**

In whichever file constructs the shared `HttpClient` (likely `HttpClientFactory.kt` in `shared/commonMain` plus the platform actuals — verify with the grep from Step 1), add `install(ListenBrainzAuthPlugin) { tokenProvider = ... }`. The provider closes over `SettingsStore` (already a Koin singleton).

If `HttpClientFactory` is a top-level expect/actual function with no DI access, the cleanest fix is to thread the SettingsStore through Koin's `HttpClient` factory binding. Read `shared/src/commonMain/kotlin/com/parachord/shared/SharedModule.kt` and the platform-specific `HttpClientFactory.android.kt` / `.ios.kt` to see how it's wired today, then add the SettingsStore dep where the client is built.

Concretely (rough sketch — verify against actual code):

```kotlin
// SharedModule.kt or wherever httpClient { ... } is bound
single<HttpClient> {
    val settings: SettingsStore = get()
    buildHttpClient { 
        install(ListenBrainzAuthPlugin) { tokenProvider = { settings.getListenBrainzToken() } }
    }
}
```

**Step 5: Verify nothing regressed.**

Run: `./gradlew :shared:testDebugUnitTest :app:testDebugUnitTest`
Expected: green. Pay attention to `ListenBrainzClientTest` — `submitRecordingFeedback` must still produce its explicit header (covered by the "preservesExistingAuthHeader" test above).

**Step 6: Commit.**

```bash
git add shared/src/commonMain/kotlin/com/parachord/shared/api/ListenBrainzAuthPlugin.kt \
        shared/src/commonTest/kotlin/com/parachord/shared/api/ListenBrainzAuthPluginTest.kt \
        shared/src/commonMain/kotlin/com/parachord/shared/SharedModule.kt \
        # plus whichever HttpClientFactory file was edited
git commit -m "api: ListenBrainz token auto-attach via Ktor plugin (#121 [2/N])"
```

---

## Task 3: Mode B — wire `parachord://play/radio?artist=...` to a generalised spinoff

The existing `PlaybackController.startSpinoff()` seeds from `stateHolder.state.value.currentTrack`. For Mode B we need to seed from a (artist, title?) pair supplied by the deeplink, with no current-track requirement.

**Files:**
- Modify: `app/src/main/java/com/parachord/android/playback/PlaybackController.kt` (extract a private `startSpinoffWithSeed(artist, title?)` helper; have the existing `startSpinoff()` delegate to it)
- Modify: `app/src/main/java/com/parachord/android/deeplink/DeepLinkViewModel.kt` (replace the "Coming soon" stub for `PlayRadio` with a Mode B dispatch)
- Test: `app/src/test/java/com/parachord/android/deeplink/ProtocolPlayHandlerTest.kt` (or new sibling `ProtocolPlayRadioHandlerTest.kt`)

**Step 1: Extract `startSpinoffWithSeed` from `startSpinoff` (refactor only).**

Lift the body of `startSpinoff()` (lines ~1416–1521 of `PlaybackController.kt`) into a new method:

```kotlin
fun startSpinoffWithSeed(artist: String, title: String?, displayName: String? = null) {
    if (stateHolder.state.value.spinoffMode) return
    spinoffJob?.cancel()
    stateHolder.update { copy(spinoffLoading = true) }
    spinoffJob = scope.launch(Dispatchers.IO) {
        try {
            val response = lastFmClient.getSimilarTracks(
                track = title.orEmpty(),
                artist = artist,
                apiKey = BuildConfig.LASTFM_API_KEY,
                limit = SPINOFF_SIMILAR_LIMIT,
            )
            // ...same body as today, but build the resolved pool and set
            // PlaybackContext(name = displayName ?: "Spinoff from $title")...
        }
    }
}

fun startSpinoff() {
    val track = stateHolder.state.value.currentTrack ?: return
    startSpinoffWithSeed(track.artist, track.title, displayName = "Spinoff from ${track.title}")
}
```

Caveat: when `title` is blank (Mode B can pass artist-only), Last.fm's `getSimilarTracks` falls back to artist similarity. Check `LastFmClient.getSimilarTracks` to confirm — if it requires a non-empty track, switch to `getSimilarArtists` + `topTracks` cascade for artist-only seeds. (Read the desktop's Mode B implementation if unsure — `app.js` search for "play/radio" handler.)

**Step 2: Run the existing spinoff tests, confirm refactor is invisible.**

Run: `./gradlew :app:testDebugUnitTest --tests "*Spinoff*" --tests "*PlaybackController*"`
Expected: green (refactor is behavior-preserving).

**Step 3: Wire `PlayRadio` dispatch for Mode B in `DeepLinkViewModel`.**

Replace the `PlayRadio, ListenAlong -> "Coming soon"` block (around line 317) with:

```kotlin
is DeepLinkAction.PlayRadio -> dispatchPlayRadio(action)
is DeepLinkAction.ListenAlong -> dispatchListenAlong(action)
```

Add `dispatchPlayRadio`:

```kotlin
private suspend fun dispatchPlayRadio(action: DeepLinkAction.PlayRadio) {
    when (val mode = action.mode) {
        is RadioMode.ArtistSeed -> {
            protocolPlayTeardown.prepareForNewPlayback()
            playbackController.startSpinoffWithSeed(
                artist = mode.artist,
                title = mode.title,
                displayName = action.name ?: mode.title?.let { "Radio: ${mode.artist} – $it" } ?: "Radio: ${mode.artist}",
            )
            _navEvents.emit(DeepLinkNavEvent.Toast("Building radio…"))
        }
        is RadioMode.PoolBased -> {
            // Wired in Task 4
            _navEvents.emit(DeepLinkNavEvent.Toast("Mode C coming next commit"))
        }
    }
}
```

(Defer `ListenAlong` until Task 7.)

**Step 4: Add a smoke test.**

```kotlin
// app/src/test/java/com/parachord/android/deeplink/PlayRadioModeBTest.kt
@Test
fun modeB_callsTeardownThenStartSpinoffWithSeed() = runTest {
    val pc = mockk<PlaybackController>(relaxed = true)
    val td = mockk<ProtocolPlayTeardown>(relaxed = true)
    coEvery { td.prepareForNewPlayback() } just runs
    val vm = DeepLinkViewModel(/* deps */)  // construct via Koin or hand-wire
    vm.dispatchPlayRadioForTest(
        DeepLinkAction.PlayRadio(
            mode = RadioMode.ArtistSeed("Slowdive", null),
            input = null,
        )
    )
    coVerify(exactly = 1) { td.prepareForNewPlayback() }
    verify(exactly = 1) { pc.startSpinoffWithSeed(eq("Slowdive"), isNull(), any()) }
}
```

If `DeepLinkViewModel` is hard to construct in unit tests, instead extract a small helper class `PlayRadioDispatcher` that takes (`teardown`, `playbackController`, `navEmitter`) and verify against that. The pattern matches the Phase 2 `ProtocolPlayHandler` extraction.

**Step 5: Commit.**

```bash
git commit -m "playback+deeplink: Mode B (artist seed) for play/radio (#121 [3/N])"
```

---

## Task 4: Mode C — pool-based spinoff entry point

`startPoolBasedSpinoff(initialPool, displayName, refillUrl?)` accepts a pre-resolved `List<TrackEntity>` (built from the Mode C input — either inline `tracks=` or a fetched JSPF/XSPF tracklist) and stages it the same way `startSpinoffWithSeed` does, but skips the Last.fm seed step.

**Files:**
- Modify: `app/src/main/java/com/parachord/android/playback/PlaybackController.kt`
- Modify: `app/src/main/java/com/parachord/android/deeplink/DeepLinkViewModel.kt`
- Modify: `app/src/main/java/com/parachord/android/deeplink/ProtocolPlayHandler.kt` (add `handle(PlayRadio)` overload that builds the entity list)
- Test: extend `PlayRadioModeBTest.kt` (rename to `PlayRadioTest.kt`)

**Step 1: Add `startPoolBasedSpinoff` to `PlaybackController`.**

Below `startSpinoffWithSeed`:

```kotlin
/**
 * Pool-based spinoff (Mode C of `parachord://play/radio`).
 *
 * No Last.fm seed step — the caller supplies an already-resolved pool.
 * Mirrors desktop's "externally curated pool" path.
 *
 * `refillUrl` is optional. When set, the refill loop (Task 5) will
 * re-fetch from it once `spinoffPool.size < 3` (with rate-limit + 3-empty
 * stop + dedup). When null, the radio runs to exhaustion and exits.
 *
 * `displayName` is the station name for the banner — for pool-based
 * spinoff there is no "source track", so the banner code (Task 6)
 * renders just this string instead of "Spun off from X by Y".
 */
fun startPoolBasedSpinoff(
    initialPool: List<TrackEntity>,
    displayName: String,
    refillUrl: String? = null,
) {
    if (initialPool.isEmpty()) {
        Log.w(TAG, "startPoolBasedSpinoff: empty initialPool, ignoring")
        return
    }
    spinoffJob?.cancel()
    spinoffPool.clear()
    spinoffPool.addAll(initialPool)
    spinoffSourceTrack = null  // pool-based has no source — see banner branch
    preSpinoffContext = queueManager.playbackContext

    // Sentinel context: type="spinoff", id="pool-based" so the banner
    // can branch without reading sourceTrack (which is null here).
    queueManager.setContext(
        PlaybackContext(type = "spinoff", name = displayName, id = "pool-based")
    )

    poolRefillUrl = refillUrl
    poolRefillEmptyCount = 0
    poolLastRefillTs = 0L

    stateHolder.update {
        copy(spinoffMode = true, spinoffLoading = false, spinoffAvailable = true)
    }

    // Play the first track immediately (don't wait for queue exhaustion).
    val first = spinoffPool.removeAt(0)
    advanceJob = scope.launch {
        try { playTrackInternal(first) }
        catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.e(TAG, "Pool spinoff first-track failed: ${e.message}", e)
        }
    }
}

// New fields near `spinoffPool`:
private var poolRefillUrl: String? = null
private var poolRefillEmptyCount: Int = 0
private var poolLastRefillTs: Long = 0L
```

Also extend `exitSpinoff()` to clear the new fields (set `poolRefillUrl = null; poolRefillEmptyCount = 0; poolLastRefillTs = 0L`).

**Step 2: Add a `handle(PlayRadio)` method on `ProtocolPlayHandler`.**

The handler already has `handle(PlayAlbum)` / `handle(PlayPlaylist)`. Add a parallel for `PlayRadio` Mode C — fetch + parse + entity-build with per-track tagging:

```kotlin
suspend fun handlePoolBased(action: DeepLinkAction.PlayRadio): ProtocolPlayResult {
    val input = action.input
        ?: return ProtocolPlayResult.Failed("Radio missing input")
    // Resolve initial pool: input.url (JSPF/XSPF/JSON fetch) OR input.tracks (inline).
    val opts = ProtocolResolveOptions(
        allowMbid = false, allowProviderId = false,
        allowUrl = true, allowTracks = true, allowArtistTitleAlbum = false,
    )
    val resolved = try {
        resolveProtocolPlayInput(input, opts, resolver)
            ?: return ProtocolPlayResult.Failed("Radio: nothing to play")
    } catch (e: Exception) {
        return ProtocolPlayResult.Failed(e.message ?: "Radio fetch failed")
    }
    if (resolved.tracks.isEmpty()) return ProtocolPlayResult.Failed("Radio: empty pool")

    val ts = currentTimeMillis()
    val entities = resolved.tracks.mapIndexed { idx, t ->
        TrackEntity(
            id = "protocol-radio-$ts-$idx",
            title = t.title,
            artist = t.artist,
            album = t.album,
            artworkUrl = resolved.albumArt,
        )
    }
    val displayName = action.name
        ?: input.title
        ?: resolved.displayName
        ?: "Radio"

    // Pre-resolve the first track so playback starts on a known-good source
    // (matches Phase 2's discipline — see ProtocolPlayHandler.runHandle Step 4).
    try {
        trackResolverCache.resolveInBackground(listOf(entities.first()), backfillDb = false)
    } catch (e: Exception) {
        Log.w(TAG, "Pool first-track pre-resolve failed: ${e.message}")
    }

    teardown.prepareForNewPlayback()
    playbackController.startPoolBasedSpinoff(entities, displayName, action.refillUrl)
    if (entities.size > 1) {
        trackResolverCache.resolveInBackground(entities.drop(1), backfillDb = false)
    }
    return ProtocolPlayResult.Started(displayName, entities.size)
}
```

**Step 3: Wire it in `DeepLinkViewModel.dispatchPlayRadio` for `PoolBased`:**

```kotlin
is RadioMode.PoolBased -> {
    _navEvents.emit(DeepLinkNavEvent.Toast("Building radio…"))
    when (val r = protocolPlayHandler.handlePoolBased(action)) {
        is ProtocolPlayResult.Started ->
            _navEvents.emit(DeepLinkNavEvent.Toast("Playing ${r.displayName}"))
        is ProtocolPlayResult.Failed ->
            _navEvents.emit(DeepLinkNavEvent.Toast("Radio failed: ${r.reason}"))
    }
}
```

**Step 4: Tests for Mode C.**

```kotlin
@Test
fun modeC_inlineTracks_buildsPoolWithStableIds() = runTest {
    val handler = buildHandler(...)
    val tracks = listOf(ProtocolTrack("A1", "T1"), ProtocolTrack("A2", "T2"))
    val action = DeepLinkAction.PlayRadio(
        mode = RadioMode.PoolBased,
        input = ProtocolPlayInput(tracks = tracks),
        name = "My Station",
    )
    val r = handler.handlePoolBased(action)
    assertTrue(r is ProtocolPlayResult.Started)
    assertEquals("My Station", (r as ProtocolPlayResult.Started).displayName)
    val poolSlot = slot<List<TrackEntity>>()
    verify { playbackController.startPoolBasedSpinoff(capture(poolSlot), "My Station", null) }
    assertEquals(2, poolSlot.captured.size)
    assertTrue(poolSlot.captured.first().id.startsWith("protocol-radio-"))
}

@Test
fun modeC_url_routesToResolveByUrl() = runTest { /* ... */ }
@Test
fun modeC_emptyPool_returnsFailedAndDoesNotTearDown() = runTest { /* ... */ }
@Test
fun modeC_namePrecedence_namePreferredOverInputTitleAndResolvedName() = runTest { /* ... */ }
```

**Step 5: Commit.**

```bash
git commit -m "playback+deeplink: Mode C (pool-based) for play/radio (#121 [4/N])"
```

---

## Task 5: Refill loop

Trigger when `spinoffPool.size < 3`, soft rate-limit 5s, stop after 3 consecutive empty refills, dedup by `mbid → isrc → (artist|title)`.

**Files:**
- Modify: `app/src/main/java/com/parachord/android/playback/PlaybackController.kt` (refill loop)
- Test: new `app/src/test/java/com/parachord/android/playback/PoolRefillTest.kt`

**Step 1: Write tests first.**

```kotlin
class PoolRefillTest {
    @Test fun underThree_triggersRefill_andAppendsFreshTracks() { /* mock fetcher */ }
    @Test fun rateLimit_twoTriggersWithin5s_onlyOneFetch() { /* virtual time */ }
    @Test fun threeEmptyRefills_stopsRefilling() { /* fixed clock */ }
    @Test fun dedup_byMbid_isrcOrArtistTitle_countsAsEmpty() { /* fixed input */ }
    @Test fun httpError_countsAsEmpty() { /* fetcher throws */ }
    @Test fun exitSpinoff_resetsRefillState() { /* call exitSpinoff then re-enter, verify rate-limit gate is fresh */ }
}
```

For testability, factor the fetcher behind a `suspend (refillUrl: String) -> List<ProtocolTrack>` lambda parameter on `PlaybackController` (constructor or setter). In production it's `ProtocolInputResolver.resolveByUrl(url).tracks`; in tests it's a fake.

**Step 2: Implement the refill check.**

In `skipNextInternal`, after the `spinoffPool.removeAt(0)` line (in the spinoff branch), check whether to trigger a refill:

```kotlin
if (spinoffPool.size < 3 && poolRefillUrl != null) {
    triggerRefillIfAllowed()
}
```

`triggerRefillIfAllowed` is a fire-and-forget coroutine:

```kotlin
private fun triggerRefillIfAllowed() {
    val now = currentTimeMillis()
    if (now - poolLastRefillTs < 5_000) return     // soft rate-limit
    if (poolRefillEmptyCount >= 3) return          // stop condition
    val url = poolRefillUrl ?: return
    poolLastRefillTs = now
    scope.launch(Dispatchers.IO) {
        val fresh = try {
            poolFetcher(url)  // injected lambda
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            Log.w(TAG, "Pool refill fetch failed: ${e.message}")
            emptyList()
        }
        val filtered = dedupAgainstPool(fresh)
        if (filtered.isEmpty()) {
            poolRefillEmptyCount += 1
            Log.d(TAG, "Pool refill empty (count=$poolRefillEmptyCount)")
        } else {
            poolRefillEmptyCount = 0
            val ts = currentTimeMillis()
            // CRITICAL: refilled tracks inherit the existing pool's first
            // track's _playbackContext so the banner stays consistent
            // (issue §F).  We're already inside the spinoff context so
            // this is a no-op for the queue manager — tagging is per-track
            // metadata only.
            val entities = filtered.mapIndexed { i, t ->
                TrackEntity(
                    id = "protocol-radio-$ts-$i",
                    title = t.title,
                    artist = t.artist,
                    album = t.album,
                )
            }
            spinoffPool.addAll(entities)
            // Background-resolve so they have sources by the time we play them.
            trackResolverCache.resolveInBackground(entities, backfillDb = false)
        }
    }
}

private fun dedupAgainstPool(fresh: List<ProtocolTrack>): List<ProtocolTrack> {
    val mbids = spinoffPool.mapNotNull { it.recordingMbid?.lowercase() }.toSet()
    val isrcs = spinoffPool.mapNotNull { it.isrc?.uppercase() }.toSet()
    val titleArtists = spinoffPool.map {
        "${it.artist.lowercase()}|${it.title.lowercase()}"
    }.toSet()
    return fresh.filter { t ->
        when {
            t.mbid?.lowercase()?.let { it in mbids } == true -> false
            t.isrc?.uppercase()?.let { it in isrcs } == true -> false
            "${t.artist.lowercase()}|${t.title.lowercase()}" in titleArtists -> false
            else -> true
        }
    }
}
```

(Verify whether `TrackEntity` already exposes `isrc`. If not, dedup falls back to `mbid` + `(artist|title)` — note that as a known gap and file a follow-up if needed.)

**Step 3: Run tests.**

`./gradlew :app:testDebugUnitTest --tests "*PoolRefill*"`
Expected: green.

**Step 4: Commit.**

```bash
git commit -m "playback: Mode C refill loop (rate-limit, 3-empty stop, dedup) (#121 [5/N])"
```

---

## Task 6: Pool-based spinoff banner branch

For the regular spinoff, `playbackContext.name == "Spinoff from <title>"` already renders correctly. For pool-based, `name == station name` and we want the banner to render just that string.

The branch lives in `QueueSheet.kt:99-102`:

```kotlin
"spinoff" -> playbackContext.name // "Spinoff from {track}"
```

Issue §E says: when a pool-based spinoff is active, render only the station name (no "Spun off from X by Y" prefix). The cleanest signal is `playbackContext.id == "pool-based"` (set in Task 4):

**Files:**
- Modify: `app/src/main/java/com/parachord/android/ui/screens/nowplaying/QueueSheet.kt:99-102`
- Modify: `app/src/main/java/com/parachord/android/ui/screens/nowplaying/NowPlayingScreen.kt` if a banner is rendered there too (search for `"Spun off"` / `"Spinoff from"`)

**Step 1: Find every banner render site.**

Run: `grep -rn "spinoff\|Spinoff\|Spun off" app/src/main/java/com/parachord/android/ui --include="*.kt"`. Update every site that branches on `playbackContext.type == "spinoff"` to also check `id`.

**Step 2: Update the branch.**

```kotlin
"spinoff" -> {
    if (playbackContext.id == "pool-based") {
        playbackContext.name  // station name only
    } else {
        playbackContext.name  // "Spinoff from {track}"
    }
}
```

(Both arms render `playbackContext.name` because Task 4 already sets `name = displayName` for pool-based and Task 3 keeps `name = "Spinoff from $title"` for the seed case. The branch is here as a hook for divergent future styling — the issue spec just says "render the station name", which we already do. Document that and move on.)

**Step 3: Add a Compose preview / smoke test if there's a banner-specific UI test harness.**

Skip if no harness exists; this branch is structural and Task 4's tests already verify `playbackContext.id == "pool-based"`.

**Step 4: Commit.**

```bash
git commit -m "ui: pool-based spinoff banner branches on context id (#121 [6/N])"
```

---

## Task 7: `parachord://listen-along` — known + transient friend

Friend lookup → if found, call `MainViewModel.startListenAlong(friend)`. Otherwise fetch `/playing-now` (LB) or `getrecenttracks?limit=1` (Last.fm), build a transient `Friend` if there's a current track, otherwise toast "<user> is not currently listening on <service>."

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/parachord/shared/api/ListenBrainzClient.kt` (add `getPlayingNow(username: String): LbListen?`)
- Modify: `shared/src/commonMain/kotlin/com/parachord/shared/api/LastFmClient.kt` (add `getNowPlaying(username: String, apiKey: String): LfNowPlaying?`)
- Modify: `shared/src/commonMain/kotlin/com/parachord/shared/repository/FriendsRepository.kt` (add `fetchTransientFriendNowPlaying(service, user): Friend?`)
- Modify: `shared/src/commonMain/kotlin/com/parachord/shared/model/Friend.kt` (add `transient: Boolean = false`)
- Modify: `app/src/main/java/com/parachord/android/deeplink/DeepLinkViewModel.kt` (add `dispatchListenAlong`)
- Modify: `app/src/main/java/com/parachord/android/ui/MainViewModel.kt` (no API change — `startListenAlong(friend)` already accepts a `Friend`)
- Tests: extend `FriendsRepositoryTest`, new `ListenAlongTest`

**Step 1: Add `Friend.transient` field.**

Add `val transient: Boolean = false` to `Friend.kt`. Default false preserves all call sites.

Run: `./gradlew :shared:testDebugUnitTest`. Confirm no breaks.

**Step 2: Add LB `getPlayingNow`.**

```kotlin
// ListenBrainzClient.kt
suspend fun getPlayingNow(username: String): LbListen? {
    return try {
        val response = httpClient.get("$BASE_URL/1/user/$username/playing-now")
        if (!response.status.isSuccess()) return null
        val parsed = json.decodeFromString<ListensWire>(response.bodyAsText())
        val listen = parsed.payload?.listens?.firstOrNull() ?: return null
        val md = listen.trackMetadata ?: return null
        LbListen(
            artistName = md.artistName.orEmpty(),
            trackName = md.trackName.orEmpty(),
            releaseName = md.releaseName?.ifBlank { null },
            listenedAt = listen.listenedAt ?: 0L,
        )
    } catch (e: CancellationException) { throw e }
    catch (e: Throwable) { Log.e(TAG, "playing-now failed for $username", e); null }
}
```

The Authorization header is now auto-attached by the Ktor plugin from Task 2 — no need to pass a token here.

**Step 3: Add Last.fm `getNowPlaying`.**

```kotlin
// LastFmClient.kt — pseudo, adapt to existing patterns
suspend fun getNowPlaying(username: String, apiKey: String): LfNowPlaying? {
    val response = guardedGet { 
        parameter("method", "user.getrecenttracks")
        parameter("user", username)
        parameter("limit", 1)
        parameter("api_key", apiKey)
        parameter("format", "json")
    }
    val first = response?.recenttracks?.track?.firstOrNull() ?: return null
    if (first.attr?.nowplaying != "true") return null  // only true now-playing
    return LfNowPlaying(
        artist = first.artist?.text ?: first.artist?.name.orEmpty(),
        title = first.name.orEmpty(),
        album = first.album?.text,
        imageUrl = first.image?.lastOrNull()?.text,
    )
}

@Serializable
data class LfNowPlaying(val artist: String, val title: String, val album: String?, val imageUrl: String?)
```

(Read the existing Last.fm wire types around line 480 — `nowplaying: String?` is already in the model. Confirm before writing.)

**Step 4: Add `FriendsRepository.fetchTransientFriendNowPlaying`.**

```kotlin
suspend fun fetchTransientFriendNowPlaying(service: String, user: String): Friend? {
    val (artist, title, album, art) = when (service) {
        "listenbrainz" -> {
            val l = listenBrainzClient.getPlayingNow(user) ?: return null
            Quad(l.artistName, l.trackName, l.releaseName, null)
        }
        "lastfm" -> {
            val l = lastFmClient.getNowPlaying(user, lastFmApiKey) ?: return null
            Quad(l.artist, l.title, l.album, l.imageUrl)
        }
        else -> return null
    }
    if (artist.isBlank() || title.isBlank()) return null
    return Friend(
        id = "transient:$service:$user",
        username = user,
        service = service,
        displayName = user,
        avatarUrl = null,
        addedAt = currentTimeMillis(),
        cachedTrackName = title,
        cachedTrackArtist = artist,
        cachedTrackAlbum = album,
        cachedTrackArtworkUrl = art,
        cachedTrackTimestamp = currentTimeMillis() / 1000,  // makes isOnAir=true
        transient = true,
    )
}
```

(Use a private data class instead of `Quad` if there's no existing utility; this is just an inlining helper.)

**Step 5: Wire in `DeepLinkViewModel.dispatchListenAlong`.**

```kotlin
private suspend fun dispatchListenAlong(action: DeepLinkAction.ListenAlong) {
    if (action.service !in setOf("listenbrainz", "lastfm")) {
        _navEvents.emit(DeepLinkNavEvent.Toast("Unknown service: ${action.service}"))
        return
    }
    _navEvents.emit(DeepLinkNavEvent.Toast("Catching up to ${action.user}…"))

    // 1. Local lookup (case-insensitive on username).
    val saved = friendsRepository.findByServiceAndUsername(action.service, action.user)
    if (saved != null) {
        _navEvents.emit(DeepLinkNavEvent.StartListenAlong(saved))
        return
    }

    // 2. Transient fallback.
    val transient = try {
        friendsRepository.fetchTransientFriendNowPlaying(action.service, action.user)
    } catch (e: Exception) {
        Log.w(TAG, "Transient now-playing fetch failed", e)
        null
    }
    if (transient == null) {
        _navEvents.emit(DeepLinkNavEvent.Toast("${action.user} is not currently listening on ${action.service}"))
        return
    }
    _navEvents.emit(DeepLinkNavEvent.StartListenAlong(transient))
}
```

Add a new `DeepLinkNavEvent.StartListenAlong(friend: Friend)` and a corresponding observer in `MainActivity` that calls `mainViewModel.startListenAlong(friend)`.

**Important:** the listen-along teardown rule (issue §G "Teardown for listen-along: tear down spinoff + clearQueue, but DON'T tear down listen-along — switching from friend A to friend B should swap, not terminate"). The existing `MainViewModel.startListenAlong()` already calls `stopListenAlong(silent=true)` at the top, which handles the swap. We must NOT call `protocolPlayTeardown.prepareForNewPlayback()` here, because that would call the listen-along stopper as part of its three-step teardown. Instead, just call `mainViewModel.startListenAlong(friend)` directly — its internal stop+restart handles the swap.

If we want the spinoff-exit + queue-clear half of the teardown but skip the listen-along stopper, expose a `prepareForListenAlongHandover()` variant on `ProtocolPlayTeardown` that runs only steps 1 and 3. Add this variant and call it from `dispatchListenAlong` before emitting `StartListenAlong`.

**Step 6: `findByServiceAndUsername` on `FriendsRepository`.**

If it doesn't exist yet, add:

```kotlin
suspend fun findByServiceAndUsername(service: String, username: String): Friend? =
    friendDao.getAll().firstOrNull {
        it.service == service && it.username.equals(username, ignoreCase = true)
    }
```

**Step 7: Tests.**

```kotlin
@Test fun listenAlong_knownFriend_startsImmediately()
@Test fun listenAlong_unknownFriendWithCurrentTrack_startsTransient()
@Test fun listenAlong_unknownFriendNoCurrentTrack_emitsCalmToast()
@Test fun listenAlong_unknownService_emitsErrorToast()
@Test fun friendsRepository_transientLB_buildsCorrectShape()
@Test fun friendsRepository_transientLF_returnsNullWhenNotNowplaying()
```

**Step 8: Commit.**

```bash
git commit -m "deeplink+friends: parachord://listen-along (known + transient) (#121 [7/N])"
```

---

## Task 8: UX polish — acknowledgment toasts, playbar loading, friend-track confidence

From the issue's "Additional sub-tasks" comment.

**Files:**
- Modify: `app/src/main/java/com/parachord/android/playback/PlaybackState.kt` (add `isPlaybarLoading: Boolean = false`)
- Modify: `app/src/main/java/com/parachord/android/playback/PlaybackController.kt` (set/unset the flag around `startPoolBasedSpinoff` and the radio fetch)
- Modify: `app/src/main/java/com/parachord/android/ui/components/MiniPlayer.kt` and `ui/screens/nowplaying/NowPlayingScreen.kt` to render a small loading indicator when `isPlaybarLoading` is true and `currentTrack == null`
- Modify: `app/src/main/java/com/parachord/android/ui/MainViewModel.kt` (verify friend-track resolver pipeline already runs `selectBest` — it does at line ~410; add a regression test)

**Step 1: Acknowledgment toast.**

Already covered in Task 3 + 7 (`"Building radio…"`, `"Catching up to ${user}…"`). Verify the toast surface (Snackbar / Toast) supports a 30s duration. If `DeepLinkNavEvent.Toast` is implemented as `Toast.makeText(context, msg, Toast.LENGTH_LONG)`, swap it to a snackbar with explicit duration, OR add a `durationMs` field to `Toast` and have the receiver respect it.

Acceptance: toast appears within 500ms of deeplink, dismisses automatically.

**Step 2: Playbar loading flag.**

Add `val isPlaybarLoading: Boolean = false` to `PlaybackState`. Set it true inside `protocolPlayHandler.handlePoolBased` before the (potentially-slow) URL fetch; clear it true→false after `playbackController.startPoolBasedSpinoff` (or on failure). Render in `MiniPlayer` as a small `CircularProgressIndicator` overlaying the cover when `currentTrack == null && isPlaybarLoading`.

For Mode B too (it's fast — sub-2s — but the spec says "Mode B and Mode C both apply").

**Step 3: Friend-track confidence regression test.**

```kotlin
@Test
fun listenAlong_resolvedFriendTrack_carriesConfidenceAboveThreshold() = runTest {
    // Arrange: stub resolverManager to return a high-confidence Spotify source.
    // Act: call playFriendCurrentTrack(friend, immediate=true)
    // Assert: track passed to playbackController.playTrack has resolver != null
    //         AND the source's confidence (from selectBest) >= MIN_CONFIDENCE_THRESHOLD.
}
```

**Step 4: Commit.**

```bash
git commit -m "ux: ack toast + playbar loading + listen-along confidence test (#121 [8/N])"
```

---

## Task 9: Manual on-device verification

Run these in order on a connected device. Use the worktree-aware install path (`./gradlew installDebug` from the **branch checkout**, then force-stop the package). The branch lives in main repo (no worktree this time, per user preference) so plain `./gradlew installDebug` is fine.

```bash
adb shell am force-stop com.parachord.android.debug
./gradlew installDebug

# Mode B
adb shell am start -W -a android.intent.action.VIEW -d "parachord://play/radio?artist=Slowdive"
# Expect: "Building radio…" toast within 500ms; pool fills with Slowdive-similar
# tracks; banner reads "Radio: Slowdive" or similar.

# Mode C URL
adb shell am start -W -a android.intent.action.VIEW \
  -d "parachord://play/radio?url=https%3A%2F%2Fapi.listenbrainz.org%2F1%2Fexplore%2Flb-radio%3Fprompt%3Dtag%3Ashoegaze%26mode%3Deasy"
# Expect: same toast; pool from JSPF; logcat shows Authorization: Token <…> on
# the LB fetch (only when LB token is configured in Settings). Let pool drain
# below 3 — verify exactly one refill fetch within the rate-limit window.

# Mode C inline
adb shell am start -W -a android.intent.action.VIEW \
  -d "parachord://play/radio?tracks=W3sidGl0bGUiOiJSYWNpbmcgTGlrZSBhIFBybyIsImFydGlzdCI6Ik5hdGlvbmFsIn0sIHsidGl0bGUiOiJUcmFjayAyIiwiYXJ0aXN0IjoiQXJ0aXN0IDIifV0%3D&name=Inline%20Test"
# Expect: 2 tracks from inline JSON; no refill (no ?refill=).

# Listen-along — known friend (replace with an actual friend username from your friends list)
adb shell am start -W -a android.intent.action.VIEW \
  -d "parachord://listen-along?service=listenbrainz&user=mr_monkey"

# Listen-along — unknown user with currently-playing track
adb shell am start -W -a android.intent.action.VIEW \
  -d "parachord://listen-along?service=listenbrainz&user=rob"

# Listen-along — unknown user not currently listening
adb shell am start -W -a android.intent.action.VIEW \
  -d "parachord://listen-along?service=listenbrainz&user=someinactive_user"
# Expect: calm toast, no crash, no playback change.
```

**Step 1: Run each. Verify expected outcomes via logcat (`adb logcat -s ProtocolPlayHandler PlaybackController DeepLinkViewModel FriendsRepository`).**

**Step 2: Cross-check the issue acceptance criteria checkboxes.** Tick them in PR description.

---

## Task 10: Open the PR

**Step 1:** Push branch:

```bash
git push -u origin feature/protocol-phase-3
```

**Step 2:** Open PR with title `Protocol play handlers — Phase 3 (#121)`. Body includes:
- Bulleted summary of Mode B / Mode C / refill / listen-along / LB token plug.
- Link to issue #121.
- Manual verification checklist from Task 9 with each line checked.

**Step 3:** Tag the issue with `closes #121` in the PR body.

---

## Risk notes

- **`startSpinoff` refactor** (Task 3) touches a heavily-used method. Run the full `:app:testDebugUnitTest` suite after the refactor commit before adding Mode B logic on top.
- **Last.fm Mode B with no `?title=`** may need `getSimilarArtists` instead of `getSimilarTracks`. Verify desktop's exact call before assuming `getSimilarTracks(artist, "")` works.
- **HttpClient plugin in shared module** must compile clean for both `androidMain` and `iosMain` (KMP rule #6 — only `Default`/`Main` dispatchers in commonMain). The plugin uses `onRequest` which is suspend by design; no explicit dispatcher.
- **Refill loop dedup** depends on `TrackEntity.isrc` existing — verify before relying on it. If absent, dedup is `mbid` + `(artist|title)` only (still better than nothing).
- **Listen-along teardown asymmetry** (Task 7 Step 5) — DO NOT call the standard teardown. Use a half-teardown variant or skip teardown entirely.
- **LB token may not be set** — every test that hits the auth plugin must work in both modes (token / no token). The plugin's `tokenProvider` returning `null` already covers the "no token" path.
