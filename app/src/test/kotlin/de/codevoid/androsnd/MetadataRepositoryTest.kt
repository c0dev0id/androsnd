package de.codevoid.androsnd

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Album art is cached per folder rather than per song, so the mapping from a
 * folder path to its cache file has to be stable across runs — a folder whose
 * art file moved would silently re-extract art on every scan.
 */
@RunWith(AndroidJUnit4::class)
class MetadataRepositoryTest {

    private lateinit var context: Context
    private lateinit var repository: MetadataRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        repository = MetadataRepository(context)
    }

    @Test
    fun `the same folder always maps to the same file`() {
        assertEquals(
            repository.artFileForFolder("/Music/Album").absolutePath,
            repository.artFileForFolder("/Music/Album").absolutePath
        )
    }

    @Test
    fun `a fresh repository resolves the same folder to the same file`() {
        val first = repository.artFileForFolder("/Music/Album")

        assertEquals(first.absolutePath, MetadataRepository(context).artFileForFolder("/Music/Album").absolutePath)
    }

    @Test
    fun `different folders do not share an art file`() {
        assertNotEquals(
            repository.artFileForFolder("/Music/Album One").absolutePath,
            repository.artFileForFolder("/Music/Album Two").absolutePath
        )
    }

    /** Sibling folders with the same leaf name are distinct libraries entries. */
    @Test
    fun `folders differing only in their parent do not share an art file`() {
        assertNotEquals(
            repository.artFileForFolder("/Music/A/Live").absolutePath,
            repository.artFileForFolder("/Music/B/Live").absolutePath
        )
    }

    @Test
    fun `art files stay inside the cache directory`() {
        val file = repository.artFileForFolder("/Music/../../etc/Album")

        assertEquals(File(context.cacheDir, "album_art"), file.parentFile)
        assertTrue(file.canonicalPath.startsWith(context.cacheDir.canonicalPath))
    }
}
