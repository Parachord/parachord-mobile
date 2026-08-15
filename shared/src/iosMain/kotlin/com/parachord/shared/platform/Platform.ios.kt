package com.parachord.shared.platform

import platform.Foundation.NSDate
import platform.Foundation.NSUUID
import platform.Foundation.NSLog
import platform.Foundation.timeIntervalSince1970

actual object Log {
    // NSLog(format, ...) treats its FIRST argument as a printf/NSString format
    // string. Passing an interpolated message directly means any '%' in it — a
    // URL-encoded value ("%20"), an artist/track name, an exception message — is
    // read as a format specifier, and NSLog dereferences a vararg that was never
    // passed → EXC_BAD_ACCESS (SIGSEGV) in _platform_strlen. This crashed the app
    // in the wild (TestFlight 0.1(1)) via ListenBrainzClient's Log.w during sync.
    //
    // Escaping every '%' to '%%' makes the whole string a literal format with no
    // active specifiers, so no vararg is ever read. Output is unchanged ('%%'
    // renders as a single '%'). This is safe regardless of how Kotlin/Native
    // marshals variadics to NSLog (which it does NOT do reliably), unlike the
    // NSLog("%@", msg) form.
    private fun emit(line: String) { NSLog(line.replace("%", "%%")) }

    actual fun d(tag: String, msg: String) { emit("D/$tag: $msg") }
    actual fun d(tag: String, msg: String, throwable: Throwable?) { emit("D/$tag: $msg ${throwable?.message ?: ""}") }
    actual fun i(tag: String, msg: String) { emit("I/$tag: $msg") }
    actual fun i(tag: String, msg: String, throwable: Throwable?) { emit("I/$tag: $msg ${throwable?.message ?: ""}") }
    actual fun w(tag: String, msg: String) { emit("W/$tag: $msg") }
    actual fun w(tag: String, msg: String, throwable: Throwable?) { emit("W/$tag: $msg ${throwable?.message ?: ""}") }
    actual fun e(tag: String, msg: String) { emit("E/$tag: $msg") }
    actual fun e(tag: String, msg: String, throwable: Throwable?) { emit("E/$tag: $msg ${throwable?.message ?: ""}") }
}

actual fun randomUUID(): String = NSUUID().UUIDString

actual fun currentTimeMillis(): Long = (NSDate().timeIntervalSince1970 * 1000).toLong()
