# Achordion share links — design

**Goal:** Migrate outbound Parachord-Android share URLs from `go.parachord.com/<id>` smart-links to Achordion entity pages (`achordion.xyz/<type>/<mbid>`), matching desktop's behavior in `parachord-desktop/app.js`'s `publishSmartLink` / `publishAlbumSmartLink` / `publishArtistSmartLink`. Track shares also pre-warm Achordion's cache via the `/api/track-links/submit` endpoint so recipients land on a fully-resolved entity page.

**Architecture:** New shared `AchordionClient` (Ktor) wraps two HTTP endpoints. `ShareManager` (`:app`) drops its `SmartLinksClient` calls for track/album/artist and routes them through the new client. Playlists keep `SmartLinksClient` since Achordion has no playlist entity. Bearer token sourced from `AppConfig.achordionBearerToken` (BuildConfig field, sourced from `local.properties` + CI secret).

**Tech stack:** Kotlin Multiplatform (`AchordionClient` in `shared/commonMain`, Ktor HTTP), Koin, mockk for tests, MockEngine for Ktor client tests.

## Scope

### In

- New shared `AchordionClient` with `fetchEntityLink(type, mbid)` + `submitTrackLinks(payload)`.
- `ShareManager.shareTrack` / `shareAlbum` / `shareArtist` rewritten to call `AchordionClient`.
- Bearer token threaded through `AppConfig`.
- Per-session dedup of submits + `authFailed` kill-switch (matches desktop plugin).
- Tests: `AchordionClientTest` (shared, MockEngine), `ShareManagerTest` (`:app`, mockk).

### Out

