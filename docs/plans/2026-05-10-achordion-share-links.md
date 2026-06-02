# Achordion share-links Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Migrate outbound share URLs (track/album/artist) from `go.parachord.com` smart-links to Achordion entity pages, with track-share submit pre-warm matching desktop's `publishSmartLink`.

**Architecture:** New shared `AchordionClient` (Ktor) wraps `/api/entity-link` + `/api/track-links/submit`. `ShareManager` calls it instead of `SmartLinksClient` for the three migrated types. Playlists keep the existing smart-link path. Bearer token threads through `AppConfig` from a new `ACHORDION_BEARER_TOKEN` BuildConfig field.

**Tech Stack:** Kotlin Multiplatform (commonMain), Ktor, kotlinx.serialization, kotlinx.coroutines (Mutex), Koin, mockk, Ktor MockEngine. Per CLAUDE.md "KMP rules": no `Dispatchers.IO` / `java.*` in commonMain; use `kotlinx.coroutines.sync.Mutex` for shared mutable state instead of `ConcurrentHashMap`/`@Volatile` for compound writes.

**Design doc:** [`docs/plans/2026-05-10-achordion-share-links-design.md`](2026-05-10-achordion-share-links-design.md) — read first for context (URL shapes, submit gate, token rationale, risk notes).

---

## Task 0: Baseline + scoping reads

**Step 1:** Confirm branch is `feature/achordion-share-links` off main.

Run: `git status && git log --oneline -1`
Expected: `On branch feature/achordion-share-links` / first line shows the design-doc commit `e13af49`.

**Step 2:** Confirm baseline tests pass.

Run: `./gradlew :app:testDebugUnitTest :shared:testDebugUnitTest`
Expected: BUILD SUCCESSFUL.

**Step 3:** Read three reference files cold so the rest of the plan refers to them by line number:

- [`shared/src/commonMain/kotlin/com/parachord/shared/api/MusicBrainzClient.kt`](shared/src/commonMain/kotlin/com/parachord/shared/api/MusicBrainzClient.kt) — shape reference for the new `AchordionClient` (same Ktor client patterns).
- [`shared/src/commonTest/kotlin/com/parachord/shared/api/MusicBrainzClientTest.kt`](shared/src/commonTest/kotlin/com/parachord/shared/api/MusicBrainzClientTest.kt) — MockEngine test pattern.
- [`parachord-desktop/plugins/achordion.axe`](../../../parachord-desktop/plugins/achordion.axe) (if you have desktop checked out at `~/Development/parachord/parachord-desktop/`) — reference implementation. The plugin's `submit`, `fetchEntityLink`, and the dedup/auth-failed kill-switch semantics are what we're porting.

No commit; this is read-only orientation.

---

## Task 1: `AchordionClient` — types + skeleton

**Files:**
- Create: `shared/src/commonMain/kotlin/com/parachord/shared/api/AchordionClient.kt`

This commit lands ONLY the types + a skeleton client (constructor + companion + empty methods returning placeholder values). Tests + behavior follow in subsequent tasks.

**Step 1:** Create the file with types + skeleton.

```kotlin
// shared/src/commonMain/kotlin/com/parachord/shared/api/AchordionClient.kt
package com.parachord.shared.api

import com.parachord.shared.platform.Log
import io.ktor.client.HttpClient
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Client for the Achordion entity-link + submit-track-links APIs.
 *
 * Two endpoints, both Bearer-token authenticated:
 *
 *  - `GET /api/entity-link?type={track|release-group|artist}&mbid={mbid}` —
 *    returns the canonical Achordion entity URL (possibly a slug-based
 *    redirect target) plus optional names. Used to mint share URLs.
 *  - `POST /api/track-links/submit` — pre-warms Achordion's match cache
 *    with the sharer's resolved per-service URLs (Spotify/Apple/SoundCloud)
 *    so the recipient lands on a fully-populated entity page on first click.
 *
 * Per-session dedup: an MBID submitted once won't re-submit until the
 * process restarts. Matches desktop's `submittedThisSession` in
 * `plugins/achordion.axe`.
 *
 * `authFailed` kill-switch: once any call returns 401, subsequent calls
 * short-circuit without hitting the network until process restart.
 *
 * KMP-clean (commonMain). Token is sourced from [com.parachord.shared.config.AppConfig.achordionBearerToken]
 * which carries an empty string when not configured — empty-token calls
 * short-circuit as [SubmitResult.AuthFailed] / null entity links.
 */
class AchordionClient(
    private val httpClient: HttpClient,
    private val bearerToken: String,
) {
    companion object {
        private const val TAG = "AchordionClient"
        private const val ENTITY_LINK_ENDPOINT = "https://achordion.xyz/api/entity-link"
        private const val SUBMIT_ENDPOINT = "https://achordion.xyz/api/track-links/submit"
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    }

    private val dedupMutex = Mutex()
    private val submittedThisSession: MutableSet<String> = mutableSetOf()
    @Volatile private var authFailed: Boolean = false

    suspend fun fetchEntityLink(
        type: EntityType,
        mbid: String,
        includeNames: Boolean = false,
    ): EntityLink? {
        TODO("Task 2")
    }

    suspend fun submitTrackLinks(payload: SubmitTrackLinksRequest): SubmitResult {
        TODO("Task 3")
    }
}

enum class EntityType(val wireValue: String) {
    Track("track"),
    ReleaseGroup("release-group"),
    Artist("artist"),
}

@Serializable
data class EntityLink(
    val url: String,
    @SerialName("embed_url") val embedUrl: String? = null,
    val name: String? = null,
    @SerialName("artist_name") val artistName: String? = null,
    @SerialName("album_name") val albumName: String? = null,
)

@Serializable
data class SubmitTrackLinksRequest(
    val mbid: String,
    val links: List<TrackLink>,
    val trackName: String? = null,
    val artistName: String? = null,
    val albumName: String? = null,
)

@Serializable
data class TrackLink(
    val url: String,
    val host: String,
    val label: String? = null,
)

sealed class SubmitResult {
    object Ok : SubmitResult()
    object NoLinks : SubmitResult()
    object AlreadySubmitted : SubmitResult()
    object NoMbid : SubmitResult()
    data class HttpError(val status: Int) : SubmitResult()
    object AuthFailed : SubmitResult()
    data class NetworkError(val message: String) : SubmitResult()
}
```

**Step 2:** Compile.

Run: `./gradlew :shared:compileDebugKotlinAndroid :shared:compileKotlinIosArm64 :shared:compileKotlinIosSimulatorArm64`
Expected: BUILD SUCCESSFUL. (TODO bodies compile; they just throw at runtime — Tasks 2+3 replace them.)

**Step 3:** Commit.

