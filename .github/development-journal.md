# Development Journal

Running record of the stack, the decisions worth remembering, and what the app
actually does. Kept for future development context, not as user documentation —
`README.md` is the end-user manual.

## Software Stack

| Piece | Choice |
|---|---|
| Language | Kotlin only, JVM target 17 |
| UI | Android Views + XML layouts (no Compose), Material Components 1.12.0 |
| Min / target SDK | 26 / 35 |
| Async | kotlinx-coroutines 1.8.1 everywhere except `UpdateChecker` |
| Playback | `MediaPlayer` + `MediaSessionCompat` (androidx.media 1.7.0) |
| Library access | Storage Access Framework (`DocumentsContract`) over a user-picked tree URI |
| Persistence | `SQLiteOpenHelper` (`metadata.db`) for tags, `SharedPreferences` (`androsnd_prefs`) for settings and playback state |
| Lists | RecyclerView 1.3.2 |
| Build / release | Gradle, GitHub Actions; signed release APKs, `dev` pre-release per main push |
| Tests | JUnit 4 + Robolectric, JVM unit tests in `app/src/test/kotlin`, run in CI |

## Key Decisions

**Folder-based library, not MediaStore.** The app scans a SAF tree itself and
groups by directory. Users with untidy ID3 tags still get a sane structure, and
nothing depends on the system media index being correct or populated.

**Volume is app-relative, not a stream change.** `MediaPlayer.setVolume()` is set
to `app_volume / 100f`, a fraction *of* the system music volume. This is the
point of the feature: navigation prompts stay at full system volume while music
plays quietly underneath. Transient focus loss ducks to `0.2 ×` app volume rather
than pausing, so a spoken instruction never stops the music.

**Songs are addressed by index, folders hold index lists.** Cheap and compact,
but every scan rebuilds the index space in display order, so an index is only
valid within one scan generation. Anything that must outlive a scan — notably the
remembered playback position — stores the song URI instead and resolves it back
to an index once the library is published.

**The scan publishes one immutable `Snapshot`.** `songs`, `folders` and
`foldersByPath` swap together behind a single `@Volatile` write, so no reader can
observe a half-updated triple. Derived collections belong inside `Snapshot`, not
beside it.

**`MediaMetadataRetriever` access is serialized through one semaphore held on the
repository.** Retrievers and `MediaPlayer.prepareAsync()` compete for a small pool
of native media slots; exhausting it breaks playback outright. The permit is taken
by the leaf functions that actually open a retriever, never by their callers — the
coroutines semaphore is not reentrant, so a caller holding it on a leaf's behalf
deadlocks.

**Metadata loads in two phases.** The scan publishes filenames immediately so the
list is usable within a second; `startEnrichment()` then fills in tags and art in
the background, doing the currently-playing song first so the visible row settles
before the rest of the library queues up.

**Art is cached per folder, not per song.** One JPEG per folder in `cacheDir`,
keyed by `folderPath.hashCode()`. An album's worth of songs shares one decode and
one file, which is the common case for a folder-organised library.

**"Next" is chosen ahead of time, not computed on demand.** `selectNextQueueSong()`
picks `nextQueueIndex` on every song change; `handleNext()` and track completion
just play it. That way the track the MediaSession advertises to Android Auto and
the notification is provably the one that will actually play, shuffle included.

**Settings cross component boundaries as prefs, not as binder values.** The writer
updates `androsnd_prefs` and then pokes the owner to re-read. Adding a setting
means following that pattern rather than widening the binder interface.

**Tests run against the real Android classes, not mocks.** The interesting logic
here is inseparable from `SharedPreferences`, `Uri` and SQLite semantics — a
cached row is valid only while `last_modified` matches, a bookmark is a URI
because indices are rebuilt every scan. Mocking those away would test the mock.
Robolectric runs the real implementations on the JVM, so the suite stays fast and
needs no device. Where a class cannot be driven from a test at all — populating
`PlaylistManager` needs a real SAF tree — the decision is extracted into an
`internal` function that takes plain data, rather than building a heavier fake.

**No migration code before 1.0.** `MetadataDb.onUpgrade` drops and recreates the
table; the cache is fully rebuildable from the files on disk, so a schema change
costs one rescan and no migration surface.

**Back never finishes the Activity.** This is an always-foreground automotive app;
`onBackPressed` closes overlays or toggles playback instead of calling `super`.

**The nightly is deliberately ungated; the release is gated by a human.**
`draft-release` depends on `build` alone — not on `lint` or `test` — so a push
that fails either still replaces the `dev` pre-release. That is intentional: the
nightly channel exists for development and testing, and a build that fails a test
is precisely the one worth installing to debug. Gating it would withhold the
artifact at the moment it is most useful. The user-facing path is separate:
`release.yml` is manual (`workflow_dispatch`), cut after checking the last
nightly ran fully green and after manual verification on the device, and it
publishes a *draft* release for a human to finish. Do not "fix" `draft-release`
by adding `needs: test`.

**Builds run in CI only.** The Android Gradle Plugin is not reachable from the
development sandbox, so changes are validated by pushing, not by a local build.

## Core Features

- Offline folder-based music library, scanned recursively from a SAF tree
  (mp3, ogg, flac, aac, m4a, opus).
- Two-level browsing: a folder grid overlay and an interleaved folder/song list.
- Full DMD Remote 2 navigation with a two-cursor focus model (song list and button
  bar move independently), plus a hardware key-repeat for the volume lever.
- App-relative volume so navigation audio stays intelligible over music.
- Shuffle with non-repeating next-track pre-selection; both the shuffle state and
  the selected song survive a restart.
- Floating "Now Playing" overlay over other apps, draggable and pinch-resizable.
- Media notification, MediaSession transport controls, and an Android Auto browse
  tree.
- In-app update check against GitHub releases, tracking pre-releases on dev builds
  and stable releases otherwise.