- Playlist shares — stay on `SmartLinksClient` / `go.parachord.com`.
- UI changes to share affordances — `ShareResult` shape unchanged.
- Token rotation infra — the token is semi-public (desktop publishes it in `.axe` plugin); rotation when it happens is a manual coordinated bump across desktop / android / ios repos.
- `fetchEmbedCode` (desktop has it; Android doesn't surface embeds yet).

## Sections

### 1. `AchordionClient` (shared, Ktor)

**File:** `shared/src/commonMain/kotlin/com/parachord/shared/api/AchordionClient.kt`

Two endpoints, both Bearer-token authenticated:

- `GET https://achordion.xyz/api/entity-link?type={track|release-group|artist}&mbid={mbid}` (optional `&include=names`). Returns `{ url, embed_url?, name?, artist_name?, album_name? }`.
- `POST https://achordion.xyz/api/track-links/submit` body `{ mbid, links: [{url, host, label?}], trackName?, artistName?, albumName? }`. Returns 200 on accept, 401 on bad token.

Public surface:

```kotlin
class AchordionClient(
    private val httpClient: HttpClient,
    private val bearerToken: String,
) {
    suspend fun fetchEntityLink(
        type: EntityType,
        mbid: String,
        includeNames: Boolean = false,
    ): EntityLink?

    suspend fun submitTrackLinks(payload: SubmitTrackLinksRequest): SubmitResult
}

enum class EntityType(val wireValue: String) {
    Track("track"),
    ReleaseGroup("release-group"),
    Artist("artist");
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

Internal state:

- `submittedThisSession: MutableSet<String>` — keyed by lowercased MBID. Prevents re-submitting the same recording within a single app lifetime. Mutex-protected (KMP rule: no `ConcurrentHashMap`).
- `authFailed: AtomicBoolean` (using `kotlin.concurrent.atomics` or a plain `@Volatile Boolean`). Set on first 401 from either endpoint. Once set, all subsequent calls short-circuit returning `AuthFailed` without hitting the network.

Both reset only on process restart. Matches desktop plugin's per-session model.

### 2. `ShareManager` rewrite (`:app`)

**File:** `app/src/main/java/com/parachord/android/share/ShareManager.kt`

Replace the three Achordion-eligible methods. `sharePlaylist(...)` stays unchanged.

```kotlin
class ShareManager(
    private val smartLinksClient: SmartLinksClient,   // playlists only
    private val achordionClient: AchordionClient,     // NEW
    private val playlistDao: PlaylistDao,
    private val playlistTrackDao: PlaylistTrackDao,
) {

    suspend fun shareTrack(track: TrackEntity): ShareResult = coroutineScope {
        val subject = "${track.artist} – ${track.title}"
        val mbid = track.recordingMbid
        // Fire entity-link + submit in parallel. Both awaited so the
        // recipient's first click sees a fully-warmed Achordion page.
        val entityLinkJob = async { tryFetchEntityLink(EntityType.Track, mbid) }
        val submitJob = async { trySubmitForTrack(track, mbid) }
        val entityUrl = entityLinkJob.await()
        submitJob.await()
        val url = entityUrl ?: trackLookupUrl(track.artist, track.title)
        ShareResult(url, subject, isSmartLink = entityUrl != null)
    }

    suspend fun shareAlbum(
        title: String,
        artist: String,
        artworkUrl: String?,  // unused now, kept for caller compat
        releaseGroupMbid: String?,
    ): ShareResult {
        val entityUrl = tryFetchEntityLink(EntityType.ReleaseGroup, releaseGroupMbid)
        val url = entityUrl ?: releaseGroupLookupUrl(artist, title)
        return ShareResult(url, "$artist – $title", isSmartLink = entityUrl != null)
    }

    fun shareArtist(name: String, artistMbid: String? = null): ShareResult {
        // No suspend wrapper — entity-link API is async but artist share
        // is fire-and-forget on the API side; let the caller decide
        // whether to await. Actually, since callers always go through
        // suspend context, just make it suspend too:
    }

    suspend fun shareArtist(name: String, artistMbid: String? = null): ShareResult {
        val entityUrl = tryFetchEntityLink(EntityType.Artist, artistMbid)
        val url = entityUrl ?: artistLookupUrl(name)
        return ShareResult(url, name, isSmartLink = entityUrl != null)
    }

    // sharePlaylist unchanged
}
```

**Internal helpers:**

```kotlin
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
    if (mbid.isNullOrBlank()) return   // Section 3 gate: no MBID → no submit
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

private fun releaseGroupLookupUrl(artist: String, title: String): String =
    "https://achordion.xyz/release-group/lookup?artist=${enc(artist)}&title=${enc(title)}"

private fun artistLookupUrl(name: String): String =
    "https://achordion.xyz/artist/lookup?name=${enc(name)}"
```

### 3. Confidence gate (Section 3 decision)

Submit fires when `track.recordingMbid` is non-null AND at least one streaming ID is non-blank. No per-source confidence threading — the MBID presence is the proxy gate.

Rationale: `MbidEnrichmentService` only stamps `recordingMbid` when the ListenBrainz mapper returns a confident match, which correlates strongly with the per-resolver IDs being correct. Wrong-song submits are theoretically possible but low-probability; if they appear in practice, follow-up issue threads `TrackResolverCache` confidence into the gate.

### 4. Bearer token via `AppConfig`

**Files modified:**

- `shared/src/commonMain/kotlin/com/parachord/shared/config/AppConfig.kt` — add `val achordionBearerToken: String`.
- `app/build.gradle.kts` — add `buildConfigField("String", "ACHORDION_BEARER_TOKEN", "\"${localProp("ACHORDION_BEARER_TOKEN")}\"")`. Default to empty string when absent.
- `app/src/main/java/com/parachord/android/di/AndroidModule.kt` — populate `AppConfig.achordionBearerToken = BuildConfig.ACHORDION_BEARER_TOKEN` in the `AppConfig` factory.
- `shared/src/commonMain/kotlin/com/parachord/shared/di/SharedModule.kt` — wire `AchordionClient` Koin binding with `bearerToken = get<AppConfig>().achordionBearerToken`.
- `.github/workflows/build.yml` — surface `ACHORDION_BEARER_TOKEN` from CI secret to `local.properties` (same pattern as `LASTFM_API_KEY`).
- `local.properties` — local developers add `ACHORDION_BEARER_TOKEN=parachord_…` if they want submit/entity-link to work in dev builds. Without the line, the token is empty and `AchordionClient` no-ops (returns null entity links, returns `AuthFailed` from `submitTrackLinks` — Section 1 short-circuit). Falls through cleanly to lookup URLs; share still works, just without pre-warm or canonical-slug URLs.

The published token (matching desktop's `.axe` plugin): `parachord_rgOgj2trN2KeIovar9DYA-yOCRkxgO6KlSyAo_jHtgg`.

### 5. `SmartLinksClient` disposition

Keep `SmartLinksClient` as-is. Update its class kdoc to: "Playlist shares only — track/album/artist migrated to Achordion as of 0.6.x; this client is retained for playlists since Achordion has no playlist entity page." No Koin removal, no test changes.

### 6. Tests

#### `AchordionClientTest` (shared, MockEngine)

```kotlin
class AchordionClientTest {
    @Test fun fetchEntityLink_returnsCanonicalUrl_whenApiReturns200() = runTest { … }
    @Test fun fetchEntityLink_returnsNull_on404() = runTest { … }
    @Test fun fetchEntityLink_returnsNull_onMalformedResponse() = runTest { … }
    @Test fun fetchEntityLink_sendsBearerTokenInAuthHeader() = runTest { … }
    @Test fun fetchEntityLink_encodesMbidInQueryString() = runTest { … }
    @Test fun submitTrackLinks_returnsOk_whenApiReturns200() = runTest { … }
    @Test fun submitTrackLinks_returnsAlreadySubmitted_onSecondCallSameMbid() = runTest { … }
    @Test fun submitTrackLinks_dedupKeyIsLowercaseMbid() = runTest { … }
    @Test fun submitTrackLinks_returnsAuthFailed_on401() = runTest { … }
    @Test fun submitTrackLinks_authFailedShortCircuitsSubsequentCalls() = runTest { … }
    @Test fun submitTrackLinks_returnsNoLinks_whenLinksListIsEmpty() = runTest { … }
    @Test fun submitTrackLinks_sendsBearerTokenAndJsonBody() = runTest { … }
}
```

#### `ShareManagerTest` (`:app`, mockk)

```kotlin
class ShareManagerTest {
    @Test fun shareTrack_withMbid_callsBothEntityLinkAndSubmit_inParallel() = runTest { … }
    @Test fun shareTrack_withoutMbid_callsNeitherApi_returnsLookupFallback() = runTest { … }
    @Test fun shareTrack_entityLinkFails_returnsLookupFallback_butStillCallsSubmit() = runTest { … }
    @Test fun shareTrack_submitFails_doesNotBlockReturn() = runTest { … }
    @Test fun shareTrack_awaitsBothBeforeReturning_orderCheck() = runTest { … }
    @Test fun shareAlbum_callsEntityLinkOnly_noSubmit() = runTest { … }
    @Test fun shareAlbum_noMbid_returnsLookupFallback() = runTest { … }
    @Test fun shareArtist_callsEntityLinkOnly_noSubmit() = runTest { … }
    @Test fun shareArtist_noMbid_returnsLookupFallback() = runTest { … }
    @Test fun sharePlaylist_unchanged_stillUsesSmartLinksClient() = runTest { … }
}
```

### 7. UI / surface

No UI changes. `ShareResult(url, subject, isSmartLink)` shape unchanged. All callsites (`TrackContextMenuHost`, `rememberShareTrack`, `rememberShareAlbumLite`, `rememberShareArtist`, `rememberSharePlaylist`, `rememberSharePlaylistById`) keep working unchanged. The `isSmartLink` boolean is computed differently now (true when the Achordion entity-link API returned a canonical URL; false when we fell through to the lookup URL) but its semantic stays "did we get the nice URL or the fallback".

### 8. Callsite updates

Search for callers passing the old shape to `ShareManager.shareAlbum` (currently takes `tracks: List<PlaylistTrackEntity>, spotifyAlbumId: String?`) and update to the new signature. Expected callers per CLAUDE.md "Outbound Sharing (Smart Links)":

- `ui/components/AlbumContextMenu.kt` (via `rememberShareAlbumLite` / rich variant)
- `ui/screens/album/AlbumDetailScreen.kt`
- Any other album-share entry point

The `tracks` and `spotifyAlbumId` parameters get dropped from `shareAlbum`'s signature; callers that pass them today need updating. `releaseGroupMbid: String?` is added. Most album entities should already carry an MBID via `AlbumEntity.mbid` — confirm at implementation time which field holds it.

Same drill for `shareArtist` — currently `(name: String, imageUrl: String? = null)`, becomes `(name: String, artistMbid: String? = null)`. `imageUrl` was unused in the existing `shareArtist` body anyway.

## Risk notes

- **Token leakage in CI logs.** Workflow must mask the secret. Use `${{ secrets.ACHORDION_BEARER_TOKEN }}` and don't `echo` it. Same pattern as `LASTFM_API_KEY`.
- **API drift.** Achordion's `/api/entity-link` response shape is what desktop plugin parses today. If Achordion changes it, both desktop and android need updating. Worth filing a cross-repo "API contract" note in achordion's AGENTS.md.
- **Submit gate too lax.** Section 3's MBID-only gate may submit wrong-song matches occasionally. Mitigation: file a follow-up to thread `TrackResolverCache` confidence into the gate if Achordion's match cache shows drift.
- **Timeout budget.** Share sheet must open within ~500ms of tap to feel responsive. The 4s `SMART_LINK_TIMEOUT_MS` is a worst case for entity-link + submit. Recipient experience is better with the wait, but consider showing a "Copying…" ack toast during the wait (we already have `DeepLinkNavEvent.Toast.longDuration` from Phase 3 polish — reusable here).

## Cross-refs

- Parachord-Android #137 — Compose Snackbar (overlap if we add a "Copying…" ack)
- jherskowitz/achordion#54 — unrelated share concern (Mode C-inline shrink)
- Achordion AGENTS.md § "Track-links submission" — the submit endpoint contract this design depends on
- `parachord-desktop/plugins/achordion.axe` — reference implementation for the submit + entity-link client
- `parachord-desktop/app.js` lines 13367–13635 — `publishSmartLink` / `publishAlbumSmartLink` / `publishArtistSmartLink` (desktop parity reference)
