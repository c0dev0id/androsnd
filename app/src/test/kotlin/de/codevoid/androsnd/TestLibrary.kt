package de.codevoid.androsnd

import android.net.Uri
import de.codevoid.androsnd.model.Song

/**
 * Fixture builders shared by the tests that need a library. Both the ordering
 * tests and the cursor tests assert against these shapes, so they have to agree
 * on the URI and path conventions — separate copies would let one suite test a
 * library the other never produces.
 */

fun song(name: String, folder: String) = Song(
    uri = Uri.parse("content://tree/document/$folder/$name"),
    displayName = name,
    folderPath = "/music/$folder",
    folderName = folder
)

fun scannedFolder(name: String, vararg songNames: String) = ScannedFolder(
    name = name,
    path = "/music/$name",
    coverUri = null,
    songs = songNames.map { song(it, name) }
)
