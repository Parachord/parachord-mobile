package com.parachord.shared.repository

import com.parachord.shared.model.Friend
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FriendsUnsavedProfileTest {

    private val saved = Friend(id = "friend-1", username = "Alice", service = "listenbrainz", displayName = "Alice B", addedAt = 1L)

    @Test
    fun unsavedIdsRoundTripAndAColonInTheNameSurvives() {
        assertEquals("transient:lastfm:a:b", unsavedFriendId("lastfm", "a:b"))
        assertEquals("lastfm" to "a:b", parseUnsavedFriendId(unsavedFriendId("lastfm", "a:b")))
        assertNull(parseUnsavedFriendId("friend-1"))
        assertNull(parseUnsavedFriendId("transient:lastfm"))
        assertNull(parseUnsavedFriendId("transient::bob"))
    }

    @Test
    fun anUnsavedFriendIsBuiltFromTheId() {
        val friend = chooseProfileFriend("transient:lastfm:bob", savedById = null, allSaved = emptyList())!!
        assertTrue(friend.transient)
        assertEquals("bob", friend.username)
        assertEquals("lastfm", friend.service)
        assertEquals("bob", friend.displayName)
        assertEquals("transient:lastfm:bob", friend.id)
        assertNull(friend.cachedTrackName)
    }

    @Test
    fun theSavedFriendWinsWhenAlreadyFollowed() {
        val friend = chooseProfileFriend("transient:listenbrainz:alice", savedById = null, allSaved = listOf(saved))!!
        assertEquals("friend-1", friend.id)
        assertFalse(friend.transient)
    }

    @Test
    fun aSavedIdIsReturnedAsIs() {
        assertEquals(saved, chooseProfileFriend("friend-1", savedById = saved, allSaved = listOf(saved)))
    }

    @Test
    fun theServiceMustMatchExactly() {
        val friend = chooseProfileFriend("transient:lastfm:alice", savedById = null, allSaved = listOf(saved))!!
        assertTrue(friend.transient)
    }

    @Test
    fun anUnknownNonTransientIdIsNull() {
        assertNull(chooseProfileFriend("nope", savedById = null, allSaved = emptyList()))
    }
}
