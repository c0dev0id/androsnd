package de.codevoid.androsnd

/**
 * Which filenames count as music, and which as a folder's cover art.
 *
 * A naming policy rather than scanner mechanics, and deliberately free of any
 * Android dependency: getting either set wrong silently drops files from the
 * library, which is invisible until a user notices missing songs.
 */
object MediaFileNames {

    private val AUDIO_EXTENSIONS = setOf("mp3", "ogg", "flac", "aac", "m4a", "opus")

    private val COVER_IMAGE_NAMES = setOf(
        "cover.jpg", "cover.jpeg", "cover.png",
        "folder.jpg", "folder.jpeg", "folder.png",
        "artwork.jpg", "artwork.jpeg", "artwork.png",
        "album.jpg", "album.jpeg", "album.png",
        "front.jpg", "front.jpeg", "front.png"
    )

    /** Only the final extension counts, so a backup of a song is not a song. */
    fun isAudio(name: String): Boolean =
        name.substringAfterLast('.', "").lowercase() in AUDIO_EXTENSIONS

    fun isCover(name: String): Boolean = name.lowercase() in COVER_IMAGE_NAMES
}
