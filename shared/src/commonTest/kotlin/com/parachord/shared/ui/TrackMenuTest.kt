package com.parachord.shared.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class TrackMenuTest {
    private fun actions(items: List<TrackMenuItem>) = items.map { it.action }

    @Test
    fun `everything in desktop's group order`() {
        val items = TrackMenu.items(
            inCollection = false, canRemoveFromQueue = true, canRemoveFromPlaylist = true,
            canEditMetadata = true, canGoToAlbum = true,
        )
        assertEquals(
            listOf(
                TrackMenuAction.PLAY_NEXT, TrackMenuAction.ADD_TO_QUEUE, TrackMenuAction.ADD_TO_PLAYLIST,
                TrackMenuAction.REMOVE_FROM_QUEUE, TrackMenuAction.REMOVE_FROM_PLAYLIST,
                TrackMenuAction.EDIT_METADATA,
                TrackMenuAction.COLLECTION,
                TrackMenuAction.GO_TO_ARTIST, TrackMenuAction.GO_TO_ALBUM,
                TrackMenuAction.SHARE,
            ),
            actions(items),
        )
        assertEquals(
            listOf(TrackMenuAction.REMOVE_FROM_QUEUE, TrackMenuAction.EDIT_METADATA, TrackMenuAction.COLLECTION, TrackMenuAction.GO_TO_ARTIST, TrackMenuAction.SHARE),
            items.filter { it.startsGroup }.map { it.action },
        )
    }

    @Test
    fun `a plain row drops the rows it can't do — and the first row never starts a group`() {
        val items = TrackMenu.items(inCollection = true, canShare = false)
        assertEquals(
            listOf(TrackMenuAction.PLAY_NEXT, TrackMenuAction.ADD_TO_QUEUE, TrackMenuAction.ADD_TO_PLAYLIST, TrackMenuAction.COLLECTION, TrackMenuAction.GO_TO_ARTIST),
            actions(items),
        )
        assertEquals(false, items.first().startsGroup)
    }

    @Test
    fun `the collection row says what it will do`() {
        assertEquals(TrackMenu.REMOVE_FROM_COLLECTION, TrackMenu.items(inCollection = true).single { it.action == TrackMenuAction.COLLECTION }.label)
        assertEquals(TrackMenu.ADD_TO_COLLECTION, TrackMenu.items(inCollection = false).single { it.action == TrackMenuAction.COLLECTION }.label)
        assertEquals("", TrackMenu.items(inCollection = null).single { it.action == TrackMenuAction.COLLECTION }.label)
    }

    @Test
    fun `removals are destructive`() {
        val items = TrackMenu.items(inCollection = false, canRemoveFromQueue = true, canRemoveFromPlaylist = true)
        assertEquals(setOf(TrackMenuAction.REMOVE_FROM_QUEUE, TrackMenuAction.REMOVE_FROM_PLAYLIST), items.filter { it.destructive }.map { it.action }.toSet())
    }
}
