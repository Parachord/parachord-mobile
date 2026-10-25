package com.parachord.shared.plugin

import com.parachord.shared.platform.Log
import com.parachord.shared.platform.currentTimeMillis
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private const val TAG = "PluginSync"

/**
 * Syncs .axe plugins from the parachord-plugins GitHub repository.
 *
 * Mirrors the desktop's `plugins-sync-marketplace` flow:
 * 1. Fetch manifest.json from GitHub (list of available plugins + versions)
 * 2. Compare each plugin's remote version against locally loaded version
 * 3. Download new/updated .axe files to plugin cache
 * 4. Call [PluginManager.reloadPlugins] to hot-load new versions
 *
 * Runs on app start (debounced to once per 24h) and on manual trigger.
 */
class PluginSyncService constructor(
    private val httpClient: HttpClient,
    private val pluginManager: PluginManager,
    private val fileAccess: PluginFileAccess,
    private val getLastSyncTimestamp: suspend () -> Long,
    private val setLastSyncTimestamp: suspend (Long) -> Unit,
) {
    companion object {
        private const val MANIFEST_URL =
            "https://raw.githubusercontent.com/Parachord/parachord-plugins/main/manifest.json"
        private const val PLUGINS_BASE_URL =
            "https://raw.githubusercontent.com/Parachord/parachord-plugins/main/"
        /** Minimum interval between automatic syncs (24 hours). */
        private const val SYNC_INTERVAL_MS = 24L * 60 * 60 * 1000
        /** Max manifest size: 1 MiB (security: M6). */
        private const val MAX_MANIFEST_SIZE = 1 * 1024 * 1024
        /** Max .axe plugin size: 5 MiB (security: M6). */
        private const val MAX_PLUGIN_SIZE = 5 * 1024 * 1024
    }

    data class SyncResult(
        val added: List<String> = emptyList(),
        val updated: List<String> = emptyList(),
        val failed: List<String> = emptyList(),
        val unchanged: Int = 0,
    ) {
        val hasChanges get() = added.isNotEmpty() || updated.isNotEmpty()
    }

    private val json = Json { ignoreUnknownKeys = true }

    /** Plain-identifier pattern for plugin IDs. Matches the filesystem allowlist. */
    private val safePluginIdRegex = Regex("^[A-Za-z0-9_.-]+$")

    /**
     * Sync plugins from the marketplace if enough time has passed since last sync.
     * Returns null if sync was skipped (too recent).
     */
    suspend fun syncIfNeeded(): SyncResult? {
        val lastSync = getLastSyncTimestamp()
        val now = currentTimeMillis()
        if (now - lastSync < SYNC_INTERVAL_MS) {
            Log.d(TAG, "Skipping sync — last sync was ${(now - lastSync) / 1000 / 60}min ago")
            return null
        }
        return sync()
    }

    /** Force sync regardless of last sync time. */
    suspend fun sync(): SyncResult {
        Log.d(TAG, "Starting plugin sync from marketplace...")

        val manifest = try {
            fetchManifest()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch plugin manifest: ${e.message}", e)
            return SyncResult(failed = listOf("manifest"))
        }

        // Compare against ALL parsed plugins (including platform-filtered ones
        // like youtube/ollama with mobile: false) so we don't re-download them
        // on every sync. Using pluginManager.plugins (filtered) would see them
        // as missing every time and keep re-fetching.
        val localPlugins = pluginManager.allLoadedPlugins.value.associateBy { it.id }

        val added = mutableListOf<String>()
        val updated = mutableListOf<String>()
        val failed = mutableListOf<String>()
        var unchanged = 0

        for (remote in manifest.plugins) {
            val local = localPlugins[remote.id]
            val localVersion = local?.version ?: "0.0.0"
            val remoteVersion = remote.version

            if (pluginManager.compareSemver(remoteVersion, localVersion) <= 0) {
                unchanged++
                continue
            }

            // Reject unsafe plugin IDs before hitting the network. The filesystem
            // layer also validates, but failing here avoids a wasted HTTP request
            // and logs the rejection clearly. security: H2
            if (!safePluginIdRegex.matches(remote.id)) {
                Log.w(TAG, "Rejecting manifest entry with unsafe id: '${remote.id}'")
                failed.add(remote.id)
                continue
            }

            // Download updated plugin
            try {
                val axeContent = fetchPlugin(remote.id)
                fileAccess.writeCachedPlugin("${remote.id}.axe", axeContent)

                if (local == null) {
                    added.add(remote.id)
                    Log.d(TAG, "Downloaded new plugin: ${remote.id} v$remoteVersion")
                } else {
                    updated.add(remote.id)
                    Log.d(TAG, "Updated plugin: ${remote.id} v$localVersion → v$remoteVersion")
                }
            } catch (e: Exception) {
                failed.add(remote.id)
                Log.w(TAG, "Failed to download ${remote.id}: ${e.message}", e)
            }
        }

        val result = SyncResult(added, updated, failed, unchanged)

        if (result.hasChanges) {
            Log.d(TAG, "Sync complete: ${added.size} added, ${updated.size} updated, ${failed.size} failed, $unchanged unchanged")
            pluginManager.reloadPlugins()
        } else {
            Log.d(TAG, "Sync complete: all plugins up to date ($unchanged unchanged)")
        }

        // Only a complete sync starts the 24h wait. If any download failed, the
        // next launch tries again instead of leaving it stale for a day.
        if (failed.isEmpty()) setLastSyncTimestamp(currentTimeMillis())

        return result
    }

    // ── Private helpers ──────────────────────────────────────────────

    private suspend fun fetchManifest(): PluginManifest {
        // security: L5 — runtime assertion that plugin URLs are HTTPS
        require(MANIFEST_URL.startsWith("https://")) { "Plugin manifest URL must be HTTPS" }
        val body = httpClient.get(MANIFEST_URL).successBody("manifest")
        // security: M6 — cap manifest size to prevent OOM from hostile CDN
        if (body.length > MAX_MANIFEST_SIZE) {
            throw Exception("Manifest too large (${body.length} bytes, max $MAX_MANIFEST_SIZE)")
        }
        return json.decodeFromString(body)
    }

    private suspend fun fetchPlugin(pluginId: String): String {
        val url = "$PLUGINS_BASE_URL$pluginId.axe"
        require(url.startsWith("https://")) { "Plugin download URL must be HTTPS" }
        val body = httpClient.get(url).successBody(pluginId)
        // security: M6 — cap .axe file size
        if (body.length > MAX_PLUGIN_SIZE) {
            throw Exception("Plugin '$pluginId' too large (${body.length} bytes, max $MAX_PLUGIN_SIZE)")
        }
        return body
    }

    /**
     * The body of a 2xx response, else throw. The shared client doesn't throw on
     * HTTP errors, so without this a 404/429/5xx error page was written over the
     * cached `.axe` as if it were the plugin.
     */
    private suspend fun HttpResponse.successBody(what: String): String {
        if (!status.isSuccess()) throw Exception("Fetching $what failed: HTTP ${status.value}")
        return bodyAsText()
    }

    // ── Manifest model ───────────────────────────────────────────────

    @Serializable
    private data class PluginManifest(
        val version: String = "1.0.0",
        val plugins: List<PluginEntry> = emptyList(),
    )

    @Serializable
    private data class PluginEntry(
        val id: String,
        val name: String = "",
        val version: String = "0.0.0",
        val type: String = "",
        val description: String = "",
    )
}
