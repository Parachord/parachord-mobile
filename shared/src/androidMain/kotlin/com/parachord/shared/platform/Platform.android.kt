package com.parachord.shared.platform

actual object Log {
    actual fun d(tag: String, msg: String) { android.util.Log.d(tag, msg) }
    actual fun d(tag: String, msg: String, throwable: Throwable?) { android.util.Log.d(tag, msg, throwable) }
    actual fun i(tag: String, msg: String) { android.util.Log.i(tag, msg) }
    actual fun i(tag: String, msg: String, throwable: Throwable?) { android.util.Log.i(tag, msg, throwable) }
    actual fun w(tag: String, msg: String) { android.util.Log.w(tag, msg) }
    actual fun w(tag: String, msg: String, throwable: Throwable?) { android.util.Log.w(tag, msg, throwable) }
    actual fun e(tag: String, msg: String) { android.util.Log.e(tag, msg) }
    actual fun e(tag: String, msg: String, throwable: Throwable?) { android.util.Log.e(tag, msg, throwable) }
}

actual fun randomUUID(): String = java.util.UUID.randomUUID().toString()

actual fun currentTimeMillis(): Long = System.currentTimeMillis()

actual fun nfkd(s: String): String = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFKD)
