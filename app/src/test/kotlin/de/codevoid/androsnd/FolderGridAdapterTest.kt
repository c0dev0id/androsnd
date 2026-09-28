package de.codevoid.androsnd

import androidx.recyclerview.widget.RecyclerView
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.codevoid.androsnd.model.PlaylistFolder
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The folder browser's grid is handed the published folder list on every UI refresh,
 * once a second while playing. Only a different list — a rescan — may rebind it: a
 * rebind blanks every visible cover and decodes it again.
 */
@RunWith(AndroidJUnit4::class)
class FolderGridAdapterTest {

    private class ChangeCounter : RecyclerView.AdapterDataObserver() {
        var changes = 0
        override fun onChanged() {
            changes++
        }
    }

    private fun grid() = MainActivity.FolderGridAdapter(accentColor = 0, getArtFile = { null }, onClick = {})

    private fun folders(vararg names: String) = names.map { PlaylistFolder(name = it, path = "/music/$it") }

    @Test
    fun `a library handed to the grid fills it`() {
        val grid = grid()

        grid.submitData(folders("One", "Two"))

        assertEquals(2, grid.itemCount)
    }

    @Test
    fun `the library it already holds does not rebind the grid`() {
        val grid = grid()
        val counter = ChangeCounter().also { grid.registerAdapterDataObserver(it) }
        val library = folders("One", "Two")

        grid.submitData(library)
        grid.submitData(library)

        assertEquals(1, counter.changes)
    }

    @Test
    fun `a rescanned library replaces the grid`() {
        val grid = grid()
        val counter = ChangeCounter().also { grid.registerAdapterDataObserver(it) }

        grid.submitData(folders("One", "Two"))
        grid.submitData(folders("Three"))

        assertEquals(2, counter.changes)
        assertEquals(1, grid.itemCount)
    }
}
