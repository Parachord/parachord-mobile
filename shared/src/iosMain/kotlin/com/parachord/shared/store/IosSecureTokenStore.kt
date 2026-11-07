package com.parachord.shared.store

import com.parachord.shared.platform.Log
import com.russhwolf.settings.KeychainSettings
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import platform.CoreFoundation.CFDictionaryRef
import platform.Foundation.NSNotificationCenter
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationProtectedDataDidBecomeAvailable
import platform.Security.kSecAttrAccessibleAfterFirstUnlock
import platform.Security.kSecAttrAccessibleWhenPasscodeSetThisDeviceOnly
import platform.Security.kSecAttrAccessibleWhenUnlocked
import platform.Security.kSecAttrAccessibleWhenUnlockedThisDeviceOnly
import kotlin.concurrent.Volatile
import kotlinx.cinterop.value
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.CFDictionaryAddValue
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFDictionaryGetValue
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFRetain
import platform.CoreFoundation.kCFBooleanTrue
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import platform.Foundation.CFBridgingRelease
import platform.Foundation.CFBridgingRetain
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemUpdate
import platform.Security.errSecSuccess
import platform.Security.kSecAttrAccessible
import platform.Security.kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecReturnAttributes
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart

/**
 * iOS implementation of [SecureTokenStore] backed by the Keychain via
 * `multiplatform-settings`' [KeychainSettings].
 *
 * The Keychain entries are scoped under the service name
 * `"com.parachord.tokens"` — distinct from the Android
 * `parachord_secure_tokens` SharedPreferences file (Keychain has its own
 * isolation by service+account). `KeychainSettings` sets no accessibility of
 * its own, which leaves iOS's default (`kSecAttrAccessibleWhenUnlocked`), so
 * a launch with the phone locked (CarPlay) couldn't read the tokens. New items
 * are therefore written with `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly`,
 * and [migrateAccessibility] moves items stored before that, once. The
 * accessibility is part of the wrapper's lookup query, so the migration must
 * run before the first read or write through [settings].
 *
 * The Keychain has no built-in change observer (unlike
 * `SharedPreferences.OnSharedPreferenceChangeListener` on Android), so
 * [observe] re-emits via a [MutableSharedFlow] that [set] / [remove] write
 * to. Subscribers see the current value immediately (via `onStart`), then
 * any subsequent updates filtered to their key.
 *
 * security: C4 — encrypt tokens at rest.
 */
