package de.codevoid.androsnd

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import java.io.File

/**
 * The provider is exported so the notification and Android Auto can render art,
 * which means anything on the device can ask it for a file. It must serve only
 * what is inside the album art cache directory.
 */
@RunWith(AndroidJUnit4::class)
class AlbumArtProviderTest {

    private companion object {
        const val AUTHORITY = "de.codevoid.androsnd.albumart"
    }

    private lateinit var context: Context
    private lateinit var provider: AlbumArtProvider
    private lateinit var artDir: File

    private fun uriFor(segment: String) = Uri.parse("content://$AUTHORITY/$segment")

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        artDir = File(context.cacheDir, "album_art").apply { mkdirs() }
        artDir.listFiles()?.forEach { it.delete() }
        // Robolectric has to build the provider: constructing one directly and calling
        // attachInfo leaves getContext() null, because the framework method bodies are
        // not run outside its controller.
        provider = Robolectric.setupContentProvider(AlbumArtProvider::class.java, AUTHORITY)
    }

    @Test
    fun `serves a file that exists in the art cache`() {
        File(artDir, "123.jpg").writeBytes(byteArrayOf(1, 2, 3))

        val fd = provider.openFile(uriFor("123.jpg"), "r")

        assertNotNull(fd)
        fd?.close()
    }

    @Test
    fun `a file that is not cached yet is absent, not an error`() {
        assertNull(provider.openFile(uriFor("never-generated.jpg"), "r"))
    }

    /**
     * The guard is against an escape encoded inside a single path segment — plain
     * `..` segments are already collapsed away by lastPathSegment.
     */
    @Test
    fun `refuses to escape the art directory`() {
        File(context.cacheDir, "secret.txt").writeBytes(byteArrayOf(9))

        assertNull(provider.openFile(uriFor("..%2Fsecret.txt"), "r"))
        assertNull(provider.openFile(uriFor("..%2F..%2Fdatabases%2F${MetadataDb.DB_NAME}"), "r"))
    }

    @Test
    fun `a uri with no path segment is refused`() {
        assertNull(provider.openFile(Uri.parse("content://$AUTHORITY"), "r"))
    }
}
