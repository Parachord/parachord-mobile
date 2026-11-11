package com.parachord.android.playback

import android.content.Context
import android.net.Uri
import com.parachord.shared.platform.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Android Auto's car-connection state ([CAR_CONNECTION_STATE_COLUMN]: 0 no
 * car, 1 native, 2 projection), read straight from its content provider
 * ([CAR_CONNECTION_URI]) without the AndroidX car library; null when the
 * provider is missing or can't be read, or the value is NULL. A binder call, so off the main thread.
 */
internal suspend fun queryCarConnectionState(context: Context): Int? = withContext(Dispatchers.IO) {
    try {
        context.contentResolver.query(
            Uri.parse(CAR_CONNECTION_URI),
            arrayOf(CAR_CONNECTION_STATE_COLUMN),
            null,
            null,
            null,
        )?.use { cursor ->
            val column = cursor.getColumnIndex(CAR_CONNECTION_STATE_COLUMN)
            // A NULL reads as 0 through getInt, which would veto the car: unknown.
            if (column < 0 || !cursor.moveToNext() || cursor.isNull(column)) null else cursor.getInt(column)
        }
    } catch (e: Exception) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        Log.w("CarConnectionState", "Couldn't read Android Auto's connection state: ${e.message}")
        null
    }
}