@OptIn(ExperimentalForeignApi::class)
class IosSecureTokenStore(
    private val serviceName: String = "com.parachord.tokens",
    /** Once-only flag for [migrateAccessibility] (default = never done). */
    private val migrationDone: () -> Boolean = { false },
    private val markMigrationDone: () -> Unit = {},
) : SecureTokenStore {

    /**
     * No accessibility attribute, so its queries match items of ANY class.
     * Used while the migration is pending: reads still find old `ak` items and
     * writes update them in place instead of failing with errSecDuplicateItem.
     */
    private val legacySettings = KeychainSettings(service = serviceName)

    // The retained service string is held by the instance for the process
    // lifetime (this store is an app-lifetime singleton), so it isn't released.
    private val modernSettings = KeychainSettings(
        kSecAttrService to CFBridgingRetain(serviceName),
        kSecAttrAccessible to kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
    )

    @Volatile
    private var migrated = false

    /** The instance to use now: the new-accessibility one only once migrated. */
    private val settings: KeychainSettings
        get() = if (migrated || tryMigrate()) modernSettings else legacySettings

    init {
        migrated = migrationDone()
        if (!migrated) {
            tryMigrate()
            // Retry when the device unlocks (a CarPlay launch can precede it).
            NSNotificationCenter.defaultCenter.addObserverForName(
                name = UIApplicationProtectedDataDidBecomeAvailable,
                `object` = null,
                queue = null,
            ) { _ -> if (!migrated) tryMigrate() }
        }
    }

    private fun serviceQuery(accessible: platform.CoreFoundation.CFStringRef? = null): platform.CoreFoundation.CFMutableDictionaryRef? {
        val d = CFDictionaryCreateMutable(
            null, 0, kCFTypeDictionaryKeyCallBacks.ptr, kCFTypeDictionaryValueCallBacks.ptr,
        )
        CFDictionaryAddValue(d, kSecClass, kSecClassGenericPassword)
        val svc = CFBridgingRetain(serviceName)
        CFDictionaryAddValue(d, kSecAttrService, svc)
        // The dictionary retained it (kCFTypeDictionaryValueCallBacks).
        CFRelease(svc)
        if (accessible != null) CFDictionaryAddValue(d, kSecAttrAccessible, accessible)
        return d
    }

    /**
     * The `kSecAttrAccessible` of one item under the service (null = no items
     * or failure). Logs `KEYCHAIN_ACCESSIBILITY <value>`. Samples one item only.
     */
    fun currentAccessibility(): String? = try {
        val q = serviceQuery()
        CFDictionaryAddValue(q, kSecReturnAttributes, kCFBooleanTrue)
        CFDictionaryAddValue(q, kSecMatchLimit, kSecMatchLimitOne)
        val value: String? = memScoped {
            val out = alloc<CFTypeRefVar>()
            val status = SecItemCopyMatching(q, out.ptr)
            if (status != errSecSuccess) {
                Log.i(TAG, "KEYCHAIN_ACCESSIBILITY none (status=$status)")
                null
            } else {
                val attrs = out.value
                val dict: CFDictionaryRef? = attrs?.reinterpret()
                val acc = CFDictionaryGetValue(dict, kSecAttrAccessible)
                val str = if (acc == null) null else CFBridgingRelease(CFRetain(acc)) as? String
                CFRelease(attrs)
                Log.i(TAG, "KEYCHAIN_ACCESSIBILITY $str")
                str
            }
        }
        CFRelease(q)
        value
    } catch (e: Throwable) {
        Log.w(TAG, "currentAccessibility failed: ${e.message}")
        null
    }

    /** Status of looking for items under the service with one accessibility class. */
    private fun findWithAccessibility(accessible: platform.CoreFoundation.CFStringRef?): Int {
        val q = serviceQuery(accessible)
        CFDictionaryAddValue(q, kSecMatchLimit, kSecMatchLimitOne)
        val status = SecItemCopyMatching(q, null)
        CFRelease(q)
        return status
    }

    /** True only if no item under the service is known to carry another class. */
    private fun noOtherClassLeft(): Boolean = listOf(
        kSecAttrAccessibleWhenUnlocked,
        kSecAttrAccessibleAfterFirstUnlock,
        kSecAttrAccessibleWhenUnlockedThisDeviceOnly,
        kSecAttrAccessibleWhenPasscodeSetThisDeviceOnly,
    ).all { findWithAccessibility(it) == -25300 }

    private fun logTokenPresence(phase: String, from: KeychainSettings) {
        TOKEN_KEYS.forEach { Log.i(TAG, "KEYCHAIN_TOKEN_PRESENT $phase $it=${from.hasKey(it)}") }
    }

    /**
     * One `SecItemUpdate` over every item under the service, setting
     * `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly`. Marks the flag only
     * once done; "nothing found" counts only if protected data is available
     * (a locked device hides ak items) and no other class remains. Never
     * throws and never deletes; on failure it stays pending and retries.
     * @return true once migrated.
     */
    fun migrateAccessibility(): Boolean = tryMigrate()

    private fun tryMigrate(): Boolean {
        if (migrated) return true
        return try {
            logTokenPresence("before", legacySettings)
            currentAccessibility()
            val q = serviceQuery()
            val upd = CFDictionaryCreateMutable(
                null, 0, kCFTypeDictionaryKeyCallBacks.ptr, kCFTypeDictionaryValueCallBacks.ptr,
            )
            CFDictionaryAddValue(upd, kSecAttrAccessible, kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly)
            val status = SecItemUpdate(q, upd)
            CFRelease(q)
            CFRelease(upd)
            val ok = when (status) {
                errSecSuccess -> true
                // Nothing stored OR hidden by a locked device: only trust it
                // when protected data is available and nothing of another class remains.
                -25300 -> UIApplication.sharedApplication.protectedDataAvailable && noOtherClassLeft()
                else -> false
            }
            if (ok) {
                migrated = true
                markMigrationDone()
                Log.i(TAG, "keychain accessibility migration done (status=$status)")
                logTokenPresence("after", modernSettings)
            } else {
                Log.w(TAG, "keychain accessibility migration pending (status=$status); using legacy lookups")
            }
            currentAccessibility()
            ok
        } catch (e: Throwable) {
            Log.w(TAG, "keychain accessibility migration threw: ${e.message}")
            false
        }
    }

    /**
     * Per-key change signal. Replay = 0 — `observe` uses [onStart] to
     * deliver the current value on subscription, separate from the change
     * stream. Buffer = 64 to absorb bursts (e.g. token refresh writing
     * access + refresh + expiry simultaneously).
     */
    private val changes = MutableSharedFlow<String>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    override fun get(key: String): String? = settings.getStringOrNull(key)

    override fun set(key: String, value: String) {
        settings.putString(key, value)
        changes.tryEmit(key)
    }

    override fun remove(key: String) {
        settings.remove(key)
        changes.tryEmit(key)
    }

    override fun contains(key: String): Boolean = settings.hasKey(key)

    override fun observe(key: String): Flow<String?> =
        changes
            .filter { it == key }
            .map { get(key) }
            .onStart { emit(get(key)) }
            .distinctUntilChanged()

    private companion object {
        const val TAG = "IosSecureTokenStore"
        // Token key names (never values) logged once around the migration.
        val TOKEN_KEYS = listOf(
            "spotify_access_token", "spotify_refresh_token", "soundcloud_access_token",
            "soundcloud_refresh_token", "listenbrainz_token", "lastfm_session_key",
            "librefm_session_key", "apple_music_user_token", "discogs_personal_token",
        )
    }
}
