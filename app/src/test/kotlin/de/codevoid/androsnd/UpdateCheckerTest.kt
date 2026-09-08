package de.codevoid.androsnd

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Version comparison decides whether the app offers an update at all, and it has
 * two modes: semver for releases, and plain inequality on the nightly channel,
 * where tags carry a commit SHA that has no ordering.
 *
 * Pure string arithmetic, so this runs as plain JUnit — no Android environment.
 */
class UpdateCheckerTest {

    @Test
    fun `a higher patch, minor or major is newer`() {
        assertTrue(UpdateChecker.isNewer("v1.2.4", "v1.2.3"))
        assertTrue(UpdateChecker.isNewer("v1.3.0", "v1.2.9"))
        assertTrue(UpdateChecker.isNewer("v2.0.0", "v1.9.9"))
    }

    @Test
    fun `the same version is not newer`() {
        assertFalse(UpdateChecker.isNewer("v1.2.3", "v1.2.3"))
    }

    @Test
    fun `an older version is not newer`() {
        assertFalse(UpdateChecker.isNewer("v1.2.3", "v1.2.4"))
        assertFalse(UpdateChecker.isNewer("v1.9.9", "v2.0.0"))
    }

    @Test
    fun `the v prefix is optional on either side`() {
        assertTrue(UpdateChecker.isNewer("1.2.4", "v1.2.3"))
        assertTrue(UpdateChecker.isNewer("v1.2.4", "1.2.3"))
    }

    @Test
    fun `missing components count as zero`() {
        assertTrue(UpdateChecker.isNewer("v1.3", "v1.2.9"))
        assertFalse(UpdateChecker.isNewer("v1.2", "v1.2.0"))
        assertTrue(UpdateChecker.isNewer("v1.2.1", "v1.2"))
    }

    @Test
    fun `nightly tags compare by inequality, not by order`() {
        assertFalse(UpdateChecker.isNewer("dev-abc1234", "dev-abc1234"))
        assertTrue(UpdateChecker.isNewer("dev-def5678", "dev-abc1234"))
        // A SHA that sorts lower is still a different build, so still an update.
        assertTrue(UpdateChecker.isNewer("dev-aaa0000", "dev-zzz9999"))
    }

    @Test
    fun `surrounding whitespace does not fake a new nightly`() {
        assertFalse(UpdateChecker.isNewer(" dev-abc1234 ", "dev-abc1234"))
    }

    @Test
    fun `a release build is offered to a nightly install`() {
        assertTrue(UpdateChecker.isNewer("v1.0.0", "dev-abc1234"))
    }
}
