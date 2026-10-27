package com.parachord.android.data.cache

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Reads and writes for the JSON cache files in `filesDir`, off the main thread.
 *
 * Writes are atomic: the text goes to `<name>.tmp`, which is then renamed over
 * the real file. A plain `writeText` truncates first, so a process killed
 * mid-write left a half-written file that failed to decode — and the whole
 * cache was refetched on the next launch. iOS already writes atomically.
 */
object CacheFiles {
    suspend fun read(file: File): String? = withContext(Dispatchers.IO) {
        try {
            if (file.exists()) file.readText() else null
        } catch (_: Exception) {
            null
        }
    }

    suspend fun write(file: File, text: String): Unit = withContext(Dispatchers.IO) {
        try {
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(file)) {
                // rename(2) replaces the target on the same filesystem; fall back
                // to a direct write if a platform quirk refuses.
                file.writeText(text)
                tmp.delete()
            }
        } catch (_: Exception) {
            // Best-effort cache: a failed write just means a refetch later.
        }
    }
}
