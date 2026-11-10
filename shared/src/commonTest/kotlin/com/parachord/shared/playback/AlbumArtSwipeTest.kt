package com.parachord.shared.playback

import kotlin.test.Test
import kotlin.test.assertEquals

class AlbumArtSwipeTest {

    private val threshold = 300f          // ~30% of a 1000px screen
    private val velocityThreshold = 600f

    @Test
    fun belowThresholdAndSlowVelocity_snapsBack() {
        val result = decideSwipeCommit(offsetX = -50f, velocity = -100f,
            commitThreshold = threshold, velocityThreshold = velocityThreshold)
        assertEquals(SwipeOutcome.SnapBack, result)
    }

    @Test
    fun aboveThresholdLeftDrag_slowVelocity_commitsNext() {
        // Finger moved left → offsetX is negative → next
        val result = decideSwipeCommit(offsetX = -350f, velocity = -100f,
            commitThreshold = threshold, velocityThreshold = velocityThreshold)
        assertEquals(SwipeOutcome.Next, result)
    }

    @Test
    fun aboveThresholdRightDrag_slowVelocity_commitsPrevious() {
        // Finger moved right → offsetX is positive → previous
        val result = decideSwipeCommit(offsetX = 350f, velocity = 100f,
            commitThreshold = threshold, velocityThreshold = velocityThreshold)
        assertEquals(SwipeOutcome.Previous, result)
    }

    @Test
    fun belowThresholdButFastNegativeVelocity_commitsNext() {
        val result = decideSwipeCommit(offsetX = -50f, velocity = -800f,
            commitThreshold = threshold, velocityThreshold = velocityThreshold)
        assertEquals(SwipeOutcome.Next, result)
    }

    @Test
    fun belowThresholdButFastPositiveVelocity_commitsPrevious() {
        val result = decideSwipeCommit(offsetX = 50f, velocity = 800f,
            commitThreshold = threshold, velocityThreshold = velocityThreshold)
        assertEquals(SwipeOutcome.Previous, result)
    }

    @Test
    fun velocityDisagreesWithOffset_velocityWins() {
        // Dragged left past threshold, but released with a fast rightward flick
        // → user is "throwing it back". Velocity wins → Previous.
        val result = decideSwipeCommit(offsetX = -350f, velocity = 900f,
            commitThreshold = threshold, velocityThreshold = velocityThreshold)
        assertEquals(SwipeOutcome.Previous, result)
    }

    @Test
    fun zeroDrag_zeroVelocity_snapsBack() {
        val result = decideSwipeCommit(offsetX = 0f, velocity = 0f,
            commitThreshold = threshold, velocityThreshold = velocityThreshold)
        assertEquals(SwipeOutcome.SnapBack, result)
    }

    @Test
    fun exactlyAtThreshold_commits() {
        val result = decideSwipeCommit(offsetX = -300f, velocity = 0f,
            commitThreshold = threshold, velocityThreshold = velocityThreshold)
        assertEquals(SwipeOutcome.Next, result)
    }
}
