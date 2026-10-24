package com.parachord.shared.platform

/**
 * Platform abstractions for code shared between Android and iOS.
 */

/** Platform-agnostic logging. */
expect object Log {
    fun d(tag: String, msg: String)
    fun d(tag: String, msg: String, throwable: Throwable?)
    fun i(tag: String, msg: String)
    fun i(tag: String, msg: String, throwable: Throwable?)
    fun w(tag: String, msg: String)
    fun w(tag: String, msg: String, throwable: Throwable?)
    fun e(tag: String, msg: String)
    fun e(tag: String, msg: String, throwable: Throwable?)
}

/** Generate a random UUID string. */
expect fun randomUUID(): String

/** Current time in milliseconds since epoch. */
expect fun currentTimeMillis(): Long

/**
 * Unicode compatibility decomposition (NFKD) — JS `String.prototype.normalize('NFKD')`.
 *
 * Kotlin common has no `Normalizer`, so each platform supplies it. Used by
 * `normalizeStr` to fold accented Latin to its base letters (é → e + U+0301,
 * whose combining mark is then stripped) the same way desktop does (#404).
 */
expect fun nfkd(s: String): String
