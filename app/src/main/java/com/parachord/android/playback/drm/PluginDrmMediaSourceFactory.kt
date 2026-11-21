package com.parachord.android.playback.drm

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.dash.manifest.DashManifestParser
import androidx.media3.exoplayer.drm.DefaultDrmSessionManager
import androidx.media3.exoplayer.drm.DrmSessionManager
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.drm.FrameworkMediaDrm
import androidx.media3.exoplayer.drm.HttpMediaDrmCallback
import androidx.media3.exoplayer.drm.MediaDrmCallback
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import com.parachord.shared.platform.Log
import com.parachord.shared.playback.drm.CodecFamilies

/**
 * ExoPlayer's media source factory: a `parachord-drm://<sessionId>` item
 * becomes a DASH source over the session's in-memory manifest (segment URLs
 * as the manifest gives them), with a Widevine session manager whose licences
 * go through the plugin ([PluginDrmLicenseCallback]). Every other item goes
 * to [fallback], unchanged.
 */
@OptIn(UnstableApi::class)
class PluginDrmMediaSourceFactory(
    private val fallback: MediaSource.Factory,
    private val sessions: PluginDrmSessions,
    private val dataSourceFactory: DataSource.Factory,
    private val drmSessionManagerFor: (PluginDrmSessions.Session) -> DrmSessionManager,
) : MediaSource.Factory {

    private var loadErrorPolicy: LoadErrorHandlingPolicy? = null

    override fun setDrmSessionManagerProvider(drmSessionManagerProvider: DrmSessionManagerProvider): MediaSource.Factory {
        fallback.setDrmSessionManagerProvider(drmSessionManagerProvider)
        return this
    }

    override fun setLoadErrorHandlingPolicy(loadErrorHandlingPolicy: LoadErrorHandlingPolicy): MediaSource.Factory {
        fallback.setLoadErrorHandlingPolicy(loadErrorHandlingPolicy)
        loadErrorPolicy = loadErrorHandlingPolicy
        return this
    }

    override fun getSupportedTypes(): IntArray =
        (fallback.supportedTypes.toSet() + C.CONTENT_TYPE_DASH).toIntArray()

    override fun createMediaSource(mediaItem: MediaItem): MediaSource {
        val uri = mediaItem.localConfiguration?.uri
        val sessionId = PluginDrmUris.sessionIdOf(uri?.toString()) ?: return fallback.createMediaSource(mediaItem)
        val session = sessions.get(sessionId)
        if (session == null || uri == null) {
            // Closed already (a stale item): the fallback can't load the scheme,
            // so the play fails as a player error instead of crashing here.
            Log.w(DRM_TAG, "No open session for $sessionId")
            return fallback.createMediaSource(mediaItem)
        }
        val manifest = try {
            DashManifestParser().parse(uri, session.manifest.byteInputStream())
        } catch (e: Exception) {
            Log.w(DRM_TAG, "Session $sessionId: the manifest didn't parse (${e.javaClass.simpleName})")
            return fallback.createMediaSource(mediaItem)
        }
        logRoster(session, manifest)
        val drm = drmSessionManagerFor(session)
        val factory = DashMediaSource.Factory(dataSourceFactory)
            .setDrmSessionManagerProvider { drm }
        loadErrorPolicy?.let { factory.setLoadErrorHandlingPolicy(it) }
        return factory.createMediaSource(manifest, mediaItem)
    }

    /** The debug aid: variant counts per codec family, never the manifest or its URLs. */
    private fun logRoster(session: PluginDrmSessions.Session, manifest: androidx.media3.exoplayer.dash.manifest.DashManifest) {
        val families = mutableMapOf<String, Int>()
        for (p in 0 until manifest.periodCount) {
            for (set in manifest.getPeriod(p).adaptationSets) {
                if (set.type != C.TRACK_TYPE_AUDIO) continue
                for (rep in set.representations) {
                    val family = CodecFamilies.of(rep.format.codecs) ?: "?"
                    families[family] = (families[family] ?: 0) + 1
                }
            }
        }
        Log.d(
            DRM_TAG,
            "Session ${session.id} (${session.resolverId}): manifest ${session.manifest.length} chars, " +
                "audio variants ${families.entries.joinToString { "${it.key}=${it.value}" }}",
        )
    }

    companion object {
        /**
         * The factory ExoPlayer uses: the default for everything else, and for
         * plugin DRM items a Widevine session manager per session, provisioning
         * through the standard Widevine POST. [userAgent] is the app's.
         */
        fun create(context: Context, sessions: PluginDrmSessions, js: PluginDrmJs, userAgent: String): PluginDrmMediaSourceFactory {
            val http = DefaultHttpDataSource.Factory().setUserAgent(userAgent)
            val provisioning: MediaDrmCallback = HttpMediaDrmCallback(null, http)
            return PluginDrmMediaSourceFactory(
                fallback = DefaultMediaSourceFactory(context),
                sessions = sessions,
                dataSourceFactory = http,
                drmSessionManagerFor = { session ->
                    val manager = DefaultDrmSessionManager.Builder()
                        .setUuidAndExoMediaDrmProvider(C.WIDEVINE_UUID, FrameworkMediaDrm.DEFAULT_PROVIDER)
                        // One key session per rendition group: the manifest
                        // carries a key group per codec family.
                        .setMultiSession(true)
                        .build(PluginDrmLicenseCallback(session.id, sessions, js, provisioning))
                    FamilyRecordingDrmSessionManager(manager) { family -> session.requestedFamily = family }
                },
            )
        }
    }
}
