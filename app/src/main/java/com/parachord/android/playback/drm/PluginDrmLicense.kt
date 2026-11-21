package com.parachord.android.playback.drm

import androidx.annotation.OptIn
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.drm.DrmSession
import androidx.media3.exoplayer.drm.DrmSessionEventListener
import androidx.media3.exoplayer.drm.DrmSessionManager
import androidx.media3.exoplayer.drm.ExoMediaDrm
import androidx.media3.exoplayer.drm.MediaDrmCallback
import com.parachord.shared.platform.Log
import com.parachord.shared.playback.drm.CodecFamilies
import com.parachord.shared.playback.drm.PluginDrmContract
import com.parachord.shared.playback.drm.PluginDrmError
import com.parachord.shared.playback.drm.PluginDrmLicenseReply
import com.parachord.shared.playback.drm.PluginDrmReplies
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/**
 * A licence the plugin refused (its `relayLicense` threw): the contract's
 * fields ride [error]. ExoPlayer wraps it in the DRM PlaybackException.
 */
class PluginDrmLicenseException(val error: PluginDrmError) :
    Exception("Plugin licence refused (${error.reason}${error.denialReason?.let { ", $it" } ?: ""})")

/**
 * Widevine key requests for one plugin DRM session: the challenge goes to the
 * plugin's `relayLicense` as base64 (with the session's context, which stays
 * in JS), and its base64 licence comes back as bytes. Provisioning (the
 * device certificate) is the standard Widevine POST ([provisioning]).
 *
 * ExoPlayer calls this on its DRM thread, which may block; the relay itself
 * runs on the main thread (the WebView), so it never waits on this one.
 */
@OptIn(UnstableApi::class)
class PluginDrmLicenseCallback(
    private val sessionId: String,
    private val sessions: PluginDrmSessions,
    private val js: PluginDrmJs,
    private val provisioning: MediaDrmCallback?,
    private val budgetMs: Long = PluginDrmContract.LICENSE_BUDGET_MS,
) : MediaDrmCallback {

    override fun executeProvisionRequest(uuid: UUID, request: ExoMediaDrm.ProvisionRequest): ByteArray {
        Log.d(DRM_TAG, "Provisioning request for $sessionId")
        val delegate = provisioning ?: throw IllegalStateException("No provisioning callback")
        return delegate.executeProvisionRequest(uuid, request)
    }

    override fun executeKeyRequest(uuid: UUID, request: ExoMediaDrm.KeyRequest): ByteArray =
        licenseFor(request.data)

    /** One round trip; public for tests (a KeyRequest needs the framework). */
    fun licenseFor(challenge: ByteArray): ByteArray {
        val challengeB64 = java.util.Base64.getEncoder().encodeToString(challenge)
        val raw = runBlocking {
            // A little past the JS budget, so the plugin's own timeout answers first.
            withTimeoutOrNull(budgetMs + 5_000) { js.license(sessionId, challengeB64, budgetMs) }
        }
        val family = sessions.get(sessionId)?.requestedFamily
        return when (val reply = PluginDrmReplies.license(raw)) {
            is PluginDrmLicenseReply.Licensed -> {
                val bytes = try {
                    java.util.Base64.getDecoder().decode(reply.licenseB64)
                } catch (e: IllegalArgumentException) {
                    fail(PluginDrmError(message = "the licence wasn't base64"), family)
                }
                Log.d(DRM_TAG, "Licence ok for $sessionId (family ${family ?: "?"})")
                sessions.recordLicenseSuccess(sessionId)
                bytes
            }
            is PluginDrmLicenseReply.Failed -> fail(reply.error, family)
        }
    }

    private fun fail(error: PluginDrmError, family: String?): Nothing {
        Log.w(
            DRM_TAG,
            "Licence refused for $sessionId: reason=${error.reason} licenseDenied=${error.licenseDenied} " +
                "denialReason=${error.denialReason} family=${family ?: "?"}",
        )
        sessions.recordLicenseFailure(sessionId, error)
        throw PluginDrmLicenseException(error)
    }
}

/**
 * A [DrmSessionManager] that notes which codec family each session is for
 * before delegating: the CDM's challenge is opaque, so a refused licence is
 * attributed to the family whose format asked for it (desktop reads the
 * active variant when the request fires).
 */
@OptIn(UnstableApi::class)
class FamilyRecordingDrmSessionManager(
    private val delegate: DrmSessionManager,
    private val onFormat: (String?) -> Unit,
) : DrmSessionManager {
    override fun prepare() = delegate.prepare()
    override fun release() = delegate.release()
    override fun setPlayer(playbackLooper: android.os.Looper, playerId: PlayerId) = delegate.setPlayer(playbackLooper, playerId)

    override fun preacquireSession(
        eventDispatcher: DrmSessionEventListener.EventDispatcher?,
        format: Format,
    ): DrmSessionManager.DrmSessionReference {
        if (format.drmInitData != null) onFormat(CodecFamilies.of(format.codecs))
        return delegate.preacquireSession(eventDispatcher, format)
    }

    override fun acquireSession(eventDispatcher: DrmSessionEventListener.EventDispatcher?, format: Format): DrmSession? {
        if (format.drmInitData != null) onFormat(CodecFamilies.of(format.codecs))
        return delegate.acquireSession(eventDispatcher, format)
    }

    override fun getCryptoType(format: Format): Int = delegate.getCryptoType(format)
}
