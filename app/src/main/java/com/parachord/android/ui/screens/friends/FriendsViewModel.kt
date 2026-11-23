package com.parachord.android.ui.screens.friends

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.parachord.android.data.db.entity.FriendEntity
import com.parachord.android.data.repository.FriendsRepository
import com.parachord.shared.model.Resource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
class FriendsViewModel constructor(
    private val friendsRepository: FriendsRepository,
    private val playbackController: com.parachord.android.playback.PlaybackController,
) : ViewModel() {

    /** Messages for the screen to show (a friend's top tracks that couldn't load, or none). */
    private val _messages = kotlinx.coroutines.flow.MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: kotlinx.coroutines.flow.SharedFlow<String> = _messages

    /** Plays a friend's recent top 10 (desktop's `playListenerTopTracks`, the listener card's play button). */
    fun playTopTracks(friend: FriendEntity) {
        viewModelScope.launch {
            when (val play = com.parachord.shared.recommendations.listenerPlay(
                friend.service, friend.username, friend.displayName, friendsRepository::listenerTopTracks,
            )) {
                is com.parachord.shared.recommendations.ListenerPlay.Play ->
                    playbackController.playQueue(play.queue.tracks, startIndex = 0, context = play.queue.context)
                is com.parachord.shared.recommendations.ListenerPlay.Message -> _messages.tryEmit(play.text)
            }
        }
    }

    val friends: StateFlow<List<FriendEntity>> = friendsRepository.getAllFriends()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val showAddDialog = MutableStateFlow(false)
    val addFriendInput = MutableStateFlow("")
    val addFriendState = MutableStateFlow<Resource<Unit>?>(null)

    fun openAddDialog() {
        addFriendInput.value = ""
        addFriendState.value = null
        showAddDialog.value = true
    }

    fun dismissAddDialog() {
        showAddDialog.value = false
        addFriendInput.value = ""
        addFriendState.value = null
    }

    fun setAddFriendInput(input: String) {
        addFriendInput.value = input
    }

    fun addFriend() {
        val input = addFriendInput.value.trim()
        if (input.isBlank()) return

        viewModelScope.launch {
            addFriendState.value = Resource.Loading
            val result = friendsRepository.addFriend(input)
            when (result) {
                is Resource.Success -> {
                    addFriendState.value = Resource.Success(Unit)
                    // Auto-dismiss after success
                    showAddDialog.value = false
                    addFriendInput.value = ""
                    addFriendState.value = null
                }
                is Resource.Error -> {
                    addFriendState.value = Resource.Error(result.message)
                }
                is Resource.Loading -> { /* shouldn't happen */ }
            }
        }
    }

    fun removeFriend(friendId: String) {
        viewModelScope.launch {
            friendsRepository.removeFriend(friendId)
        }
    }

    fun togglePin(friend: FriendEntity) {
        viewModelScope.launch {
            friendsRepository.pinFriend(friend.id, !friend.pinnedToSidebar)
        }
    }

    fun pinFriend(friendId: String) {
        viewModelScope.launch {
            friendsRepository.pinFriend(friendId, true)
        }
    }

    fun refreshAll() {
        viewModelScope.launch {
            friends.value.forEach { friend ->
                friendsRepository.refreshFriendActivity(friend)
            }
        }
    }

    init {
        // Sync friends from Last.fm/ListenBrainz, then refresh activity
        viewModelScope.launch {
            friendsRepository.syncFriendsFromServices()
            refreshAll()
        }
    }
}
