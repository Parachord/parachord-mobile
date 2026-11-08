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
import platform.Foundation.NSRecursiveLock
import com.parachord.shared.platform.currentTimeMillis
import platform.Security.kSecAttrAccount
import platform.Security.kSecMatchLimitAll
import platform.UIKit.UIApplicationProtectedDataDidBecomeAvailable
import platform.Security.kSecAttrAccessibleAfterFirstUnlock
import platform.Security.kSecAttrAccessibleWhenPasscodeSetThisDeviceOnly
import platform.Security.kSecAttrAccessibleWhenUnlocked
import platform.Security.kSecAttrAccessibleWhenUnlockedThisDeviceOnly
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
 * accessibility is part of the wrapper's lookup query, so an item of another
 * class is invisible to (and un-overwritable by) the new-accessibility
 * instance. While the migration is pending (e.g. a launch with the phone
 * locked) every read and write therefore goes through a legacy instance with
 * no accessibility attribute. Instance selection, the operation and the
 * migration all run under one lock, so no legacy write can land after the
 * switch; a once-per-launch self-heal then moves any stray item.
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

    private val lock = NSRecursiveLock()

    private inline fun <T> locked(block: () -> T): T {
        lock.lock()
        try { return block() } finally { lock.unlock() }
    }

    /**
     * No accessibility attribute, so its queries match items of ANY class.
     * Used while the migration is pending.
     */
    private val legacySettings = KeychainSettings(service = serviceName)

    // The retained service string is held by the instance for the process
    // lifetime (this store is an app-lifetime singleton), so it isn't released.
    private val modernSettings = KeychainSettings(
        kSecAttrService to CFBridgingRetain(serviceName),
        kSecAttrAccessible to kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
    )

    private var migrated = false
    private var healed = false
    private var lastAttemptMs = 0L
    private var loggedBefore = false

    /** The instance to use now (call under [lock]). Retries a pending migration at most every 30 s. */
    private fun active(): KeychainSettings {
        if (!migrated && currentTimeMillis() - lastAttemptMs >= RETRY_INTERVAL_MS) tryMigrate()
        return if (migrated) modernSettings else legacySettings
    }

    init {
        locked {
            migrated = migrationDone()
            if (migrated) healIfNeeded() else tryMigrate()
        }
        // Retry / heal when the device unlocks (a CarPlay launch can precede it).
        // Posted on main; takes the same lock as every token access.
        NSNotificationCenter.defaultCenter.addObserverForName(
            name = UIApplicationProtectedDataDidBecomeAvailable,
            `object` = null,
            queue = null,
        ) { _ ->
            locked { if (!migrated) tryMigrate() else healIfNeeded() }
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

    /** Account names (never data) of every item under the service, all classes. */
    private fun accountNames(): List<String> = try {
        val q = serviceQuery()
        CFDictionaryAddValue(q, kSecReturnAttributes, kCFBooleanTrue)
        CFDictionaryAddValue(q, kSecMatchLimit, kSecMatchLimitAll)
        val names = memScoped {
            val out = alloc<CFTypeRefVar>()
            val status = SecItemCopyMatching(q, out.ptr)
            if (status != errSecSuccess) emptyList()
            else {
                val list = CFBridgingRelease(out.value) as? List<*> ?: emptyList<Any?>()
                val accountKey = CFBridgingRelease(CFRetain(kSecAttrAccount)) as String
                list.mapNotNull { (it as? Map<*, *>)?.get(accountKey) as? String }.sorted()
            }
        }
        CFRelease(q)
        names
    } catch (e: Throwable) {
        emptyList()
    }

    /** Status of looking for items under the service with one accessibility class. */
    private fun findWithAccessibility(accessible: platform.CoreFoundation.CFStringRef?): Int {
        val q = serviceQuery(accessible)
        CFDictionaryAddValue(q, kSecMatchLimit, kSecMatchLimitOne)
        val status = SecItemCopyMatching(q, null)
        CFRelease(q)
        return status
    }

    /**
     * True only if every other class answers a definite "not found". A locked
     * device answers errSecInteractionNotAllowed (-25308) for items it holds,
     * so this also distinguishes "locked with items" from "empty".
     */
    private fun noOtherClassLeft(): Boolean = listOf(
        kSecAttrAccessibleWhenUnlocked,
        kSecAttrAccessibleAfterFirstUnlock,
        kSecAttrAccessibleWhenUnlockedThisDeviceOnly,
        kSecAttrAccessibleWhenPasscodeSetThisDeviceOnly,
    ).all { findWithAccessibility(it) == -25300 }

    /**
     * One `SecItemUpdate` over every item under the service, then confirm no
     * other class remains. Never deletes. Call under [lock].
     */
    private fun moveToNewClass(): Boolean {
        val q = serviceQuery()
        val upd = CFDictionaryCreateMutable(
            null, 0, kCFTypeDictionaryKeyCallBacks.ptr, kCFTypeDictionaryValueCallBacks.ptr,
        )
        CFDictionaryAddValue(upd, kSecAttrAccessible, kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly)
        val status = SecItemUpdate(q, upd)
        CFRelease(q)
        CFRelease(upd)
        val ok = (status == errSecSuccess || status == -25300) && noOtherClassLeft()
        if (!ok) Log.w(TAG, "keychain accessibility update not confirmed (status=$status)")
        return ok
    }

    /** Idempotent once-per-launch sweep for an item written in a stale class. */
    private fun healIfNeeded() {
        if (healed) return
        try { healed = moveToNewClass() } catch (e: Throwable) {
            Log.w(TAG, "keychain heal threw: ${e.message}")
        }
    }

    /** Runs (or retries) the migration. @return true once migrated. */
    fun migrateAccessibility(): Boolean = locked { tryMigrate() }

    private fun tryMigrate(): Boolean {
        if (migrated) return true
        lastAttemptMs = currentTimeMillis()
        return try {
            if (!loggedBefore) {
                loggedBefore = true
                Log.i(TAG, "KEYCHAIN_ACCOUNTS before: ${accountNames()}")
                currentAccessibility()
            }
            if (moveToNewClass()) {
                migrated = true
                healed = true
                markMigrationDone()
                Log.i(TAG, "keychain accessibility migration done")
                Log.i(TAG, "KEYCHAIN_ACCOUNTS after: ${accountNames()}")
                currentAccessibility()
            } else {
                Log.w(TAG, "keychain accessibility migration pending; using legacy lookups")
            }
            migrated
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

    // Keychain errors never escape: a throw reaching a handler-less coroutine
    // would abort the process. Only statuses are logged, never values.
    override fun get(key: String): String? = try {
        locked { active().getStringOrNull(key) }
    } catch (e: Throwable) {
        Log.w(TAG, "keychain read failed for $key: ${e.message}")
        null
    }

    override fun set(key: String, value: String) {
        try {
            locked { active().putString(key, value) }
        } catch (e: Throwable) {
            Log.w(TAG, "keychain write failed for $key: ${e.message}")
        }
        changes.tryEmit(key)
    }

    override fun remove(key: String) {
        try {
            locked { active().remove(key) }
        } catch (e: Throwable) {
            Log.w(TAG, "keychain remove failed for $key: ${e.message}")
        }
        changes.tryEmit(key)
    }

    override fun contains(key: String): Boolean = try {
        locked { active().hasKey(key) }
    } catch (e: Throwable) {
        Log.w(TAG, "keychain lookup failed for $key: ${e.message}")
        false
    }

    override fun observe(key: String): Flow<String?> =
        changes
            .filter { it == key }
            .map { get(key) }
            .onStart { emit(get(key)) }
            .distinctUntilChanged()

    private companion object {
        const val TAG = "IosSecureTokenStore"
        const val RETRY_INTERVAL_MS = 30_000L
    }
}
