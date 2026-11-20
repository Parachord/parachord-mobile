package com.parachord.shared.repository

import com.parachord.shared.model.Friend
import com.parachord.shared.platform.Log
import com.parachord.shared.platform.currentTimeMillis
import com.parachord.shared.plugin.PluginManager
import com.parachord.shared.plugin.needsAccount
import com.parachord.shared.recommendations.FriendExclusions
import com.parachord.shared.recommendations.SimilarUser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** A plugin that can name people who listen like you and that people can be followed on. */
data class SimilarUsersProvider(
    val id: String,
    val name: String,
    val isMetaService: Boolean = false,
    val needsAccount: Boolean = false,
)

/** Where similar users come from. The real one is [PluginSimilarUsersSource]; tests supply their own. */
interface SimilarUsersSource {
    /** Loaded plugins that declare both `similarUsers` and `friends`, on or not. */
    suspend fun providers(): List<SimilarUsersProvider>

    /** One provider's raw `getSimilarUsers(config)` answer (a JSON array). Throws when it couldn't answer. */
    suspend fun fetch(providerId: String, configJson: String): String
}

/**
 * The plugins with `capabilities.similarUsers` and `capabilities.friends`
 * (desktop's `similarUsersProviders`: Follow looks the person up on the
 * provider's id, so a provider for a service nobody can follow on is left out).
 */
class PluginSimilarUsersSource(private val pluginManager: PluginManager) : SimilarUsersSource {
    override suspend fun providers(): List<SimilarUsersProvider> {
        val infos = pluginManager.plugins.value.associateBy { it.id }
        return pluginManager.similarUsersResolvers().mapNotNull { (id, name) ->
            val info = infos[id] ?: return@mapNotNull null
            if (info.capabilities["friends"] != true) return@mapNotNull null
            SimilarUsersProvider(id, name, isMetaService = info.type == "meta-service", needsAccount = needsAccount(info))
        }
    }

    override suspend fun fetch(providerId: String, configJson: String): String =
        pluginManager.getSimilarUsers(providerId, configJson)
}

/**
 * Listeners' people, per similar-users service: a port of desktop's
 * `fetchSimilarUsers` (`app.js` ~L27963) and its cache.
 *
 * - **Providers:** [SimilarUsersSource.providers] that [providerIsOn].
 * - **Not ready:** a config with no `username`, or with `credentialsPending`
 *   (Last.fm before its key is known), isn't asked: its keyless `[]` must not
 *   replace a good answer. The cached answer for that account still shows.
 * - **Asked with** the stored config plus `excludeUsernames`: the names on
 *   that service the user follows or removed.
 * - **Cache** (`similar_users_cache.json`): per provider `users`, `fetchedAt`,
 *   `account`, `shape`. Fresh for [FRESH_MS] when the shape is [SHAPE] and the
 *   account matches; an empty answer is fresh too. An older shape is shown
 *   ([cached]) and fetched again. A failure stores nothing and keeps the last
 *   answer, even a stale one.
 * - **One fetch in flight** per provider and account, shared by callers. A
 *   fetch slower than [TIMEOUT_MS] returns the cached answer; its late
 *   answer is still stored.
 */
