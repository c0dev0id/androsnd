package de.codevoid.androsnd

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.codevoid.androsnd.model.SongMetadata
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The cache's contract is that a row is only valid while the file behind it is
 * unchanged — the whole point of storing last_modified alongside the tags.
 */
@RunWith(AndroidJUnit4::class)
class MetadataDbTest {

    private lateinit var context: Context
    private lateinit var db: MetadataDb

    private val meta = SongMetadata("Title", "Artist", "Album", 240_000L)

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase("metadata.db")
        db = MetadataDb(context)
    }

    @After
    fun tearDown() {
        db.close()
        context.deleteDatabase("metadata.db")
    }

    @Test
    fun `a stored row comes back intact`() {
        db.upsert("content://song/a", 42L, meta)

        assertEquals(meta, db.get("content://song/a", 42L))
    }

    @Test
    fun `an unknown uri has no row`() {
        assertNull(db.get("content://song/never-seen", 42L))
    }

    @Test
    fun `a file modified since it was cached invalidates the row`() {
        db.upsert("content://song/a", 42L, meta)

        assertNull(db.get("content://song/a", 43L))
    }

    /** A file whose timestamp the provider did not report cannot be validated. */
    @Test
    fun `a row is never served when the timestamp is unusable`() {
        db.upsert("content://song/a", 0L, meta)

        assertNull(db.get("content://song/a", 0L))
        assertNull(db.get("content://song/a", -1L))
    }

    @Test
    fun `re-tagging a file replaces its row rather than duplicating it`() {
        db.upsert("content://song/a", 42L, meta)
        val retagged = meta.copy(title = "Corrected Title")

        db.upsert("content://song/a", 42L, retagged)

        assertEquals(retagged, db.get("content://song/a", 42L))
    }

    @Test
    fun `cleanup keeps rows still in the library and drops the rest`() {
        db.upsert("content://song/a", 1L, meta)
        db.upsert("content://song/b", 1L, meta)

        db.cleanup(setOf("content://song/a"))

        assertNotNull(db.get("content://song/a", 1L))
        assertNull(db.get("content://song/b", 1L))
    }

    @Test
    fun `cleanup against an empty library drops everything`() {
        db.upsert("content://song/a", 1L, meta)

        db.cleanup(emptySet())

        assertNull(db.get("content://song/a", 1L))
    }
}
