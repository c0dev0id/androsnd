package de.codevoid.androsnd

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A group's bundled logo drawable is resolved by name (`logo_<slug>`), so the slug is the
 * contract between the JSON group names and the drawable file names. It is a pure string
 * transform, verified here without Android resources.
 */
class LogoSlugTest {

    @Test
    fun `spaces and case fold to a single underscore lowercase`() {
        assertEquals("radio_rpr1", RadioPlaybackController.logoSlug("Radio RPR1"))
        assertEquals("somafm", RadioPlaybackController.logoSlug("SomaFM"))
    }

    @Test
    fun `runs of punctuation collapse and ends are trimmed`() {
        assertEquals("rock_n_roll", RadioPlaybackController.logoSlug("Rock 'n' Roll"))
        assertEquals("jazz", RadioPlaybackController.logoSlug("  Jazz!  "))
    }

    @Test
    fun `a name with no usable characters yields an empty slug`() {
        assertEquals("", RadioPlaybackController.logoSlug("!!!"))
        assertEquals("", RadioPlaybackController.logoSlug(""))
    }
}