```bash
git add shared/src/commonMain/kotlin/com/parachord/shared/api/AchordionClient.kt
git commit -m "$(cat <<'EOF'
api: AchordionClient skeleton — types + empty methods (#share-links)

Lays the public surface (EntityType, EntityLink, SubmitTrackLinksRequest,
TrackLink, SubmitResult) and the constructor. Methods are TODO bodies;
behavior follows in subsequent commits.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 2: `fetchEntityLink` implementation + tests

**Files:**
- Create: `shared/src/commonTest/kotlin/com/parachord/shared/api/AchordionClientTest.kt`
- Modify: `shared/src/commonMain/kotlin/com/parachord/shared/api/AchordionClient.kt`

TDD: tests first, then implementation.

**Step 1: Write the failing tests.**

```kotlin
// shared/src/commonTest/kotlin/com/parachord/shared/api/AchordionClientTest.kt
package com.parachord.shared.api

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AchordionClientTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun buildClient(engine: MockEngine, token: String = "test-token"): AchordionClient {
        val httpClient = HttpClient(engine) {
            install(ContentNegotiation) { json(json) }
        }
        return AchordionClient(httpClient, token)
    }

    // ── fetchEntityLink ─────────────────────────────────────────────

    @Test
    fun fetchEntityLink_returnsCanonicalUrl_whenApiReturns200() = runTest {
        val engine = MockEngine { _ ->
            respond(
                content = """{"url":"https://achordion.xyz/recording/slowdive-sugar","name":"Sugar For The Pill"}""",
                status = HttpStatusCode.OK,
                headers = headersOf("Content-Type", "application/json"),
            )
        }
        val client = buildClient(engine)
        val result = client.fetchEntityLink(EntityType.Track, "abc-mbid")
        assertEquals("https://achordion.xyz/recording/slowdive-sugar", result?.url)
        assertEquals("Sugar For The Pill", result?.name)
    }

    @Test
    fun fetchEntityLink_returnsNull_on404() = runTest {
        val engine = MockEngine { _ ->
            respond(content = """{"error":"Not Found"}""", status = HttpStatusCode.NotFound)
        }
        val client = buildClient(engine)
        assertNull(client.fetchEntityLink(EntityType.Track, "abc-mbid"))
    }

    @Test
    fun fetchEntityLink_returnsNull_onMalformedResponse() = runTest {
        val engine = MockEngine { _ ->
            respond(content = """{"unexpected":"shape"}""", status = HttpStatusCode.OK,
                    headers = headersOf("Content-Type", "application/json"))
        }
        val client = buildClient(engine)
        // SerializationException on missing required `url` field → swallowed → null.
        assertNull(client.fetchEntityLink(EntityType.Track, "abc-mbid"))
    }

    @Test
    fun fetchEntityLink_sendsBearerTokenInAuthHeader() = runTest {
        var seenAuth: String? = null
        val engine = MockEngine { req ->
            seenAuth = req.headers["Authorization"]
            respond("""{"url":"https://achordion.xyz/recording/x"}""", HttpStatusCode.OK,
                    headersOf("Content-Type", "application/json"))
        }
        val client = buildClient(engine, token = "secret-bearer")
        client.fetchEntityLink(EntityType.Track, "abc-mbid")
        assertEquals("Bearer secret-bearer", seenAuth)
    }

    @Test
    fun fetchEntityLink_encodesMbidAndTypeInQueryString() = runTest {
        var seenUrl: String? = null
        val engine = MockEngine { req ->
            seenUrl = req.url.toString()
            respond("""{"url":"https://achordion.xyz/release-group/x"}""", HttpStatusCode.OK,
                    headersOf("Content-Type", "application/json"))
        }
        val client = buildClient(engine)
        client.fetchEntityLink(EntityType.ReleaseGroup, "554b417d-8885-41e6-86c7-ae935e62d571")
        val url = checkNotNull(seenUrl)
        assertEquals(true, url.startsWith("https://achordion.xyz/api/entity-link?"))
        assertEquals(true, url.contains("type=release-group"))
        assertEquals(true, url.contains("mbid=554b417d-8885-41e6-86c7-ae935e62d571"))
    }

    @Test
    fun fetchEntityLink_includeNamesAddsQueryParam() = runTest {
        var seenUrl: String? = null
        val engine = MockEngine { req ->
            seenUrl = req.url.toString()
            respond("""{"url":"https://achordion.xyz/x"}""", HttpStatusCode.OK,
                    headersOf("Content-Type", "application/json"))
        }
        val client = buildClient(engine)
        client.fetchEntityLink(EntityType.Track, "abc", includeNames = true)
        assertEquals(true, seenUrl?.contains("include=names"))
    }

    @Test
    fun fetchEntityLink_emptyToken_returnsNull_doesNotHitNetwork() = runTest {
        var calls = 0
        val engine = MockEngine { _ ->
            calls += 1
            respond("""{"url":"x"}""", HttpStatusCode.OK)
        }
        val client = buildClient(engine, token = "")
        assertNull(client.fetchEntityLink(EntityType.Track, "abc"))
        assertEquals(0, calls)
    }

    @Test
    fun fetchEntityLink_after401_subsequentCallsShortCircuit() = runTest {
        var calls = 0
        val engine = MockEngine { _ ->
            calls += 1
            respond(content = "", status = HttpStatusCode.Unauthorized)
        }
        val client = buildClient(engine)
        assertNull(client.fetchEntityLink(EntityType.Track, "first"))
        assertNull(client.fetchEntityLink(EntityType.Track, "second"))
        assertNull(client.fetchEntityLink(EntityType.Track, "third"))
        assertEquals(1, calls)   // only the first call hits the network
    }
}
```

**Step 2: Run the tests to confirm they fail.**

Run: `./gradlew :shared:testDebugUnitTest --tests "*AchordionClient*"`
Expected: FAIL — all eight tests fail with `NotImplementedError` (from the `TODO()` body).

**Step 3: Implement `fetchEntityLink`.**

Replace the `TODO("Task 2")` body in `AchordionClient.kt` with:

```kotlin
suspend fun fetchEntityLink(
    type: EntityType,
    mbid: String,
    includeNames: Boolean = false,
): EntityLink? {
    if (bearerToken.isBlank()) return null
    if (authFailed) return null
    return try {
        val response = httpClient.get(ENTITY_LINK_ENDPOINT) {
            parameter("type", type.wireValue)
            parameter("mbid", mbid)
            if (includeNames) parameter("include", "names")
            header(HttpHeaders.Authorization, "Bearer $bearerToken")
            accept(ContentType.Application.Json)
        }
        when (response.status) {
            HttpStatusCode.Unauthorized -> {
                authFailed = true
                Log.w(TAG, "entity-link returned 401 — suppressing further calls this session")
                null
            }
            HttpStatusCode.OK -> {
                val body = response.bodyAsText()
                json.decodeFromString<EntityLink>(body)
            }
            else -> {
                Log.w(TAG, "entity-link returned HTTP ${response.status.value} for $type mbid=$mbid")
                null
            }
        }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Throwable) {
        Log.w(TAG, "entity-link failed for $type mbid=$mbid: ${e.message}")
        null
    }
}
```

Add imports:

```kotlin
import io.ktor.client.request.accept
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
```

**Step 4: Run the tests, confirm green.**

Run: `./gradlew :shared:testDebugUnitTest --tests "*AchordionClient*"`
Expected: PASS — 8/8.

**Step 5: Commit.**

```bash
git add shared/src/commonMain/kotlin/com/parachord/shared/api/AchordionClient.kt \
        shared/src/commonTest/kotlin/com/parachord/shared/api/AchordionClientTest.kt
