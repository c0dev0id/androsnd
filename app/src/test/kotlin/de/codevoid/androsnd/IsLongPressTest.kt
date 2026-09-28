package de.codevoid.androsnd

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The remote's Play button distinguishes a tap (play/pause) from a hold (stop) by how
 * long it was held. The decision is a pure function of the two timestamps so it can be
 * verified without a device or the key-event plumbing.
 */
class IsLongPressTest {

    @Test
    fun `a hold at or past the threshold is long`() {
        assertTrue(isLongPress(downAtMs = 1_000L, upAtMs = 1_500L))
        assertTrue(isLongPress(downAtMs = 1_000L, upAtMs = 2_000L))
    }

    @Test
    fun `a quick tap is not long`() {
        assertFalse(isLongPress(downAtMs = 1_000L, upAtMs = 1_100L))
        assertFalse(isLongPress(downAtMs = 1_000L, upAtMs = 1_499L))
    }

    @Test
    fun `the threshold is configurable`() {
        assertTrue(isLongPress(downAtMs = 0L, upAtMs = 200L, thresholdMs = 200L))
        assertFalse(isLongPress(downAtMs = 0L, upAtMs = 199L, thresholdMs = 200L))
    }
}
