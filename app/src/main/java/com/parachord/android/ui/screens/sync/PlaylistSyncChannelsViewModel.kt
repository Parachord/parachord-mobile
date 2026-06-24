package com.parachord.android.ui.screens.sync

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.parachord.shared.sync.PlaylistSyncChannel
import com.parachord.shared.sync.PlaylistSyncChannelManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Backs the per-playlist [com.parachord.android.ui.components.PlaylistSyncChannelsSheet]
 * (parity with iOS's `PlaylistSyncChannelsSheet`). Thin VM over the SHARED
 * [PlaylistSyncChannelManager] — no channel logic lives here, only the UI
 * state (loaded channels + the keep/delete confirmation + the AM "remove
 * manually" heads-up).
 */
class PlaylistSyncChannelsViewModel(
    private val manager: PlaylistSyncChannelManager,
) : ViewModel() {

    private val _channels = MutableStateFlow<List<PlaylistSyncChannel>>(emptyList())
    val channels: StateFlow<List<PlaylistSyncChannel>> = _channels.asStateFlow()

    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    /** The channel awaiting the user's keep/delete choice (toggle OFF flow). */
    private val _pendingOff = MutableStateFlow<PlaylistSyncChannel?>(null)
    val pendingOff: StateFlow<PlaylistSyncChannel?> = _pendingOff.asStateFlow()

    /** Provider display name for the "remove manually" heads-up (null = hidden). */
    private val _headsUp = MutableStateFlow<String?>(null)
    val headsUp: StateFlow<String?> = _headsUp.asStateFlow()

    private var localId: String = ""

    fun load(localId: String) {
        this.localId = localId
        viewModelScope.launch {
            _loading.value = true
            _channels.value = manager.getChannels(localId)
            _loading.value = false
        }
    }

    /** Toggle a channel ON — applied directly (no confirmation). */
    fun enableChannel(providerId: String) {
        viewModelScope.launch {
            manager.setChannel(localId, providerId, enabled = true)
            reload()
        }
    }

    /** Toggle a channel OFF — open the keep/delete confirmation. */
    fun requestDisable(channel: PlaylistSyncChannel) {
        _pendingOff.value = channel
    }

    fun cancelDisable() {
        _pendingOff.value = null
    }

    /**
     * Resolve the keep/delete confirmation. [deleteRemote] = also delete from the
     * service. If the remote delete is UNSUPPORTED (Apple Music), surfaces the
     * provider display name via [headsUp].
     */
    fun confirmDisable(channel: PlaylistSyncChannel, deleteRemote: Boolean) {
        _pendingOff.value = null
        viewModelScope.launch {
            val unsupported = manager.disableChannel(localId, channel.providerId, deleteRemote)
            if (unsupported != null) {
                _headsUp.value = unsupported
            }
            reload()
        }
    }

    fun dismissHeadsUp() {
        _headsUp.value = null
    }

    private suspend fun reload() {
        _channels.value = manager.getChannels(localId)
    }
}