git commit -m "$(cat <<'EOF'
api: AchordionClient.fetchEntityLink + 8 MockEngine tests (#share-links)

Resolves a Bearer-authenticated GET against /api/entity-link to the
canonical Achordion entity URL. Empty token short-circuits without
hitting the network (covers debug builds without local.properties).
First 401 trips an authFailed kill-switch so subsequent calls
short-circuit too — matches desktop plugin's per-session model.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 3: `submitTrackLinks` implementation + tests

**Files:**
- Modify: `shared/src/commonTest/kotlin/com/parachord/shared/api/AchordionClientTest.kt`
- Modify: `shared/src/commonMain/kotlin/com/parachord/shared/api/AchordionClient.kt`

**Step 1: Add tests below the existing fetchEntityLink block.**

```kotlin
// ── submitTrackLinks ────────────────────────────────────────────

private val sampleRequest = SubmitTrackLinksRequest(
    mbid = "abc-mbid-123",
    links = listOf(
        TrackLink(url = "https://open.spotify.com/track/X", host = "spotify.com", label = "Spotify"),
    ),
    trackName = "Song",
    artistName = "Artist",
    albumName = "Album",
)

@Test
fun submitTrackLinks_returnsOk_whenApiReturns200() = runTest {
    val engine = MockEngine { _ -> respond("", HttpStatusCode.OK) }
    val client = buildClient(engine)
    assertEquals(SubmitResult.Ok, client.submitTrackLinks(sampleRequest))
}

@Test
fun submitTrackLinks_returnsNoLinks_whenLinksListIsEmpty() = runTest {
    val engine = MockEngine { _ -> error("should not be called") }
    val client = buildClient(engine)
    val result = client.submitTrackLinks(sampleRequest.copy(links = emptyList()))
    assertEquals(SubmitResult.NoLinks, result)
}

@Test
fun submitTrackLinks_returnsNoMbid_whenMbidIsBlank() = runTest {
    val engine = MockEngine { _ -> error("should not be called") }
    val client = buildClient(engine)
    val result = client.submitTrackLinks(sampleRequest.copy(mbid = ""))
    assertEquals(SubmitResult.NoMbid, result)
}

@Test
fun submitTrackLinks_returnsAlreadySubmitted_onSecondCallSameMbid() = runTest {
    val engine = MockEngine { _ -> respond("", HttpStatusCode.OK) }
    val client = buildClient(engine)
    assertEquals(SubmitResult.Ok, client.submitTrackLinks(sampleRequest))
    assertEquals(SubmitResult.AlreadySubmitted, client.submitTrackLinks(sampleRequest))
}

@Test
fun submitTrackLinks_dedupKeyIsLowercaseMbid() = runTest {
    val engine = MockEngine { _ -> respond("", HttpStatusCode.OK) }
    val client = buildClient(engine)
    client.submitTrackLinks(sampleRequest.copy(mbid = "ABC-MBID-123"))
    val second = client.submitTrackLinks(sampleRequest.copy(mbid = "abc-mbid-123"))
    assertEquals(SubmitResult.AlreadySubmitted, second)
}

@Test
fun submitTrackLinks_returnsAuthFailed_on401() = runTest {
    val engine = MockEngine { _ -> respond("", HttpStatusCode.Unauthorized) }
    val client = buildClient(engine)
    assertEquals(SubmitResult.AuthFailed, client.submitTrackLinks(sampleRequest))
}

@Test
fun submitTrackLinks_authFailedShortCircuitsSubsequentCalls() = runTest {
    var calls = 0
    val engine = MockEngine { _ ->
        calls += 1
        respond("", HttpStatusCode.Unauthorized)
    }
    val client = buildClient(engine)
    client.submitTrackLinks(sampleRequest)
    val again = client.submitTrackLinks(sampleRequest.copy(mbid = "different-mbid"))
    assertEquals(SubmitResult.AuthFailed, again)
    assertEquals(1, calls)   // only the first call hits the network
}

@Test
fun submitTrackLinks_returnsHttpError_onOther4xx() = runTest {
    val engine = MockEngine { _ -> respond("", HttpStatusCode.UnprocessableEntity) }
    val client = buildClient(engine)
    val result = client.submitTrackLinks(sampleRequest)
    assertEquals(SubmitResult.HttpError(422), result)
}

@Test
fun submitTrackLinks_sendsBearerTokenAndJsonBody() = runTest {
    var seenAuth: String? = null
    var seenContentType: String? = null
    val engine = MockEngine { req ->
        seenAuth = req.headers["Authorization"]
        seenContentType = req.headers["Content-Type"]
        respond("", HttpStatusCode.OK)
    }
    val client = buildClient(engine, token = "tok-xyz")
    client.submitTrackLinks(sampleRequest)
    assertEquals("Bearer tok-xyz", seenAuth)
    assertEquals(true, seenContentType?.startsWith("application/json"))
}

@Test
fun submitTrackLinks_emptyToken_returnsAuthFailed_doesNotHitNetwork() = runTest {
    var calls = 0
    val engine = MockEngine { _ -> calls += 1; respond("", HttpStatusCode.OK) }
    val client = buildClient(engine, token = "")
    assertEquals(SubmitResult.AuthFailed, client.submitTrackLinks(sampleRequest))
    assertEquals(0, calls)
}
```

**Step 2: Run, confirm failure.**

Run: `./gradlew :shared:testDebugUnitTest --tests "*AchordionClient*"`
Expected: 8 from Task 2 pass, 10 new fail with `NotImplementedError`.

**Step 3: Implement `submitTrackLinks`.**

Replace `TODO("Task 3")`:

```kotlin
suspend fun submitTrackLinks(payload: SubmitTrackLinksRequest): SubmitResult {
    if (bearerToken.isBlank()) return SubmitResult.AuthFailed
    if (authFailed) return SubmitResult.AuthFailed
    if (payload.mbid.isBlank()) return SubmitResult.NoMbid
    if (payload.links.isEmpty()) return SubmitResult.NoLinks

    val mbidKey = payload.mbid.lowercase()
    val alreadySubmitted = dedupMutex.withLock {
        if (mbidKey in submittedThisSession) true
        else { submittedThisSession.add(mbidKey); false }
    }
    if (alreadySubmitted) return SubmitResult.AlreadySubmitted

    return try {
        val response = httpClient.post(SUBMIT_ENDPOINT) {
            header(HttpHeaders.Authorization, "Bearer $bearerToken")
            contentType(ContentType.Application.Json)
            setBody(payload)
        }
        when (response.status) {
            HttpStatusCode.OK, HttpStatusCode.Created, HttpStatusCode.Accepted -> SubmitResult.Ok
            HttpStatusCode.Unauthorized -> {
                authFailed = true
                Log.w(TAG, "submit returned 401 — suppressing further calls this session")
                SubmitResult.AuthFailed
            }
            else -> {
                // Roll back the dedup entry so a transient 5xx doesn't
                // permanently mark this mbid as "submitted" for the
                // rest of the session.
                dedupMutex.withLock { submittedThisSession.remove(mbidKey) }
                Log.w(TAG, "submit returned HTTP ${response.status.value} for mbid=${payload.mbid}")
                SubmitResult.HttpError(response.status.value)
            }
        }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Throwable) {
        dedupMutex.withLock { submittedThisSession.remove(mbidKey) }
        Log.w(TAG, "submit failed for mbid=${payload.mbid}: ${e.message}")
        SubmitResult.NetworkError(e.message ?: "unknown")
    }
}
```

Add imports:

```kotlin
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation  // already may be present
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.contentType
import kotlinx.coroutines.sync.withLock
```

**Step 4: Run, confirm green.**

Run: `./gradlew :shared:testDebugUnitTest --tests "*AchordionClient*"`
Expected: 18/18 PASS.

**Step 5: Commit.**

```bash
git add shared/src/commonMain/kotlin/com/parachord/shared/api/AchordionClient.kt \
        shared/src/commonTest/kotlin/com/parachord/shared/api/AchordionClientTest.kt
git commit -m "$(cat <<'EOF'
api: AchordionClient.submitTrackLinks + 10 MockEngine tests (#share-links)

POSTs the sharer's resolved per-service URLs to Achordion's match
cache. Gates in order: empty token, authFailed kill-switch, blank MBID,
empty links, per-session dedup (lowercase MBID key under a coroutine
Mutex — KMP-safe). HTTP errors roll back the dedup entry so a transient
5xx doesn't permanently mark the MBID as submitted.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 4: `AppConfig.achordionBearerToken` + BuildConfig

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/parachord/shared/config/AppConfig.kt`
- Modify: `app/build.gradle.kts:33-39ish` (the `buildConfigField` block)
- Modify: `app/src/main/java/com/parachord/android/di/AndroidModule.kt` (search for the `AppConfig` factory)

**Step 1: Add field to AppConfig.**

In `AppConfig.kt` data class, add (alphabetically placed; the file already has fields like `lastFmApiKey`, `userAgent`, `isDebug`):

```kotlin
data class AppConfig(
    val lastFmApiKey: String = "",
    // ... existing fields ...
    val achordionBearerToken: String = "",
    val userAgent: String = "",
    val isDebug: Boolean = false,
)
```

Place `achordionBearerToken` between the existing API-key fields and `userAgent` — match the existing alphabetical or grouping convention by reading the file first.

**Step 2: Add BuildConfig field.**

In `app/build.gradle.kts` near line 33-39 where other `buildConfigField` calls live, add:

```kotlin
buildConfigField("String", "ACHORDION_BEARER_TOKEN", "\"${localProp("ACHORDION_BEARER_TOKEN")}\"")
```

Place it alphabetically among the other tokens.

**Step 3: Wire it into the AppConfig Koin factory.**

Search: `grep -n "AppConfig(" app/src/main/java/com/parachord/android/di/AndroidModule.kt`

Find the `single<AppConfig> { ... }` (or `factory<AppConfig> { ... }`) block. It should look like:

```kotlin
single {
    AppConfig(
        lastFmApiKey = BuildConfig.LASTFM_API_KEY,
        userAgent = "Parachord/${BuildConfig.VERSION_NAME} (Android; https://parachord.app)",
        isDebug = BuildConfig.DEBUG,
        // ... etc
    )
}
```

Add the new field:

```kotlin
achordionBearerToken = BuildConfig.ACHORDION_BEARER_TOKEN,
```

**Step 4: Compile.**

Run: `./gradlew :app:compileDebugKotlin :shared:compileKotlinIosArm64`
Expected: BUILD SUCCESSFUL on both targets.

**Step 5: Quick test to confirm AppConfig now carries the token.**

Add to `shared/src/commonTest/kotlin/com/parachord/shared/config/AppConfigTest.kt` (create if absent — single test):

```kotlin
package com.parachord.shared.config

import kotlin.test.Test
import kotlin.test.assertEquals

class AppConfigTest {
    @Test
    fun achordionBearerToken_defaultIsEmpty() {
        assertEquals("", AppConfig().achordionBearerToken)
    }
}
```

Run: `./gradlew :shared:testDebugUnitTest --tests "*AppConfig*"`
Expected: PASS.

**Step 6: Commit.**

```bash
git add shared/src/commonMain/kotlin/com/parachord/shared/config/AppConfig.kt \
        app/build.gradle.kts \
        app/src/main/java/com/parachord/android/di/AndroidModule.kt \
        shared/src/commonTest/kotlin/com/parachord/shared/config/AppConfigTest.kt
git commit -m "$(cat <<'EOF'
config: AppConfig.achordionBearerToken + BuildConfig wire (#share-links)

Threads the Bearer token through AppConfig from a new
ACHORDION_BEARER_TOKEN BuildConfig field. Sourced from local.properties
or the CI secret of the same name. Empty default means devs without the
token in local.properties get a clean fall-through to lookup URLs
without API errors — AchordionClient short-circuits on empty tokens.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 5: CI secret + workflow wire

**Files:**
- Modify: `.github/workflows/build.yml` (the `Create local.properties` step around line 42-50)

**Step 1: Add the secret to the CI `local.properties` write block.**

Find the `cat <<EOF > local.properties` heredoc and add a line:

```yaml
ACHORDION_BEARER_TOKEN=${{ secrets.ACHORDION_BEARER_TOKEN }}
```

Place alphabetically among the other secrets.

**Step 2: Add the secret to the repo on GitHub.**

This is a manual / out-of-band step the controller should flag to the user:

> Add a new repo secret named `ACHORDION_BEARER_TOKEN` with value `parachord_rgOgj2trN2KeIovar9DYA-yOCRkxgO6KlSyAo_jHtgg` (the published value from desktop's `parachord-desktop/plugins/achordion.axe`) via Settings → Secrets and variables → Actions → New repository secret. Without this secret, release builds will compile and ship but Achordion submits will silently no-op until the token is configured.

The implementer should NOT try to set this themselves — flag it in the report.

**Step 3: Commit the workflow change.**

```bash
git add .github/workflows/build.yml
git commit -m "$(cat <<'EOF'
ci: wire ACHORDION_BEARER_TOKEN into local.properties (#share-links)

Release builds need the Achordion Bearer token surfaced into
local.properties so the BuildConfig field carries it. Empty value
when the repo secret isn't set is fine — AchordionClient short-circuits
gracefully.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 6: Koin binding for `AchordionClient`

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/parachord/shared/di/SharedModule.kt` — add binding adjacent to `SmartLinksClient` (line 91)

**Step 1: Add the Koin binding.**

After line 91 (`single { SmartLinksClient(get()) }`), add:

```kotlin
single {
    AchordionClient(
        httpClient = get(),
        bearerToken = get<AppConfig>().achordionBearerToken,
    )
}
```

Verify the imports are right (`AchordionClient`, `AppConfig`). Add if missing.

**Step 2: Compile.**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

**Step 3: Commit.**

```bash
git add shared/src/commonMain/kotlin/com/parachord/shared/di/SharedModule.kt
git commit -m "$(cat <<'EOF'
di: wire AchordionClient as a Koin singleton (#share-links)

Sources the Bearer token from AppConfig.achordionBearerToken at
construction time. Ready for ShareManager to inject in the next commit.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 7: `ShareManager` — rewrite `shareTrack`

**Files:**
- Modify: `app/src/main/java/com/parachord/android/share/ShareManager.kt`
- Create: `app/src/test/java/com/parachord/android/share/ShareManagerTest.kt`

**Step 1: Add AchordionClient to ShareManager constructor.**

```kotlin
class ShareManager constructor(
    private val smartLinksClient: SmartLinksClient,
    private val achordionClient: AchordionClient,    // NEW
    private val playlistDao: PlaylistDao,
    private val playlistTrackDao: PlaylistTrackDao,
) { ... }
```

Update the Koin binding in `AndroidModule.kt` to pass `achordionClient = get()`.

**Step 2: Write failing tests for `shareTrack`.**

```kotlin
// app/src/test/java/com/parachord/android/share/ShareManagerTest.kt
package com.parachord.android.share

import com.parachord.android.data.db.dao.PlaylistDao
import com.parachord.android.data.db.dao.PlaylistTrackDao
import com.parachord.android.data.db.entity.TrackEntity
import com.parachord.shared.api.AchordionClient
import com.parachord.shared.api.EntityLink
import com.parachord.shared.api.EntityType
import com.parachord.shared.api.SmartLinksClient
import com.parachord.shared.api.SubmitResult
import com.parachord.shared.api.SubmitTrackLinksRequest
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ShareManagerTest {

    private fun buildManager(
        achordion: AchordionClient = mockk(relaxed = true),
        smartLinks: SmartLinksClient = mockk(relaxed = true),
    ): ShareManager = ShareManager(
        smartLinksClient = smartLinks,
        achordionClient = achordion,
        playlistDao = mockk(relaxed = true),
        playlistTrackDao = mockk(relaxed = true),
    )

    private fun sampleTrack(
        recordingMbid: String? = "550e8400-e29b-41d4-a716-446655440000",
        spotifyId: String? = "SP123",
        appleMusicId: String? = "AM456",
        soundcloudId: String? = "user/track",
    ) = TrackEntity(
        id = "t1",
        title = "Sugar For The Pill",
        artist = "Slowdive",
        album = "Slowdive",
        recordingMbid = recordingMbid,
        spotifyId = spotifyId,
        appleMusicId = appleMusicId,
        soundcloudId = soundcloudId,
    )

    @Test
    fun shareTrack_withMbid_callsBothEntityLinkAndSubmit() = runTest {
        val achordion = mockk<AchordionClient>()
        coEvery { achordion.fetchEntityLink(EntityType.Track, "550e8400-e29b-41d4-a716-446655440000", any()) } returns
            EntityLink(url = "https://achordion.xyz/recording/slowdive-sugar")
        coEvery { achordion.submitTrackLinks(any()) } returns SubmitResult.Ok

        val mgr = buildManager(achordion = achordion)
        val result = mgr.shareTrack(sampleTrack())

        assertEquals("https://achordion.xyz/recording/slowdive-sugar", result.url)
        assertTrue(result.isSmartLink)
        coVerify(exactly = 1) { achordion.fetchEntityLink(EntityType.Track, any(), any()) }
        coVerify(exactly = 1) { achordion.submitTrackLinks(any()) }
    }

    @Test
    fun shareTrack_withoutMbid_callsNeitherApi_returnsLookupFallback() = runTest {
        val achordion = mockk<AchordionClient>(relaxed = true)
        val mgr = buildManager(achordion = achordion)
        val result = mgr.shareTrack(sampleTrack(recordingMbid = null))

        assertEquals(
            "https://achordion.xyz/recording/lookup?artist=Slowdive&title=Sugar%20For%20The%20Pill",
            result.url,
        )
        assertFalse(result.isSmartLink)
        coVerify(exactly = 0) { achordion.fetchEntityLink(any(), any(), any()) }
        coVerify(exactly = 0) { achordion.submitTrackLinks(any()) }
    }

    @Test
    fun shareTrack_entityLinkReturnsNull_returnsLookupFallback_butStillSubmits() = runTest {
        val achordion = mockk<AchordionClient>()
        coEvery { achordion.fetchEntityLink(any(), any(), any()) } returns null
        coEvery { achordion.submitTrackLinks(any()) } returns SubmitResult.Ok

        val mgr = buildManager(achordion = achordion)
        val result = mgr.shareTrack(sampleTrack())

        assertTrue(result.url.startsWith("https://achordion.xyz/recording/lookup?"))
        assertFalse(result.isSmartLink)
        coVerify(exactly = 1) { achordion.submitTrackLinks(any()) }
    }

    @Test
    fun shareTrack_submitPayloadIncludesAllAvailableSourceUrls() = runTest {
        val achordion = mockk<AchordionClient>()
        coEvery { achordion.fetchEntityLink(any(), any(), any()) } returns null
        val payloadSlot = slot<SubmitTrackLinksRequest>()
        coEvery { achordion.submitTrackLinks(capture(payloadSlot)) } returns SubmitResult.Ok

        val mgr = buildManager(achordion = achordion)
        mgr.shareTrack(sampleTrack())

        val payload = payloadSlot.captured
        assertEquals("550e8400-e29b-41d4-a716-446655440000", payload.mbid)
        assertEquals("Sugar For The Pill", payload.trackName)
        assertEquals("Slowdive", payload.artistName)
        assertEquals("Slowdive", payload.albumName)
        val hosts = payload.links.map { it.host }.toSet()
        assertEquals(setOf("spotify.com", "music.apple.com", "soundcloud.com"), hosts)
    }

    @Test
    fun shareTrack_skipsSourceUrlsWithBlankIds() = runTest {
        val achordion = mockk<AchordionClient>()
        coEvery { achordion.fetchEntityLink(any(), any(), any()) } returns null
        val payloadSlot = slot<SubmitTrackLinksRequest>()
        coEvery { achordion.submitTrackLinks(capture(payloadSlot)) } returns SubmitResult.Ok

        val mgr = buildManager(achordion = achordion)
        mgr.shareTrack(sampleTrack(spotifyId = "SP", appleMusicId = "", soundcloudId = null))

        val hosts = payloadSlot.captured.links.map { it.host }
        assertEquals(listOf("spotify.com"), hosts)
    }

    @Test
    fun shareTrack_noSourceIds_doesNotCallSubmit() = runTest {
        val achordion = mockk<AchordionClient>()
        coEvery { achordion.fetchEntityLink(any(), any(), any()) } returns null
        coEvery { achordion.submitTrackLinks(any()) } returns SubmitResult.NoLinks

        val mgr = buildManager(achordion = achordion)
        mgr.shareTrack(sampleTrack(spotifyId = null, appleMusicId = null, soundcloudId = null))

        // ShareManager filters source-list at build time and skips submit when empty.
        coVerify(exactly = 0) { achordion.submitTrackLinks(any()) }
    }
}
```

**Step 3: Run, confirm failure.**

Run: `./gradlew :app:testDebugUnitTest --tests "*ShareManagerTest*"`
Expected: FAIL — old signature doesn't have `achordionClient`, and `shareTrack` body still calls `smartLinksClient`.

**Step 4: Replace `shareTrack` body and helpers.**

Open `app/src/main/java/com/parachord/android/share/ShareManager.kt` and replace `shareTrack`:

```kotlin
suspend fun shareTrack(track: TrackEntity): ShareResult = coroutineScope {
    val subject = "${track.artist} – ${track.title}"
    val mbid = track.recordingMbid

    // Fire entity-link + submit in parallel. Both AWAITED so the recipient's
    // first click sees a fully-warmed Achordion page. Matches desktop's
    // publishSmartLink behavior (parachord-desktop/app.js:13380+).
    val entityLinkJob = async { tryFetchEntityLink(EntityType.Track, mbid) }
    val submitJob = async { trySubmitForTrack(track, mbid) }
    val entityUrl = entityLinkJob.await()
    submitJob.await()

    val url = entityUrl ?: trackLookupUrl(track.artist, track.title)
    ShareResult(url, subject, isSmartLink = entityUrl != null)
}

private suspend fun tryFetchEntityLink(type: EntityType, mbid: String?): String? {
    if (mbid.isNullOrBlank()) return null
    return try {
        withTimeout(SMART_LINK_TIMEOUT_MS) {
            achordionClient.fetchEntityLink(type, mbid)?.url
        }
    } catch (e: TimeoutCancellationException) {
        Log.w(TAG, "fetchEntityLink timed out for $type mbid=$mbid")
        null
    } catch (e: Exception) {
        Log.w(TAG, "fetchEntityLink failed for $type mbid=$mbid: ${e.message}")
        null
    }
}

private suspend fun trySubmitForTrack(track: TrackEntity, mbid: String?) {
    if (mbid.isNullOrBlank()) return
    val links = buildList {
        track.spotifyId?.takeIf { it.isNotBlank() }?.let {
            add(TrackLink(url = "https://open.spotify.com/track/$it", host = "spotify.com", label = "Spotify"))
        }
        track.appleMusicId?.takeIf { it.isNotBlank() }?.let {
            add(TrackLink(url = "https://music.apple.com/song/$it", host = "music.apple.com", label = "Apple Music"))
        }
        track.soundcloudId?.takeIf { it.isNotBlank() }?.let {
            add(TrackLink(url = "https://soundcloud.com/$it", host = "soundcloud.com", label = "SoundCloud"))
        }
    }
    if (links.isEmpty()) return
    try {
        withTimeout(SMART_LINK_TIMEOUT_MS) {
            achordionClient.submitTrackLinks(
                SubmitTrackLinksRequest(
                    mbid = mbid,
                    links = links,
                    trackName = track.title,
                    artistName = track.artist,
                    albumName = track.album,
                )
            )
        }
    } catch (e: TimeoutCancellationException) {
        Log.w(TAG, "submitTrackLinks timed out for mbid=$mbid")
    } catch (e: Exception) {
        Log.w(TAG, "submitTrackLinks failed for mbid=$mbid: ${e.message}")
    }
}

private fun trackLookupUrl(artist: String, title: String): String =
    "https://achordion.xyz/recording/lookup?artist=${enc(artist)}&title=${enc(title)}"
```

Add imports:

```kotlin
import com.parachord.shared.api.AchordionClient
import com.parachord.shared.api.EntityType
import com.parachord.shared.api.SubmitTrackLinksRequest
import com.parachord.shared.api.TrackLink
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
```

Don't remove `SmartLinksClient` / `SmartLinkCreateRequest` imports — playlist still uses them.

**Step 5: Run, confirm green.**

Run: `./gradlew :app:testDebugUnitTest --tests "*ShareManagerTest*"`
Expected: 6/6 new tests PASS.

**Step 6: Commit.**

```bash
git add app/src/main/java/com/parachord/android/share/ShareManager.kt \
        app/src/main/java/com/parachord/android/di/AndroidModule.kt \
        app/src/test/java/com/parachord/android/share/ShareManagerTest.kt
git commit -m "$(cat <<'EOF'
share: rewrite shareTrack to use AchordionClient + 6 mockk tests (#share-links)

Drops the SmartLinksClient call for tracks. Now calls entity-link +
submit in parallel via coroutineScope+async, awaits both before
returning. Lookup URL fallback when MBID is missing. Submit gates
internally on links.isNotEmpty so tracks without resolved sources
skip the POST cleanly.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 8: `ShareManager` — rewrite `shareAlbum`

**Files:**
- Modify: `app/src/main/java/com/parachord/android/share/ShareManager.kt` (shareAlbum body, around line 72-103)
- Modify: `app/src/test/java/com/parachord/android/share/ShareManagerTest.kt` (add tests)
- Modify: `app/src/main/java/com/parachord/android/share/ShareSheet.kt` (caller at line 71 + `rememberShareAlbum` signature at line 58)

**Step 1: Write the failing tests.**

Add to ShareManagerTest:

```kotlin
@Test
fun shareAlbum_withMbid_callsEntityLinkOnly_noSubmit() = runTest {
    val achordion = mockk<AchordionClient>()
    coEvery { achordion.fetchEntityLink(EntityType.ReleaseGroup, "rg-mbid-1", any()) } returns
        EntityLink(url = "https://achordion.xyz/release-group/death-cab-tower")

    val mgr = buildManager(achordion = achordion)
    val result = mgr.shareAlbum(
        title = "I Built You a Tower",
        artist = "Death Cab for Cutie",
        artworkUrl = null,
        releaseGroupMbid = "rg-mbid-1",
    )

    assertEquals("https://achordion.xyz/release-group/death-cab-tower", result.url)
    assertTrue(result.isSmartLink)
    coVerify(exactly = 1) { achordion.fetchEntityLink(EntityType.ReleaseGroup, "rg-mbid-1", any()) }
    coVerify(exactly = 0) { achordion.submitTrackLinks(any()) }
}

@Test
fun shareAlbum_noMbid_returnsLookupFallback() = runTest {
    val achordion = mockk<AchordionClient>(relaxed = true)
    val mgr = buildManager(achordion = achordion)
    val result = mgr.shareAlbum(
        title = "I Built You a Tower",
        artist = "Death Cab for Cutie",
        artworkUrl = null,
        releaseGroupMbid = null,
    )
    assertEquals(
        "https://achordion.xyz/release-group/lookup?artist=Death%20Cab%20for%20Cutie&title=I%20Built%20You%20a%20Tower",
        result.url,
    )
    assertFalse(result.isSmartLink)
    coVerify(exactly = 0) { achordion.fetchEntityLink(any(), any(), any()) }
}

@Test
fun shareAlbum_entityLinkFails_returnsLookupFallback() = runTest {
    val achordion = mockk<AchordionClient>()
    coEvery { achordion.fetchEntityLink(any(), any(), any()) } returns null
    val mgr = buildManager(achordion = achordion)
    val result = mgr.shareAlbum("Tower", "Death Cab", null, "rg-mbid-1")
    assertTrue(result.url.contains("/release-group/lookup?"))
    assertFalse(result.isSmartLink)
}
```

**Step 2: Run, confirm failure.**

Run: `./gradlew :app:testDebugUnitTest --tests "*ShareManagerTest.shareAlbum*"`
Expected: compile error — `shareAlbum` doesn't have the new signature.

**Step 3: Update `shareAlbum` body.**

Replace the existing `shareAlbum` method body:

```kotlin
suspend fun shareAlbum(
    title: String,
    artist: String,
    artworkUrl: String?,         // kept for caller compat; unused after migration
    releaseGroupMbid: String?,
): ShareResult {
    val subject = "$artist – $title"
    val entityUrl = tryFetchEntityLink(EntityType.ReleaseGroup, releaseGroupMbid)
    val url = entityUrl ?: releaseGroupLookupUrl(artist, title)
    return ShareResult(url, subject, isSmartLink = entityUrl != null)
}

private fun releaseGroupLookupUrl(artist: String, title: String): String =
    "https://achordion.xyz/release-group/lookup?artist=${enc(artist)}&title=${enc(title)}"
```

Note: the old `shareAlbum` took `(title, artist, artworkUrl, tracks, spotifyAlbumId)`. We're dropping `tracks` + `spotifyAlbumId` from the API. Callers must be updated.

**Step 4: Update caller sites in `ShareSheet.kt`.**

In `ShareSheet.kt`, the `rememberShareAlbum` helper (line 58+) and `rememberShareAlbumLite` (line 88+) currently produce a lambda that takes `(title, artist, artworkUrl, tracks, spotifyAlbumId)`. Two paths:

1. **Reduce the lambda signature** to `(title, artist, artworkUrl, releaseGroupMbid)`. Update all call sites that pass `tracks` / `spotifyAlbumId` to drop those args and pass `releaseGroupMbid` (null if not known yet).

2. **Keep the existing lambda signature, drop `tracks` / `spotifyAlbumId` from the lambda body's call to `shareManager.shareAlbum`, add `releaseGroupMbid = null` always.** Most callers don't have an MBID anyway, so this just removes the dead args without forcing callers to thread MBID through yet.

Use **#2 for now** — minimal churn. File a follow-up to plumb release-group MBID from `AlbumEntity` once the entity carries one. Update `ShareSheet.kt:71`:

```kotlin
val result = shareManager.shareAlbum(title, artist, artworkUrl, releaseGroupMbid = null)
```

(Drop the `tracks` and `spotifyAlbumId` args.)

And line 92 (the Lite variant):

```kotlin
shareAlbum(title, artist, artworkUrl)
```

…assuming the Lite signature already doesn't accept tracks/spotifyAlbumId. Verify by reading the existing helper body.

**Step 5: Run, confirm green.**

Run: `./gradlew :app:testDebugUnitTest --tests "*ShareManagerTest*"`
Expected: all tests PASS (Task 7's 6 + Task 8's 3 = 9).

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

**Step 6: Commit.**

```bash
git add app/src/main/java/com/parachord/android/share/ShareManager.kt \
        app/src/main/java/com/parachord/android/share/ShareSheet.kt \
        app/src/test/java/com/parachord/android/share/ShareManagerTest.kt
git commit -m "$(cat <<'EOF'
share: rewrite shareAlbum to use AchordionClient + 3 tests (#share-links)

Drops the smart-link payload (tracks list, spotifyAlbumId) — they're
no longer needed since Achordion serves the album entity page from
just the release-group MBID. Callers pass releaseGroupMbid (currently
always null since AlbumEntity doesn't carry one yet; future follow-up
to plumb it).

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 9: `ShareManager` — rewrite `shareArtist`

**Files:**
- Modify: `app/src/main/java/com/parachord/android/share/ShareManager.kt:149-153`
- Modify: `app/src/test/java/com/parachord/android/share/ShareManagerTest.kt`
- Modify: `app/src/main/java/com/parachord/android/share/ShareSheet.kt:134-141` (`rememberShareArtist`)

**Step 1: Write tests.**

```kotlin
@Test
fun shareArtist_withMbid_callsEntityLinkOnly_noSubmit() = runTest {
    val achordion = mockk<AchordionClient>()
    coEvery { achordion.fetchEntityLink(EntityType.Artist, "artist-mbid-1", any()) } returns
        EntityLink(url = "https://achordion.xyz/artist/slowdive")
    val mgr = buildManager(achordion = achordion)
    val result = mgr.shareArtist("Slowdive", artistMbid = "artist-mbid-1")
    assertEquals("https://achordion.xyz/artist/slowdive", result.url)
    assertTrue(result.isSmartLink)
    coVerify(exactly = 0) { achordion.submitTrackLinks(any()) }
}

@Test
fun shareArtist_noMbid_returnsLookupFallback() = runTest {
    val achordion = mockk<AchordionClient>(relaxed = true)
    val mgr = buildManager(achordion = achordion)
    val result = mgr.shareArtist("Slowdive", artistMbid = null)
    assertEquals("https://achordion.xyz/artist/lookup?name=Slowdive", result.url)
    assertFalse(result.isSmartLink)
}
```

**Step 2: Run, confirm failure.**

Run: `./gradlew :app:testDebugUnitTest --tests "*ShareManagerTest.shareArtist*"`
Expected: compile or assertion error.

**Step 3: Update `shareArtist`.**

```kotlin
suspend fun shareArtist(name: String, artistMbid: String? = null): ShareResult {
    val entityUrl = tryFetchEntityLink(EntityType.Artist, artistMbid)
    val url = entityUrl ?: artistLookupUrl(name)
    return ShareResult(url, name, isSmartLink = entityUrl != null)
}

private fun artistLookupUrl(name: String): String =
    "https://achordion.xyz/artist/lookup?name=${enc(name)}"
```

Note: changed from `fun` (sync) to `suspend fun`. Caller `rememberShareArtist` already runs in `coroutineScope`, so the change is safe. Update its caller too.

**Step 4: Update `ShareSheet.rememberShareArtist`.**

In `ShareSheet.kt` around line 141, current call is `shareManager.shareArtist(name, imageUrl)`. The `imageUrl` parameter was unused in the old body anyway. Change to:

```kotlin
val result = shareManager.shareArtist(name, artistMbid = null)
```

(Future improvement: when Achordion has a public artist-MBID lookup endpoint, or when `Artist` carries an MBID via metadata service, thread it here. For now, lookup URL handles the no-MBID case.)

The `rememberShareArtist` lambda signature can stay `(name: String, imageUrl: String?) -> Unit` if other call sites pass `imageUrl`; the helper just discards it. Or simplify to `(name: String) -> Unit` if call sites are simple to update. Use judgment based on caller count.

**Step 5: Run, confirm green.**

Run: `./gradlew :app:testDebugUnitTest --tests "*ShareManagerTest*"`
Expected: 11/11 PASS.

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

**Step 6: Commit.**

```bash
git add app/src/main/java/com/parachord/android/share/ShareManager.kt \
        app/src/main/java/com/parachord/android/share/ShareSheet.kt \
        app/src/test/java/com/parachord/android/share/ShareManagerTest.kt
git commit -m "$(cat <<'EOF'
share: rewrite shareArtist to use AchordionClient + 2 tests (#share-links)

Drops the unused imageUrl parameter. Now suspend (was sync) since the
entity-link call is suspending. Callers in ShareSheet already run in a
coroutineScope so the change is safe.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 10: Playlist regression guard test

**Files:**
- Modify: `app/src/test/java/com/parachord/android/share/ShareManagerTest.kt`

**Step 1: Add the test.**

```kotlin
@Test
fun sharePlaylist_unchanged_stillUsesSmartLinksClient_notAchordion() = runTest {
    val achordion = mockk<AchordionClient>(relaxed = true)
    val smartLinks = mockk<SmartLinksClient>(relaxed = true)
    coEvery { smartLinks.create(any()) } returns com.parachord.shared.api.SmartLinkCreateResponse(
        url = "https://go.parachord.com/abc123",
        id = "abc123",
    )

    val playlistDao = mockk<PlaylistDao>()
    val playlistTrackDao = mockk<PlaylistTrackDao>()
    coEvery { playlistDao.getById("p1") } returns com.parachord.android.data.db.entity.PlaylistEntity(
        id = "p1", name = "My Playlist", ownerName = "me",
    )
    coEvery { playlistTrackDao.getByPlaylistIdSync("p1") } returns emptyList()

    val mgr = ShareManager(
        smartLinksClient = smartLinks,
        achordionClient = achordion,
        playlistDao = playlistDao,
        playlistTrackDao = playlistTrackDao,
    )

    val result = mgr.sharePlaylist("p1")
    assertEquals("https://go.parachord.com/abc123", result?.url)
    coVerify(exactly = 0) { achordion.fetchEntityLink(any(), any(), any()) }
    coVerify(exactly = 0) { achordion.submitTrackLinks(any()) }
    coVerify(exactly = 1) { smartLinks.create(any()) }
}
```

Verify the `SmartLinkCreateResponse` shape and `PlaylistEntity` constructor match the actual types by reading them. Adjust if needed.

**Step 2: Run.**

Run: `./gradlew :app:testDebugUnitTest --tests "*ShareManagerTest*"`
Expected: 12/12 PASS.

**Step 3: Commit.**

```bash
git add app/src/test/java/com/parachord/android/share/ShareManagerTest.kt
git commit -m "$(cat <<'EOF'
test: lock sharePlaylist on SmartLinksClient (regression guard) (#share-links)

Phase-3 share-links migration is scoped to track/album/artist. Playlist
shares must continue going through SmartLinksClient (go.parachord.com)
since Achordion has no playlist entity page. This test would catch a
future refactor that accidentally routes playlists through Achordion.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 11: `SmartLinksClient` kdoc refresh

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/parachord/shared/api/SmartLinksClient.kt`

**Step 1: Update the class kdoc to clarify the post-migration role.**

Read the existing class kdoc and rewrite it to note:

- This client used to handle track + album + artist + playlist shares.
- After the Achordion migration (v0.6.x), it's playlist-only.
- Track/album/artist now go through `AchordionClient`.
- Keeping this client around because (a) it has tests + Koin wiring, (b) Achordion has no playlist entity yet, (c) future smart-link-shaped shares (e.g. albums-with-tracklist) could re-use it.

No code change.

**Step 2: Commit.**

```bash
git add shared/src/commonMain/kotlin/com/parachord/shared/api/SmartLinksClient.kt
git commit -m "$(cat <<'EOF'
docs: SmartLinksClient kdoc — playlist-only after Achordion migration (#share-links)

Document that track/album/artist shares moved to AchordionClient and
this client is retained for the playlist path only.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 12: Update CLAUDE.md "Outbound Sharing (Smart Links)" section

**Files:**
- Modify: `CLAUDE.md` (the "Outbound Sharing (Smart Links)" section)

**Step 1: Rewrite the section.**

Search the file for "Outbound Sharing (Smart Links)" — it's around the existing share-related infra notes. Rewrite to describe the new contract:

- **Track / album / artist shares produce Achordion entity URLs** (`achordion.xyz/recording/<mbid>`, etc.), via the new `AchordionClient` in shared.
- **Track shares** additionally pre-warm Achordion's cache via `/api/track-links/submit` so recipients see fully-resolved per-service links. Submit gates on (a) MBID present, (b) at least one streaming source ID non-blank. Per-session dedup by lowercase MBID.
- **Playlist shares** still use `SmartLinksClient` → `go.parachord.com/<id>` smart-links. Achordion has no playlist entity.
- **Bearer token** comes from `AppConfig.achordionBearerToken` (BuildConfig field). Empty token short-circuits cleanly to lookup URLs in debug builds.
- **Mirrors desktop's `publishSmartLink` / `publishAlbumSmartLink` / `publishArtistSmartLink`** in `parachord-desktop/app.js`.
- **Fallback** when MBID is missing or the API errors: lookup URL (`/recording/lookup?artist=&title=`, etc.) — Achordion does server-side MB search and 302s to the canonical page.

Add a "Key files" list:
- `shared/.../api/AchordionClient.kt` — Ktor client
- `app/.../share/ShareManager.kt` — caller
- `parachord-desktop/plugins/achordion.axe` — reference implementation

**Step 2: Commit.**

```bash
git add CLAUDE.md
git commit -m "$(cat <<'EOF'
docs: CLAUDE.md — Achordion share-links section update (#share-links)

Replaces the "Outbound Sharing (Smart Links)" section with the new
post-migration contract. Track/album/artist via AchordionClient,
playlists via SmartLinksClient. Documents the submit pre-warm gates
and the bearer-token flow.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 13: Manual on-device verification

**Step 1: Build + install.**

```bash
cd /Users/jherskowitz/Development/parachord/parachord-mobile
adb shell am force-stop com.parachord.android.debug
./gradlew installDebug
```

Confirm the new APK has `ACHORDION_BEARER_TOKEN` populated:

```bash
adb shell pm dump com.parachord.android.debug | grep -i build.config
```

(If you set the token in `local.properties` before building, it'll be there. If not, the share will fall through to lookup URLs — still works, just no pre-warm or canonical-slug URLs.)

**Step 2: Manual share tests.**

Open the app. For each:

- **Track share** — long-press a track row → Share. Expect a URL like `https://achordion.xyz/recording/<mbid-or-slug>`. Confirm via `adb logcat -s AchordionClient` you see both the entity-link call and the submit call.
- **Album share** — long-press an album card → Share. Expect `https://achordion.xyz/release-group/lookup?artist=…&title=…` (no MBID plumbed yet, hits the lookup path).
- **Artist share** — long-press an artist card → Share. Expect `https://achordion.xyz/artist/lookup?name=…`.
- **Playlist share** — long-press a playlist → Share. Expect `https://go.parachord.com/<id>` (unchanged from before).

**Step 3:** Tap the resulting Achordion URL on the device (paste into Chrome). It should land on the canonical entity page. Track shares should show Parachord-resolved per-service buttons immediately (the submit pre-warm).

No commit; this is verification only. Note any anomalies and either file follow-up issues or roll into a fix commit.

---

## Task 14: Open PR

**Step 1: Push branch.**

```bash
git push -u origin feature/achordion-share-links
```

**Step 2: Open PR via `gh`.**

```bash
gh pr create --title "Share links: track / album / artist → Achordion entity pages" --body "$(cat <<'EOF'
Migrates outbound share URLs from \`go.parachord.com\` smart-links to Achordion entity pages, matching desktop's \`publishSmartLink\` / \`publishAlbumSmartLink\` / \`publishArtistSmartLink\`. Playlists stay on the existing smart-link path (Achordion has no playlist entity).

## Summary

- New shared **\`AchordionClient\`** (Ktor) — \`fetchEntityLink(type, mbid)\` + \`submitTrackLinks(payload)\`. Bearer-authenticated.
- **\`ShareManager.shareTrack\`** now fires entity-link + submit in parallel via \`coroutineScope+async\`, awaits both, returns the canonical URL or a lookup-URL fallback. Submit pre-warms Achordion's cache so recipients see Parachord-resolved per-service links on first click.
- **\`shareAlbum\`** and **\`shareArtist\`** call entity-link only (no submit — desktop comment: "submissions are recording-keyed").
- **\`sharePlaylist\`** unchanged — \`SmartLinksClient\` retained for playlist shares.
- **Bearer token** threaded through \`AppConfig.achordionBearerToken\` from a new \`ACHORDION_BEARER_TOKEN\` BuildConfig field (sourced from \`local.properties\` + CI secret). Empty token short-circuits all Achordion calls cleanly.

## Submit gate (matches desktop)

Submit fires when:
1. \`track.recordingMbid\` is non-null+non-blank (proxies for "high-confidence resolution").
2. At least one of \`spotifyId\` / \`appleMusicId\` / \`soundcloudId\` is non-blank.
3. MBID hasn't already been submitted this process lifetime (per-session dedup, lowercase key, Mutex-protected).
4. \`authFailed\` kill-switch not tripped (first 401 sets it; later calls short-circuit).

## Test plan

- [x] 18 \`AchordionClientTest\` MockEngine cases — entity-link happy/404/malformed/auth/empty-token + submit happy/no-links/no-mbid/dedup/case-insensitive-dedup/auth/short-circuit/HTTP-error/network-error.
- [x] 12 \`ShareManagerTest\` mockk cases — track with/without MBID, album with/without MBID, artist with/without MBID, payload shape, blank-id filtering, no-sources-no-submit, playlist-regression-guard.
- [ ] Manual on-device — track / album / artist / playlist shares all produce the expected URL shape. Track share also pre-warms Achordion cache (confirm via logcat \`AchordionClient\` tag + visit the Achordion URL on a fresh-cache instance).

## Manual setup needed

- **GitHub repo secret**: add \`ACHORDION_BEARER_TOKEN\` with the published value from \`parachord-desktop/plugins/achordion.axe\`. Without this, release builds compile and ship but Achordion submits silently no-op and shares fall through to lookup URLs.

## Known follow-ups

- Album / Artist MBID plumbing: callers pass \`releaseGroupMbid = null\` / \`artistMbid = null\` today. \`AlbumEntity\` / \`ArtistEntity\` don't carry MBIDs yet. File-and-defer: thread MBIDs through from \`MetadataService\` results so album/artist shares can use canonical entity URLs (not just lookup fallback).
- Token leakage protection: workflow uses \`\${{ secrets.ACHORDION_BEARER_TOKEN }}\` directly — GitHub's secret masking handles log redaction. Don't \`echo\` the token in any future workflow step.

## Plan + design

- [docs/plans/2026-05-10-achordion-share-links-design.md](docs/plans/2026-05-10-achordion-share-links-design.md)
- [docs/plans/2026-05-10-achordion-share-links.md](docs/plans/2026-05-10-achordion-share-links.md)

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

**Step 3: Note in the PR description any anomalies from Task 13's manual verification.**

---

## Risk notes

- **Token leakage in CI logs.** Workflow uses `${{ secrets.ACHORDION_BEARER_TOKEN }}` so GitHub's secret masking redacts it from logs. Don't add `cat local.properties` or `env` dumps anywhere.
- **API drift.** Achordion's `/api/entity-link` and `/api/track-links/submit` response shapes are what desktop plugin parses today. Coordinate with achordion's owner if either changes.
- **Submit gate too lax.** Section 3 (design doc) discusses the MBID-only gate. If wrong-track submits start showing up in Achordion's cache, follow-up is to thread `TrackResolverCache` confidence into the gate. Low-probability per the rationale, but worth monitoring after rollout.
- **Timeout budget.** `SMART_LINK_TIMEOUT_MS = 4_000L` (reused from the old SmartLinks path). Worst case the share sheet shows after up to 4s if Achordion is slow. Consider Phase 3's Snackbar (#137) once it lands so the ack is visible during the wait.

## Out of scope

- Album / Artist MBID plumbing from `MetadataService` (file follow-up).
- The `fetchEmbedCode` endpoint (desktop has it, Android doesn't surface embeds).
- iOS share targets (KMP-ready since `AchordionClient` is in commonMain; iOS shell just needs to wire it).
- Rotating the published token (if achordion rotates it, update `ACHORDION_BEARER_TOKEN` repo secret + cut a new release).
