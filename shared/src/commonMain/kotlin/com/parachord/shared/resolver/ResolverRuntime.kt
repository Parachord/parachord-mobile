package com.parachord.shared.resolver

/**
 * Platform-specific resolver execution behind a single interface (#210, D2).
 *
 * The shared [ResolverCoordinator] owns the fan-out, the native **Spotify**
 * branch (injected as a lambda), gating, re-score, and ranking — all
 * byte-for-byte identical across platforms. What genuinely differs per platform
 * is (a) which non-Spotify native resolvers exist and how they resolve, and
 * (b) how `.axe` plugins execute. Both live here:
 *
 * - **Android** ([com.parachord.android.resolver] runtime impl): natives are
 *   `{applemusic, soundcloud, localfiles}` (Apple Music via MusicKit + iTunes,
 *   SoundCloud via the API, local files via the DAO); `.axe` via
 *   `PluginManager.resolve`.
 * - **iOS** (`IosResolverRuntime`): the only native is `{applemusic}` (catalog
 *   HTTP); `.axe` via the JavaScriptCore unique-key polling workaround.
 *
 * Both AM mechanisms already pick their best title+artist match via
 * [selectBestMatch], so neither blindly returns the first catalog hit.
 */
interface ResolverRuntime {
    /**
     * Native resolver ids this platform resolves itself, **EXCLUDING spotify**
     * (the coordinator owns the Spotify branch via its injected lambda).
     * Android: `{applemusic, soundcloud, localfiles}`; iOS: `{applemusic}`.
     */
    val nativeResolverIds: Set<String>

    /**
     * Resolve one native id. Returns null on no-match or not-available
     * (token absent, plugin not configured, etc.). The coordinator only calls
     * this for ids in [nativeResolverIds] that pass the active/exclude gate.
     */
    suspend fun resolveNative(
        resolverId: String,
        query: String,
        targetTitle: String?,
        targetArtist: String?,
        album: String?,
    ): ResolvedSource?

    /**
     * [resolveNative] with the target's recording identity (#402): its ISRC and
     * MusicBrainz recording MBID, either may be null. A runtime that picks among
     * several search hits (Apple Music) passes these to [selectBestMatch] so an
     * exact-identity hit wins before any name comparison, as desktop's
     * `findBestMatch` does. The coordinator always calls this overload; the
     * default ignores the identity, so runtimes and fakes that don't use it need
     * not override.
     */
    suspend fun resolveNative(
        resolverId: String,
        query: String,
        targetTitle: String?,
        targetArtist: String?,
        album: String?,
        targetIsrc: String?,
        targetMbid: String?,
    ): ResolvedSource? = resolveNative(resolverId, query, targetTitle, targetArtist, album)

    /**
     * Resolve one `.axe` id (Android: `PluginManager.resolve`; iOS: JSC
     * unique-key polling). Returns null on no-match. The coordinator only calls
     * this for ids returned by [ResolverGating.axeResolverIds] (minus excludes).
     *
     * [album] is forwarded to the plugin's `resolve(artist, title, album, …)`.
     * iOS passes it through (its `.axe` resolvers use it for disambiguation);
     * Android's impl passes `null` (parity with its pre-#210 `resolveViaPlugin`,
     * which always passed `null`) — each platform preserves its prior behavior.
     */
    suspend fun resolveAxe(
        resolverId: String,
        query: String,
        targetTitle: String?,
        targetArtist: String?,
        album: String?,
    ): ResolvedSource?
}
