package de.codevoid.androsnd

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.codevoid.androsnd.model.RadioStation
import de.codevoid.androsnd.model.StationGroup
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The station library is fed from hand-editable JSON, so a single malformed entry must
 * not empty the list, and user-added stations must round-trip through filesDir. Uses
 * Robolectric only because org.json is stubbed under plain JVM tests.
 */
@RunWith(AndroidJUnit4::class)
class StationRepositoryTest {

    private lateinit var context: Context
    private lateinit var repo: StationRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        userFile().delete()
        repo = StationRepository(context)
    }

    @After
    fun tearDown() {
        userFile().delete()
    }

    private fun userFile() = File(context.filesDir, "user_stations.json")

    @Test
    fun `a well-formed document parses into groups and stations`() {
        val groups = StationRepository.parse(
            """{"groups":[{"name":"Jazz","stations":[
               {"name":"A","url":"http://a"},{"name":"B","url":"http://b","logo":"l"}]}]}"""
        )
        assertEquals(1, groups.size)
        assertEquals("Jazz", groups[0].name)
        assertEquals(listOf("A", "B"), groups[0].stations.map { it.name })
        assertEquals("l", groups[0].stations[1].logo)
    }

    @Test
    fun `entries missing a name or url are skipped, not fatal`() {
        val groups = StationRepository.parse(
            """{"groups":[{"name":"G","stations":[
               {"name":"ok","url":"http://ok"},
               {"name":"","url":"http://x"},
               {"name":"y","url":""},
               {"nonsense":true}]}]}"""
        )
        assertEquals(listOf("ok"), groups.single().stations.map { it.name })
    }

    @Test
    fun `malformed json yields an empty list rather than throwing`() {
        assertTrue(StationRepository.parse("not json").isEmpty())
        assertTrue(StationRepository.parse("{}").isEmpty())
    }

    @Test
    fun `merge appends user stations to a bundled group of the same name`() {
        val bundled = listOf(StationGroup("Rock", listOf(RadioStation("X", "http://x"))))
        val user = listOf(StationGroup("Rock", listOf(RadioStation("Y", "http://y"))))
        val merged = StationRepository.merge(bundled, user)
        assertEquals(1, merged.size)
        assertEquals(listOf("X", "Y"), merged.single().stations.map { it.name })
    }

    @Test
    fun `groups become ScannedFolders with stream-uri songs`() {
        val folders = repo.toScannedFolders(
            listOf(StationGroup("Jazz", listOf(RadioStation("A", "http://a"))))
        )
        val folder = folders.single()
        assertEquals("Jazz", folder.name)
        assertEquals("/radio/Jazz", folder.path)
        assertEquals("http://a", folder.songs.single().uri.toString())
        assertEquals("Jazz", folder.songs.single().folderName)
    }

    @Test
    fun `an added station round-trips through the user file`() {
        repo.addUserStation("My Stream", "http://mine")
        val reloaded = StationRepository.parse(userFile().readText())
        val userGroup = reloaded.single { it.name == StationRepository.USER_GROUP }
        assertEquals("My Stream", userGroup.stations.single().name)
        assertEquals("http://mine", userGroup.stations.single().url)
    }

    @Test
    fun `adding a second station keeps the first`() {
        repo.addUserStation("One", "http://one")
        repo.addUserStation("Two", "http://two")
        val userGroup = StationRepository.parse(userFile().readText())
            .single { it.name == StationRepository.USER_GROUP }
        assertEquals(listOf("One", "Two"), userGroup.stations.map { it.name })
    }
}
