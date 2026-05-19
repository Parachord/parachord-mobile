package com.parachord.shared.repository

import com.parachord.shared.api.LastFmClient
import com.parachord.shared.api.LastFmRateLimitedException
import com.parachord.shared.api.ListenBrainzClient
import com.parachord.shared.api.bestImageUrl
import com.parachord.shared.db.dao.FriendDao
import com.parachord.shared.metadata.MetadataService
import com.parachord.shared.model.Friend
import com.parachord.shared.model.HistoryAlbum
import com.parachord.shared.model.HistoryArtist
import com.parachord.shared.model.HistoryTrack
import com.parachord.shared.model.RecentTrack
import com.parachord.shared.model.Resource
import com.parachord.shared.platform.Log
import com.parachord.shared.platform.currentTimeMillis
import com.parachord.shared.platform.randomUUID
import com.parachord.shared.settings.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Repository for managing friends and fetching their listening data.
 * Mirrors the desktop app's friend management (add, validate, fetch activity).
 *
 * The Last.fm API key is passed in via constructor (Android sources it
 * from `BuildConfig.LASTFM_API_KEY`; iOS will source it from
 * `AppConfig` once the iOS DI module lights up).
 */
class FriendsRepository(
    private val friendDao: FriendDao,
    private val lastFmClient: LastFmClient,
    private val listenBrainzClient: ListenBrainzClient,
    private val metadataService: MetadataService,
    private val settingsStore: SettingsStore,
    private val lastFmApiKey: String,
) {
    companion object {
        private const val TAG = "FriendsRepository"
    }

    /** All friends as a live-updating Flow. */
    fun getAllFriends(): Flow<List<Friend>> = friendDao.getAllFriends()

    /** Only friends pinned to sidebar (manual or auto-pinned). */
    fun getPinnedFriends(): Flow<List<Friend>> = friendDao.getPinnedFriends()

    /** Get a single friend by ID. */
    suspend fun getFriendById(id: String): Friend? = friendDao.getFriendById(id)

    /**
     * Look up a saved friend by `(service, username)`. Case-insensitive
     * on the username so the deeplink user `?user=mrmonkey` finds a
     * stored "MrMonkey".
     *
     * Used by the `parachord://listen-along` dispatcher to decide
     * between calling [MainViewModel.startListenAlong] with a stored
     * Friend (this returns non-null) versus minting a transient one
     * via [fetchTransientFriendNowPlaying] (this returns null).
     */
    suspend fun findByServiceAndUsername(service: String, username: String): Friend? =
        withContext(Dispatchers.Default) {
            friendDao.findByServiceAndUsername(service, username)
        }

    /**
     * Fetch the target user's currently-playing track and build a
     * synthetic, NON-PERSISTED [Friend] record for the
     * `parachord://listen-along` deeplink path. Returns `null` when:
     *
     *  - LB user has no `playing-now` entry, OR
     *  - Last.fm response's first track has `@attr.nowplaying != "true"`,
     *    OR
     *  - the API call fails (calm UX — caller surfaces a "not currently
     *    listening" toast rather than an error).
     *
     * The returned Friend's id is `transient:{service}:{user}` and its
     * `transient` flag is `true`. Callers pass it directly to
     * `MainViewModel.startListenAlong(friend)` — that VM only reads
     * cachedTrack* fields + id/displayName/service, none of which need
     * a Room row to exist. `cachedTrackTimestamp` is stamped at "now"
     * (seconds since epoch) so [Friend.isOnAir] returns true.
     */
    suspend fun fetchTransientFriendNowPlaying(service: String, user: String): Friend? =
        withContext(Dispatchers.Default) {
            try {
                when (service) {
                    "listenbrainz" -> {
                        val listen = listenBrainzClient.getPlayingNow(user) ?: return@withContext null
                        buildTransientFriend(
                            service = service,
                            user = user,
                            trackName = listen.trackName,
                            trackArtist = listen.artistName,
                            trackAlbum = listen.releaseName,
                            artworkUrl = null,
                        )
                    }
                    "lastfm" -> {
                        val response = lastFmClient.getUserRecentTracks(
                            user = user,
                            apiKey = lastFmApiKey,
                            limit = 1,
                        )
                        val first = response.recenttracks?.track?.firstOrNull() ?: return@withContext null
                        // Only treat the row as "now playing" when Last.fm
                        // explicitly flags it. Unlike refreshLastFmActivity()
                        // we don't sanity-check against a recent scrobble
                        // here — the deeplink path is one-shot and the user
                        // is already opting in by sharing the link; even a
                        // slightly stale flag is acceptable, and the polling
                        // loop will recover on the next tick.
                        if (first.attr?.nowplaying != "true") return@withContext null
                        val artist = first.artist?.name?.takeIf { it.isNotBlank() }
                            ?: return@withContext null
                        val title = first.name.takeIf { it.isNotBlank() }
                            ?: return@withContext null
                        buildTransientFriend(
                            service = service,
                            user = user,
                            trackName = title,
                            trackArtist = artist,
                            trackAlbum = first.album?.name?.takeIf { it.isNotBlank() },
                            artworkUrl = first.image.bestImageUrl(),
                        )
                    }
                    else -> null
                }
            } catch (e: Exception) {
                Log.w(TAG, "fetchTransientFriendNowPlaying failed for $service:$user", e)
                null
            }
        }

    private fun buildTransientFriend(
        service: String,
        user: String,
        trackName: String,
        trackArtist: String,
        trackAlbum: String?,
        artworkUrl: String?,
    ): Friend = Friend(
        id = "transient:$service:$user",
        username = user,
        service = service,
        displayName = user,
        avatarUrl = null,
        addedAt = currentTimeMillis(),
        lastFetchedAt = currentTimeMillis(),
        cachedTrackName = trackName,
        cachedTrackArtist = trackArtist,
        cachedTrackAlbum = trackAlbum,
        cachedTrackArtworkUrl = artworkUrl,
        // Seconds since epoch — matches the timestamp scale used by
        // refreshLastFmActivity and refreshListenBrainzActivity, so
        // Friend.isOnAir's 10-min window evaluates correctly.
        cachedTrackTimestamp = currentTimeMillis() / 1000,
        pinnedToSidebar = false,
        autoPinned = false,
        transient = true,
    )

    /** Remove a friend locally and unfollow on the service. */
    suspend fun removeFriend(friendId: String) = withContext(Dispatchers.Default) {
        val friend = friendDao.getFriendById(friendId) ?: return@withContext
        // Add to deleted keys so sync doesn't re-add them
        val key = "${friend.service}:${friend.username.lowercase()}"
        settingsStore.addDeletedFriendKey(key)
        // Unfollow on service (ListenBrainz supports this; Last.fm does not)
        unfollowOnService(friend)
        // Delete locally
        friendDao.delete(friendId)
    }

    /** Manually pin/unpin a friend to the sidebar. */
    suspend fun pinFriend(friendId: String, pinned: Boolean) {
        friendDao.setPinned(id = friendId, pinned = pinned, auto = false)
    }

    /** Auto-pin friends that are on-air, auto-unpin those that aren't. */
    suspend fun updateAutoPins() = withContext(Dispatchers.Default) {
        val allFriends = friendDao.getAllFriendsSync()
        for (friend in allFriends) {
            if (friend.isOnAir && !friend.pinnedToSidebar) {
                // Auto-pin on-air friend
                friendDao.setPinned(id = friend.id, pinned = true, auto = true)
                Log.d(TAG, "Auto-pinned on-air friend: ${friend.displayName}")
            } else if (!friend.isOnAir && friend.autoPinned && friend.pinnedToSidebar) {
                // Auto-unpin friend that was auto-pinned but is no longer on-air
                friendDao.setPinned(id = friend.id, pinned = false, auto = false)
                Log.d(TAG, "Auto-unpinned inactive friend: ${friend.displayName}")
            }
        }
    }

    // ---------- Input Parsing (mirrors desktop parseFriendInput) ----------

    data class ParsedFriend(val service: String, val username: String)

    /**
     * Parse user input into service + username.
     * Accepts:
     * - Plain username (defaults to Last.fm)
     * - https://www.last.fm/user/{name}
     * - https://listenbrainz.org/user/{name}
     */
    fun parseFriendInput(input: String): ParsedFriend? {
        val trimmed = input.trim()
        if (trimmed.isBlank()) return null

        // Last.fm URL
        val lastFmRegex = Regex("""(?:https?://)?(?:www\.)?last\.fm/user/([^/?#]+)""", RegexOption.IGNORE_CASE)
        lastFmRegex.find(trimmed)?.let { match ->
            return ParsedFriend("lastfm", match.groupValues[1])
        }

        // ListenBrainz URL
        val lbRegex = Regex("""(?:https?://)?listenbrainz\.org/user/([^/?#]+)""", RegexOption.IGNORE_CASE)
        lbRegex.find(trimmed)?.let { match ->
            return ParsedFriend("listenbrainz", match.groupValues[1])
        }

        // Plain username — default to Last.fm
        if (trimmed.contains("/") || trimmed.contains("://")) return null
        return ParsedFriend("lastfm", trimmed)
    }

    // ---------- Add Friend ----------

    /**
     * Add a friend by raw input (username or URL).
     * Validates the user exists, fetches display info, stores locally.
     */
    suspend fun addFriend(input: String): Resource<Friend> = withContext(Dispatchers.Default) {
        val parsed = parseFriendInput(input)
            ?: return@withContext Resource.Error("Invalid username or URL")

        try {
            val friend = when (parsed.service) {
                "lastfm" -> addLastFmFriend(parsed.username)
                "listenbrainz" -> addListenBrainzFriend(parsed.username)
                else -> return@withContext Resource.Error("Unknown service")
            }

            if (friend != null) {
                // Clear deleted key if re-adding a previously removed friend
                val key = "${friend.service}:${friend.username.lowercase()}"
                settingsStore.removeDeletedFriendKey(key)
                friendDao.upsert(friend)
                // Follow on the service so desktop stays in sync
                followOnService(friend)
                // Fetch initial activity
                refreshFriendActivity(friend)
                // Re-read from DB to get latest cached data
                val updated = friendDao.getFriendById(friend.id) ?: friend
                Resource.Success(updated)
            } else {
                Resource.Error("User not found")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add friend: ${parsed.username}", e)
            Resource.Error("Failed to add friend: ${e.message}")
        }
    }

    private suspend fun addLastFmFriend(username: String): Friend? {
        val response = lastFmClient.getUserInfo(
            user = username,
            apiKey = lastFmApiKey,
        )
        val user = response.user ?: return null

        return Friend(
            id = newFriendId(),
            username = user.name,
            service = "lastfm",
            displayName = user.realname?.takeIf { it.isNotBlank() } ?: user.name,
            avatarUrl = user.image.bestImageUrl(),
            addedAt = currentTimeMillis(),
        )
    }

    private suspend fun addListenBrainzFriend(username: String): Friend? {
        val exists = listenBrainzClient.validateUser(username)
        if (!exists) return null

        return Friend(
            id = newFriendId(),
            username = username,
            service = "listenbrainz",
            displayName = username, // ListenBrainz doesn't provide display names
            addedAt = currentTimeMillis(),
        )
    }

    /** Generate a unique friend ID using shared platform helpers. */
    private fun newFriendId(): String =
        "friend-${currentTimeMillis()}-${randomUUID().take(8)}"

    /**
     * Follow a user on their service so changes propagate to other Parachord clients.
     * ListenBrainz supports follow via API; Last.fm deprecated their add-friend API.
     */
    private suspend fun followOnService(friend: Friend) {
        try {
            when (friend.service) {
                "listenbrainz" -> {
                    val token = settingsStore.getListenBrainzToken() ?: return
                    listenBrainzClient.followUser(friend.username, token)
                    Log.d(TAG, "Followed ${friend.username} on ListenBrainz")
                }
                // Last.fm deprecated user.addFriend — can't follow via API
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to follow ${friend.username} on ${friend.service}", e)
        }
    }

    /**
     * Unfollow a user on their service when removing them from friends.
     * ListenBrainz supports unfollow via API; Last.fm deprecated their friend API.
     */
    private suspend fun unfollowOnService(friend: Friend) {
        try {
            when (friend.service) {
                "listenbrainz" -> {
                    val token = settingsStore.getListenBrainzToken() ?: return
                    listenBrainzClient.unfollowUser(friend.username, token)
                    Log.d(TAG, "Unfollowed ${friend.username} on ListenBrainz")
                }
                // Last.fm deprecated friend management API — can't unfollow via API.
                // The deleted-keys mechanism prevents re-sync instead.
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to unfollow ${friend.username} on ${friend.service}", e)
        }
    }

    // ---------- Sync Friends from Services ----------

    /**
     * Sync friends from Last.fm and ListenBrainz.
     * Pulls the user's friend/following lists from both services and adds
     * any users not already in the local friends DB.
     * This keeps friends in sync across desktop and mobile without a backend.
     *
     * @return number of newly synced friends
     */
    suspend fun syncFriendsFromServices(): Int = withContext(Dispatchers.Default) {
        val existingFriends = friendDao.getAllFriendsSync()
        val existingByKey = existingFriends.associate {
            "${it.service}:${it.username.lowercase()}" to it
        }
        val deletedKeys = settingsStore.getDeletedFriendKeys()
        var synced = 0

        // Sync from Last.fm
        try {
            val lastFmUsername = settingsStore.getLastFmUsername()
            if (lastFmUsername != null) {
                val response = lastFmClient.getUserFriends(
                    user = lastFmUsername,
                    apiKey = lastFmApiKey,
                )
                val friends = response.friends?.user ?: emptyList()
                for (user in friends) {
                    val key = "lastfm:${user.name.lowercase()}"
                    if (key !in existingByKey && key !in deletedKeys) {
                        val entity = Friend(
                            id = newFriendId(),
                            username = user.name,
                            service = "lastfm",
                            displayName = user.realname?.takeIf { it.isNotBlank() } ?: user.name,
                            avatarUrl = user.image.bestImageUrl(),
                            addedAt = currentTimeMillis(),
                        )
                        friendDao.upsert(entity)
                        synced++
                        Log.d(TAG, "Synced Last.fm friend: ${user.name}")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to sync Last.fm friends", e)
        }

        // Sync from ListenBrainz
        try {
            val lbUsername = settingsStore.getListenBrainzUsername()
            if (lbUsername != null) {
                val following = listenBrainzClient.getUserFollowing(lbUsername)
                for (username in following) {
                    val key = "listenbrainz:${username.lowercase()}"
                    if (key !in existingByKey && key !in deletedKeys) {
                        val entity = Friend(
                            id = newFriendId(),
                            username = username,
                            service = "listenbrainz",
                            displayName = username,
                            addedAt = currentTimeMillis(),
                        )
                        friendDao.upsert(entity)
                        synced++
                        Log.d(TAG, "Synced ListenBrainz following: $username")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to sync ListenBrainz following", e)
        }

        Log.d(TAG, "Friend sync complete: $synced new friends")
        synced
    }

    // ---------- Refresh Activity ----------

    /**
     * Refresh a friend's "now playing" / most recent track.
     * Updates the cached track in Room.
     */
    suspend fun refreshFriendActivity(friend: Friend) = withContext(Dispatchers.Default) {
        try {
            when (friend.service) {
                "lastfm" -> refreshLastFmActivity(friend)
                "listenbrainz" -> refreshListenBrainzActivity(friend)
            }
        } catch (e: LastFmRateLimitedException) {
            // Expected transient under load — the [LastFmClient] rate-limit
            // gate is doing its job (cooldown is active, short-circuiting
            // further calls without a network hit). Demote to debug so a
            // user with 60+ Last.fm friends doesn't see a stack-trace burst
            // every 2-minute refresh cycle. Re-thrown from the call below
            // so [refreshAllActivity] can short-circuit the rest of the
            // cycle's Last.fm friends.
            Log.d(TAG, "Last.fm rate-limited for ${friend.username} (cooldown active) — skipping")
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Failed to refresh activity for ${friend.username}", e)
        }
    }

    /**
     * Refresh all friends' activity and update auto-pins.
     * Called periodically (every 2 minutes) by MainViewModel.
     *
     * Skips remaining Last.fm friends in the cycle once the rate-limit gate
     * trips — those calls would all throw [LastFmRateLimitedException]
     * synchronously without making a network call, but the noise still
     * accumulates in logcat and wastes a few hundred coroutine launches
     * every cycle. ListenBrainz friends are unaffected (separate API +
     * separate gate) so the loop continues for them.
     */
    suspend fun refreshAllActivity() = withContext(Dispatchers.Default) {
        val allFriends = friendDao.getAllFriendsSync()
        var lastFmRateLimited = false
        for (friend in allFriends) {
            if (lastFmRateLimited && friend.service == "lastfm") continue
            try {
                refreshFriendActivity(friend)
            } catch (e: LastFmRateLimitedException) {
                // First trip of the cycle — log once at debug, then suppress
                // for the rest of this cycle's Last.fm friends.
                lastFmRateLimited = true
                Log.d(TAG, "Last.fm rate-limit tripped; skipping remaining Last.fm friends this cycle")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to refresh ${friend.username}", e)
            }
        }
        updateAutoPins()
    }

    private suspend fun refreshLastFmActivity(friend: Friend) {
        // Fetch 2 tracks so we can sanity-check the `attr.nowplaying`
        // flag against an actual scrobble timestamp. Last.fm leaves the
        // flag set on the most recent track for hours after the user
        // actually stopped playing — without a backing recent scrobble
        // we'd keep treating them as on-air indefinitely. Two tracks is
        // also cheap (~one extra row in the response).
        val response = lastFmClient.getUserRecentTracks(
            user = friend.username,
            limit = 2,
            apiKey = lastFmApiKey,
        )
        val tracks = response.recenttracks?.track.orEmpty()
        val track = tracks.firstOrNull()
        // The most recent track with a real scrobble timestamp. When
        // track[0] is the now-playing entry (no `date`), this is
        // track[1]. When track[0] is a historical scrobble, it IS
        // track[0]. Either way, `latestScrobble` represents the most
        // recent definitive listen.
        val latestScrobble = tracks.firstNotNullOfOrNull {
            it.date?.uts?.toLongOrNull()
        } ?: 0L
        val now = currentTimeMillis() / 1000
        // Last.fm's `attr.nowplaying = "true"` flag is set on the most
        // recent track when the user is currently scrobbling, BUT it's
        // also left in place after they stop — hours later the same row
        // can still come back marked nowplaying. Only trust the flag if
        // it's backed by a recent scrobble (within ~30 min): a truly
        // active listener will have completed at least one full song in
        // that window, putting that scrobble's `date.uts` in the second
        // slot of the response.
        val nowPlayingFlag = track?.attr?.nowplaying == "true"
        val recentScrobble = latestScrobble > 0 && (now - latestScrobble) < 1800
        val trulyOnAir = nowPlayingFlag && recentScrobble
        val timestamp = when {
            track == null -> 0L
            // Currently playing AND backed by a real recent scrobble —
            // safe to stamp now so isOnAir returns true.
            trulyOnAir -> now
            // Use the latest definitive scrobble timestamp. `isOnAir`'s
            // 10-min window keeps an active listener showing as on-air
            // for that long after their last full song completes.
            else -> latestScrobble
        }
        friendDao.updateCachedTrack(
            id = friend.id,
            name = track?.name,
            artist = track?.artist?.name,
            album = track?.album?.name,
            timestamp = timestamp,
            artworkUrl = track?.image?.bestImageUrl(),
            fetchedAt = currentTimeMillis(),
        )
    }

    private suspend fun refreshListenBrainzActivity(friend: Friend) {
        val listens = listenBrainzClient.getRecentListens(friend.username, count = 1)
        val listen = listens.firstOrNull()
        friendDao.updateCachedTrack(
            id = friend.id,
            name = listen?.trackName,
            artist = listen?.artistName,
            album = listen?.releaseName,
            timestamp = listen?.listenedAt ?: 0,
            artworkUrl = null, // ListenBrainz doesn't provide artwork
            fetchedAt = currentTimeMillis(),
        )
    }

    // ---------- Friend History Data ----------

    /** Map ListenBrainz range values to Last.fm period values. */
    private fun periodToLbRange(period: String): String = when (period) {
        "7day" -> "week"
        "1month" -> "month"
        "3month" -> "quarter"
        "6month" -> "half_yearly"
        "12month" -> "year"
        "overall" -> "all_time"
        else -> "month"
    }

    fun getFriendTopTracks(username: String, service: String, period: String): Flow<Resource<List<HistoryTrack>>> = flow {
        emit(Resource.Loading)
        try {
            val tracks = when (service) {
                "lastfm" -> {
                    val response = lastFmClient.getUserTopTracks(
                        user = username,
                        period = period,
                        limit = 50,
                        apiKey = lastFmApiKey,
                    )
                    response.toptracks?.track?.mapIndexed { index, track ->
                        HistoryTrack(
                            title = track.name,
                            artist = track.artist?.name ?: "",
                            artworkUrl = track.image.bestImageUrl(),
                            playCount = track.playcount?.toIntOrNull() ?: 0,
                            rank = track.attr?.rank?.toIntOrNull() ?: (index + 1),
                        )
                    } ?: emptyList()
                }
                "listenbrainz" -> {
                    val recordings = listenBrainzClient.getUserTopRecordings(
                        username = username,
                        range = periodToLbRange(period),
                        count = 50,
                    )
                    recordings.mapIndexed { index, rec ->
                        HistoryTrack(
                            title = rec.trackName,
                            artist = rec.artistName,
                            playCount = rec.listenCount,
                            rank = index + 1,
                        )
                    }
                }
                else -> emptyList()
            }
            emit(Resource.Success(tracks))

            // Enrich tracks missing artwork
            val tracksNeedingArt = tracks.filter { it.artworkUrl == null }
            if (tracksNeedingArt.isNotEmpty()) {
                val enriched = enrichTrackArtwork(tracks)
                emit(Resource.Success(enriched))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch friend top tracks", e)
            emit(Resource.Error("Failed to load top tracks"))
        }
    }

    fun getFriendTopAlbums(username: String, service: String, period: String): Flow<Resource<List<HistoryAlbum>>> = flow {
        emit(Resource.Loading)
        try {
            val albums = when (service) {
                "lastfm" -> {
                    val response = lastFmClient.getUserTopAlbums(
                        user = username,
                        period = period,
                        limit = 50,
                        apiKey = lastFmApiKey,
                    )
                    response.topalbums?.album?.mapIndexed { index, album ->
                        HistoryAlbum(
                            name = album.name,
                            artist = album.artist?.name ?: "",
                            artworkUrl = album.image.bestImageUrl(),
                            playCount = album.playcount?.toIntOrNull() ?: 0,
                            rank = album.attr?.rank?.toIntOrNull() ?: (index + 1),
                        )
                    } ?: emptyList()
                }
                "listenbrainz" -> {
                    val releases = listenBrainzClient.getUserTopReleases(
                        username = username,
                        range = periodToLbRange(period),
                        count = 50,
                    )
                    releases.mapIndexed { index, rel ->
                        HistoryAlbum(
                            name = rel.releaseName,
                            artist = rel.artistName,
                            playCount = rel.listenCount,
                            rank = index + 1,
                        )
                    }
                }
                else -> emptyList()
            }
            emit(Resource.Success(albums))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch friend top albums", e)
            emit(Resource.Error("Failed to load top albums"))
        }
    }

    fun getFriendTopArtists(username: String, service: String, period: String): Flow<Resource<List<HistoryArtist>>> = flow {
        emit(Resource.Loading)
        try {
            val artists = when (service) {
                "lastfm" -> {
                    val response = lastFmClient.getUserTopArtists(
                        user = username,
                        period = period,
                        limit = 50,
                        apiKey = lastFmApiKey,
                    )
                    response.topartists?.artist?.mapIndexed { index, artist ->
                        HistoryArtist(
                            name = artist.name,
                            imageUrl = artist.image.bestImageUrl(),
                            playCount = artist.playcount?.toIntOrNull() ?: 0,
                            rank = artist.attr?.rank?.toIntOrNull() ?: (index + 1),
                        )
                    } ?: emptyList()
                }
                "listenbrainz" -> {
                    val lbArtists = listenBrainzClient.getUserTopArtists(
                        username = username,
                        range = periodToLbRange(period),
                        count = 50,
                    )
                    lbArtists.mapIndexed { index, artist ->
                        HistoryArtist(
                            name = artist.name,
                            playCount = artist.listenCount,
                            rank = index + 1,
                        )
                    }
                }
                else -> emptyList()
            }
            emit(Resource.Success(artists))

            // Enrich artists missing images (Last.fm deprecated images in 2020)
            val artistsNeedingImages = artists.filter { it.imageUrl == null }
            if (artistsNeedingImages.isNotEmpty()) {
                val enriched = enrichArtistImages(artists)
                emit(Resource.Success(enriched))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch friend top artists", e)
            emit(Resource.Error("Failed to load top artists"))
        }
    }

    fun getFriendRecentTracks(username: String, service: String): Flow<Resource<List<RecentTrack>>> = flow {
        emit(Resource.Loading)
        try {
            val tracks = when (service) {
                "lastfm" -> {
                    val response = lastFmClient.getUserRecentTracks(
                        user = username,
                        limit = 50,
                        apiKey = lastFmApiKey,
                    )
                    response.recenttracks?.track?.map { track ->
                        RecentTrack(
                            title = track.name,
                            artist = track.artist?.name ?: "",
                            album = track.album?.name,
                            artworkUrl = track.image.bestImageUrl(),
                            timestamp = track.date?.uts?.toLongOrNull() ?: 0,
                            source = "Last.fm",
                            nowPlaying = track.attr?.nowplaying == "true",
                        )
                    } ?: emptyList()
                }
                "listenbrainz" -> {
                    val listens = listenBrainzClient.getRecentListens(username, count = 50)
                    listens.map { listen ->
                        RecentTrack(
                            title = listen.trackName,
                            artist = listen.artistName,
                            album = listen.releaseName,
                            timestamp = listen.listenedAt,
                            source = "ListenBrainz",
                        )
                    }
                }
                else -> emptyList()
            }
            emit(Resource.Success(tracks))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch friend recent tracks", e)
            emit(Resource.Error("Failed to load recent tracks"))
        }
    }

    // ---------- Artwork Enrichment ----------
    //
    // `synchronized(map) { ... }` is JVM-only — replaced with `Mutex` for KMP.
    // Lock scope is just the put-or-get window, so contention is negligible
    // compared to the network call itself.

    private suspend fun enrichTrackArtwork(tracks: List<HistoryTrack>): List<HistoryTrack> = coroutineScope {
        val artworkCache = mutableMapOf<String, String>()
        val cacheMutex = Mutex()
        tracks.filter { it.artworkUrl == null }.take(15).map { track ->
            async {
                try {
                    val results = metadataService.searchTracks("${track.artist} ${track.title}", limit = 1)
                    val artwork = results.firstOrNull()?.artworkUrl
                    if (artwork != null) {
                        val key = "${track.artist.lowercase()}|${track.title.lowercase()}"
                        cacheMutex.withLock { artworkCache[key] = artwork }
                    }
                } catch (_: Exception) { /* skip */ }
            }
        }.awaitAll()

        tracks.map { track ->
            if (track.artworkUrl != null) return@map track
            val key = "${track.artist.lowercase()}|${track.title.lowercase()}"
            val enrichedUrl = artworkCache[key]
            if (enrichedUrl != null) track.copy(artworkUrl = enrichedUrl) else track
        }
    }

    private suspend fun enrichArtistImages(artists: List<HistoryArtist>): List<HistoryArtist> = coroutineScope {
        val imageCache = mutableMapOf<String, String>()
        val cacheMutex = Mutex()
        artists.filter { it.imageUrl == null }.take(15).map { artist ->
            async {
                try {
                    val info = metadataService.getArtistInfo(artist.name)
                    val imageUrl = info?.imageUrl
                    if (imageUrl != null) {
                        cacheMutex.withLock { imageCache[artist.name.lowercase()] = imageUrl }
                    }
                } catch (_: Exception) { /* skip */ }
            }
        }.awaitAll()

        artists.map { artist ->
            if (artist.imageUrl != null) return@map artist
            val enrichedUrl = imageCache[artist.name.lowercase()]
            if (enrichedUrl != null) artist.copy(imageUrl = enrichedUrl) else artist
        }
    }
}
