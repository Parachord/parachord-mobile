package com.parachord.shared.store

import com.parachord.shared.platform.Log
import com.russhwolf.settings.KeychainSettings
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.CFDictionaryAddValue
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFDictionaryGetValue
import platform.CoreFoundation.CFDictionaryRef
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
    /** Once-only flag for [migrateAccessibility] (null = always attempt). */
    private val migrationDone: () -> Boolean = { false },
    private val markMigrationDone: () -> Unit = {},
) : SecureTokenStore {

    private val settings: KeychainSettings

    init {
        // Must precede the first use of the new-accessibility settings below.
        migrateAccessibility()
        settings = KeychainSettings(
            kSecAttrService to CFBridgingRetain(serviceName),
            kSecAttrAccessible to kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
        )
    }

    private fun serviceQuery(): platform.CoreFoundation.CFMutableDictionaryRef? {
        val d = CFDictionaryCreateMutable(
            null, 0, kCFTypeDictionaryKeyCallBacks.ptr, kCFTypeDictionaryValueCallBacks.ptr,
        )
        CFDictionaryAddValue(d, kSecClass, kSecClassGenericPassword)
        val svc = CFBridgingRetain(serviceName)
        CFDictionaryAddValue(d, kSecAttrService, svc)
        // The dictionary retained it (kCFTypeDictionaryValueCallBacks).
        CFRelease(svc)
        return d
    }

    /**
     * The `kSecAttrAccessible` of one item under the service (null = no items
     * or failure). Logs `KEYCHAIN_ACCESSIBILITY <value>`.
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
                val acc = CFDictionaryGetValue(attrs as CFDictionaryRef?, kSecAttrAccessible)
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

    /**
     * One `SecItemUpdate` over every item under the service, setting
     * `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly`. Runs once (flag).
     * Never throws and never deletes; on failure the flag stays unset so the
     * next launch retries.
     */
    fun migrateAccessibility() {
        try {
            if (migrationDone()) return
            Log.i(TAG, "before migration:")
            currentAccessibility()
            val q = serviceQuery()
            val upd = CFDictionaryCreateMutable(
                null, 0, kCFTypeDictionaryKeyCallBacks.ptr, kCFTypeDictionaryValueCallBacks.ptr,
            )
            CFDictionaryAddValue(upd, kSecAttrAccessible, kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly)
            val status = SecItemUpdate(q, upd)
            CFRelease(q)
            CFRelease(upd)
            // errSecItemNotFound (-25300): nothing stored yet, nothing to migrate.
            if (status == errSecSuccess || status == -25300) {
                markMigrationDone()
                Log.i(TAG, "keychain accessibility migration done (status=$status)")
            } else {
                Log.w(TAG, "keychain accessibility migration failed (status=$status); will retry")
            }
            currentAccessibility()
        } catch (e: Throwable) {
            Log.w(TAG, "keychain accessibility migration threw: ${e.message}")
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
    }
}
