package com.parachord.shared.store

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart

private const val TAG = "SecureTokenStore"
private const val FILENAME = "parachord_secure_tokens"

/**
 * Android implementation of [SecureTokenStore] backed by Jetpack
 * `EncryptedSharedPreferences` (AES-256-GCM via Android Keystore).
 *
 * If Keystore initialization fails (some devices, rooted, unlocked
 * bootloaders), falls back to an unencrypted `SharedPreferences` with a
 * logged warning — better to have functioning auth than to block the
 * user entirely.
 *
 * security: C4 — encrypt tokens at rest.
 */
class AndroidSecureTokenStore(context: Context) : SecureTokenStore {

    private val prefs: SharedPreferences = try {
        val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
        EncryptedSharedPreferences.create(
            FILENAME,
            masterKeyAlias,
            context,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    } catch (e: Exception) {
        Log.e(TAG, "EncryptedSharedPreferences init failed — falling back to unencrypted", e)
        context.getSharedPreferences("${FILENAME}_fallback", Context.MODE_PRIVATE)
    }

    // Internal change bus. EncryptedSharedPreferences ENCRYPTS its keys
    // (AES256_SIV), so `OnSharedPreferenceChangeListener` fires with the
    // ENCRYPTED key — a `changedKey == key` comparison never matches, so a
    // prefs-listener-based observe() never re-emits and is effectively one-shot.
    // That made in-place saves invisible to the UI (the BYO Spotify Client ID
    // saved but the Connect button stayed disabled — #363). We instead emit here
    // on every write, which every mutation goes through, so observe() is reliably
    // reactive regardless of the key-encryption scheme.
    private val keyChanges = MutableSharedFlow<String>(extraBufferCapacity = 64)

    override fun get(key: String): String? = prefs.getString(key, null)

    override fun set(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
        keyChanges.tryEmit(key)
    }

    override fun remove(key: String) {
        prefs.edit().remove(key).apply()
        keyChanges.tryEmit(key)
    }

    override fun contains(key: String): Boolean = prefs.contains(key)

    override fun observe(key: String): Flow<String?> =
        keyChanges
            .filter { it == key }
            .map { get(key) }
            .onStart { emit(get(key)) }
            .distinctUntilChanged()
}