class SimilarUsersStore(
    private val source: SimilarUsersSource,
    /** Followed and removed friend keys, and the user's own accounts (`friendServices` is filled here). */
    private val friends: suspend () -> FriendExclusions,
    /** A provider's stored plugin config JSON; null when it has none. */
    private val configFor: suspend (String) -> String?,
    private val providerIsOn: suspend (SimilarUsersProvider) -> Boolean,
    private val load: suspend () -> String?,
    private val save: suspend (String) -> Unit,
    private val nowMs: () -> Long = { currentTimeMillis() },
    /** Runs late answers. The default logs an uncaught failure instead of crashing (SIGABRT on iOS). */
    private val scope: CoroutineScope = similarUsersBackgroundScope(),
) {
    companion object {
        private const val TAG = "SimilarUsersStore"
        const val SHAPE = 3
        const val FRESH_MS = 7L * 24 * 60 * 60 * 1000
        const val TIMEOUT_MS = 20_000L
    }

    @Serializable
    data class Entry(
        val users: List<SimilarUser> = emptyList(),
        val fetchedAt: Long = 0,
        val account: String = "",
        val shape: Int = 0,
    )

    @Serializable
    private data class Disk(val providers: Map<String, Entry> = emptyMap())

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val mutex = Mutex()
    private var loaded = false
    private val entries = LinkedHashMap<String, Entry>()
    private val inFlight = mutableMapOf<String, Deferred<Result<Entry>>>()

    /** The on providers' ids: the services the Listeners row may offer people from. */
    suspend fun friendServices(): Set<String> = onProviders().map { it.id }.toSet()

    /** What Listeners leaves out, with [FriendExclusions.friendServices] from the on providers. */
    suspend fun exclusions(): FriendExclusions = friends().copy(friendServices = friendServices())

    /** The saved people younger than [FRESH_MS] for each on provider's current account, any shape, without asking anyone. */
    suspend fun cached(): Map<String, List<SimilarUser>> {
        ensureLoaded()
        val out = LinkedHashMap<String, List<SimilarUser>>()
        for (provider in onProviders()) {
            val account = accountOf(safeConfig(provider.id)) ?: continue
            val entry = mutex.withLock { entries[provider.id] }?.takeIf { it.account == account } ?: continue
            if (nowMs() - entry.fetchedAt < FRESH_MS) out[provider.id] = entry.users
        }
        return out
    }

    /** Each on provider's people (rules above); [force] asks even when the cache is fresh. */
    suspend fun usersFor(force: Boolean): Map<String, List<SimilarUser>> {
        ensureLoaded()
        val providers = onProviders()
        val exclude = try {
            friends()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            FriendExclusions()
        }
        val answers = coroutineScope {
            providers.map { provider -> async { provider.id to usersOf(provider, force, exclude) } }.awaitAll()
        }
        val out = LinkedHashMap<String, List<SimilarUser>>()
        for ((id, users) in answers) if (users != null) out[id] = users
        return out
    }

    private suspend fun usersOf(provider: SimilarUsersProvider, force: Boolean, exclude: FriendExclusions): List<SimilarUser>? {
        val config = safeConfig(provider.id)
        val account = accountOf(config)
        val cached = mutex.withLock { entries[provider.id] }?.takeIf { account != null && it.account == account }
        if (config == null || account == null || isPending(config)) return cached?.users
        val young = cached != null && cached.shape == SHAPE && nowMs() - cached.fetchedAt < FRESH_MS
        if (!force && young) return cached!!.users
        val request = mutex.withLock {
            val key = "${provider.id}\u0000$account"
            inFlight[key]?.takeIf { it.isActive } ?: scope.async {
                try {
                    // A Result, so a failure stays with the callers (the scope
                    // never sees it) and a late one is simply dropped.
                    try {
                        Result.success(fetchAndStore(provider, config, account, exclude))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Result.failure(e)
                    }
                } finally {
                    mutex.withLock { if (inFlight[key] === coroutineContext[kotlinx.coroutines.Job]) inFlight.remove(key) }
                }
            }.also { inFlight[key] = it }
        }
        return try {
            (withTimeoutOrNull(TIMEOUT_MS) { request.await() } ?: return cached?.users).getOrThrow().users
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The message only: the config can carry an API key.
            Log.w(TAG, "Similar users from ${provider.name} failed: ${e.message}")
            cached?.users
        }
    }

    private suspend fun fetchAndStore(provider: SimilarUsersProvider, config: JsonObject, account: String, exclude: FriendExclusions): Entry {
        val prefix = "${provider.id}:"
        val names = (exclude.friendKeys.filter { it.startsWith(prefix) }.map { it.removePrefix(prefix) }).distinct()
        val request = JsonObject(config + ("excludeUsernames" to JsonArray(names.map { JsonPrimitive(it) })))
        val raw = source.fetch(provider.id, request.toString())
        val entry = Entry(parseSimilarUsers(raw), nowMs(), account, SHAPE)
        mutex.withLock { entries[provider.id] = entry }
        persist()
        return entry
    }

    private suspend fun onProviders(): List<SimilarUsersProvider> = try {
        source.providers().filter { providerIsOn(it) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "Similar-users providers unavailable: ${e.message}")
        emptyList()
    }

    private suspend fun safeConfig(id: String): JsonObject? = try {
        configFor(id)?.let { Json.parseToJsonElement(it).jsonObject }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    private fun accountOf(config: JsonObject?): String? =
        (config?.get("username") as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

    private fun isPending(config: JsonObject): Boolean =
        (config["credentialsPending"] as? JsonPrimitive)?.booleanOrNull == true

    private suspend fun ensureLoaded() {
        if (mutex.withLock { loaded }) return
        val saved = try {
            load()?.let { json.decodeFromString(Disk.serializer(), it) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Similar users cache unreadable: ${e.message}")
            null
        }
        mutex.withLock {
            if (loaded) return
            loaded = true
            saved?.providers?.forEach { (id, entry) -> if (id !in entries) entries[id] = entry }
        }
    }

    private suspend fun persist() {
        val snapshot = mutex.withLock { Disk(LinkedHashMap(entries)) }
        try {
            save(json.encodeToString(Disk.serializer(), snapshot))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Similar users cache not saved: ${e.message}")
        }
    }
}

/** A `getSimilarUsers` answer: `[{ username, similarity?, avatarUrl?, topArtists? }]`; anything else is skipped. */
fun parseSimilarUsers(raw: String): List<SimilarUser> {
    val array = Json.parseToJsonElement(raw).jsonArray
    return array.mapNotNull { element ->
        val obj = element as? JsonObject ?: return@mapNotNull null
        val username = (obj["username"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
            ?: return@mapNotNull null
        SimilarUser(
            username = username,
            similarity = (obj["similarity"] as? JsonPrimitive)?.doubleOrNull,
            avatarUrl = (obj["avatarUrl"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() },
            topArtists = (obj["topArtists"] as? JsonArray)?.mapNotNull { a ->
                when (a) {
                    is JsonPrimitive -> a.contentOrNull
                    is JsonObject -> (a["name"] as? JsonPrimitive)?.contentOrNull
                    else -> null
                }?.takeIf { it.isNotBlank() }
            }.orEmpty(),
        )
    }
}

/**
 * Who Listeners leaves out (desktop's `recommendationExclusions`): people the
 * user follows and people they removed ([deletedFriendKeys], already
 * `service:username` keys), and the user's own account on each service
 * ([selfAccounts], service id to username). Keys are lowercased.
 */
fun friendExclusionsOf(
    friends: List<Friend>,
    deletedFriendKeys: Set<String>,
    selfAccounts: Map<String, String?>,
): FriendExclusions = FriendExclusions(
    friendKeys = friends.filter { !it.transient }.map { "${it.service}:${it.username.lowercase()}" }.toSet() +
        deletedFriendKeys.map { it.lowercase() },
    selfKeys = selfAccounts.mapNotNull { (service, user) ->
        user?.trim()?.takeIf { it.isNotEmpty() }?.let { "$service:${it.lowercase()}" }
    }.toSet(),
)

/**
 * A similar-users provider's config from the plugin configs
 * ([PluginManager.resolverConfigProvider]): Last.fm's says
 * `credentialsPending` while it has no API key (desktop's
 * `getResolverConfig`), since its `getSimilarUsers` answers `[]` without one.
 */
fun similarUsersConfig(id: String, configs: Map<String, String>?): String? {
    val raw = configs?.get(id) ?: return null
    if (id != "lastfm") return raw
    val obj = try {
        Json.parseToJsonElement(raw).jsonObject
    } catch (e: Exception) {
        return raw
    }
    val key = (obj["apiKey"] as? JsonPrimitive)?.contentOrNull
    return if (key.isNullOrBlank()) JsonObject(obj + ("credentialsPending" to JsonPrimitive(true))).toString() else raw
}

/**
 * A handler-less scope would crash Android and abort the process on iOS on an
 * uncaught failure; this one logs it. A top-level function, not a lambda in
 * the default argument: Kotlin/Native 2.1.0 can't compile a local
 * `CoroutineExceptionHandler` class there.
 */
private fun similarUsersBackgroundScope(): CoroutineScope = CoroutineScope(
    SupervisorJob() + Dispatchers.Default +
        CoroutineExceptionHandler { _, e -> Log.e("SimilarUsersStore", "Similar users background work failed (ignored): ${e.message}", e) },
)
