package com.parachord.shared.sync

/**
 * Which sync provider a playlist's id says it came from.
 *
 * A playlist a provider's sync pulled in is saved as `<provider>-<remote id>`
 * (`spotify-37i9…`, `applemusic-p.…`, `listenbrainz-<mbid>`), and sync code
 * reads the pull source and its remote id off that prefix.
 *
 * A playlist imported from a link (`PlaylistUrlImporter`) is saved as
 * `<provider>-import-<uuid>`, and a saved recommendation as
 * `<provider>-import-rec-<hash>`. Those are local snapshots: no provider owns
 * them and nothing pulls them. Reading their prefix as a provider wrote a pull
 * source with remote id `import-<uuid>`, showed a phantom mirror chip, offered
 * "delete from Spotify" for an id Spotify doesn't have, and made the bogus
 * source authoritative in the N-way reconcile. So every "which provider does
 * this id imply" question goes through [impliedPlaylistProvider].
 */
val PLAYLIST_ID_PROVIDERS: List<String> = listOf(
    SpotifySyncProvider.PROVIDER_ID,
    AppleMusicSyncProvider.PROVIDER_ID,
    ListenBrainzSyncProvider.PROVIDER_ID,
)

private const val LINK_IMPORT_MARKER = "-import-"

/** Whether [id] is a playlist imported from a link: `<word>-import-…`. */
fun isLinkImportedPlaylistId(id: String): Boolean {
    val dash = id.indexOf('-')
    return dash > 0 && id.startsWith(LINK_IMPORT_MARKER, dash)
}

/** The service a link-imported playlist was imported from (`spotify-import-…` → `spotify`), else null. */
fun linkImportOrigin(id: String): String? =
    if (isLinkImportedPlaylistId(id)) id.substringBefore('-') else null

/**
 * The provider that owns a playlist with this id, from its prefix, or null for
 * a local, hosted or link-imported playlist.
 */
fun impliedPlaylistProvider(id: String): String? {
    if (isLinkImportedPlaylistId(id)) return null
    return PLAYLIST_ID_PROVIDERS.firstOrNull { id.startsWith("$it-") }
}

/** The remote id after the provider prefix, when [impliedPlaylistProvider] names one. */
fun impliedPlaylistExternalId(id: String): String? =
    impliedPlaylistProvider(id)?.let { id.removePrefix("$it-") }
