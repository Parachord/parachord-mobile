package com.parachord.shared.ios

import com.parachord.shared.api.auth.AuthCredential
import com.parachord.shared.api.auth.AuthRealm
import com.parachord.shared.api.auth.AuthTokenProvider
import com.parachord.shared.settings.SettingsStore

/**
 * iOS Apple Music **library** auth (#257). Serves [AuthRealm.AppleMusicLibrary]
 * with a [AuthCredential.BearerWithMUT] = the ES256 developer token (from
 * `AppConfig`/plist) + the Music User Token persisted by the Swift MUT
 * acquisition (`SKCloudServiceController.requestUserToken`).
 *
 * Mirrors `AndroidAuthTokenProvider`'s AppleMusicLibrary branch. Returns null
 * when the MUT is absent or the dev token is blank — `AppleMusicLibraryClient`
 * then throws `AppleMusicReauthRequiredException`, which the SyncEngine handles
 * as a per-provider skip (no hard failure). Distinct from
 * [SpotifyAuthTokenProvider] (Spotify realm); the Apple Music library client
 * takes its OWN AuthTokenProvider, so this doesn't touch the HttpClient's
 * global (Spotify) auth.
 */
class IosAppleMusicAuthProvider(
    private val settingsStore: SettingsStore,
    private val developerToken: String,
) : AuthTokenProvider {

    override suspend fun tokenFor(realm: AuthRealm): AuthCredential? {
        if (realm != AuthRealm.AppleMusicLibrary) return null
        if (developerToken.isBlank()) return null
        val mut = settingsStore.getAppleMusicUserToken()?.takeIf { it.isNotBlank() } ?: return null
        return AuthCredential.BearerWithMUT(developerToken, mut)
    }

    override suspend fun invalidate(realm: AuthRealm) {
        if (realm == AuthRealm.AppleMusicLibrary) settingsStore.clearAppleMusicUserToken()
    }
}
