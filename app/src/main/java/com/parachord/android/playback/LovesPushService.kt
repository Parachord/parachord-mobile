package com.parachord.android.playback

/**
 * Bridge shim — the loved-tracks push now lives in shared/commonMain so iOS
 * pushes loves through the same service. Preserves app-side imports.
 */
typealias LovesPushService = com.parachord.shared.playback.LovesPushService
