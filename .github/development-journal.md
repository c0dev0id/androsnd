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
| Playback | `MediaPlayer` for local files, Media3 ExoPlayer for radio streams, one shared `MediaSessionCompat` (androidx.media 1.7.0) |
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
plays quietly underneath. A focus loss that allows ducking lowers the music
instead of pausing it — on API 26+ the system performs that duck itself, and the
app's own `0.2 ×` duck is the fallback — so a prompt that asks to duck never stops
the music. Plain transient and permanent losses pause.

**Finding the files is separate from shaping the library.** `SafLibraryScanner`
walks the SAF tree and reports what is there; `Library.of()` decides the ordering
and numbering; `PlaylistManager` publishes the result and tracks the cursor. The
split was made because the ordering rules are where the subtle bugs live and they
were previously unreachable from a test — the walk needed a real content provider.
With a `LibraryScanner` interface in front, a stub puts a known library in place
and the real `scanFolder` path is exercised directly. Keep new decisions out of
the walk.

**Songs are addressed by index, folders hold index lists.** Cheap and compact,
but every scan rebuilds the index space in display order, so an index is only
valid within one scan generation. Anything that must outlive a scan — notably the
remembered playback position — stores the song URI instead and resolves it back
to an index once the library is published.

**The scan publishes one immutable `Library`.** `songs`, `folders` and
`foldersByPath` swap together behind a single `@Volatile` write, so no reader can
observe a half-updated triple. Derived collections belong inside `Library`, not
beside it.

**`MediaMetadataRetriever` access is serialized through one semaphore held on the
repository.** Retrievers and `MediaPlayer.prepareAsync()` compete for a small pool
of native media slots; exhausting it breaks playback outright. The permit is taken
by the leaf functions that actually open a retriever, never by their callers — the
coroutines semaphore is not reentrant, so a caller holding it on a leaf's behalf
deadlocks.

**Metadata loads in two phases.** The scan publishes the library as soon as the
walk ends; `startEnrichment()` then fills in tags and art in the background, doing
the currently-playing song first so the now-playing panel settles before the rest
of the library queues up. The playlist waits for the second phase: after a scan
the UI shows a `Loading N/total` counter and loads every row at once when
enrichment completes, which replaced streaming the rows in one by one.

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
needs no device. Where a class cannot be driven from a test directly — populating
`PlaylistManager` needs a real SAF tree — a seam is added instead (the
`LibraryScanner` interface, so a stub supplies the library), rather than widening
visibility or building a heavier fake.

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

**Radio is a second subsystem, not a mode flag threaded through the file path.**
Files and radio each own a player, a source and a cursor behind a common
`PlaybackController` interface; `MusicService` holds one `active` controller and
routes every command and state read to it. The shared surface is the button row,
the service shell (one MediaSession, notification and audio focus), `Library.of()`
and the two RecyclerView adapters. The alternative — a `mode` flag — would have
scattered `if (radio)` branches through `PlaylistManager`, `MusicService` and
`MainActivity` to gate off seek, duration, enrichment and folder-hash art, all of
which radio simply does not have. `FilePlaybackController` is the existing
`MediaPlayer` machinery relocated behind the interface unchanged; the delicate
state machine was not rewritten.

**Streams play through ExoPlayer, files through `MediaPlayer`.** Reconnect after a
dropout and live ICY "now playing" titles are exactly where `MediaPlayer` is
weakest, and they are the whole point of radio. ExoPlayer handles both natively.

**Radio pause is a timeshift, not a stop.** The `DefaultLoadControl` fixes `maxBufferMs`
at 20 minutes with `setPrioritizeTimeOverSizeThresholds(true)` so the time cap governs
rather than a byte estimate. ExoPlayer's loading loop is driven by `maxBufferMs`
independently of `playWhenReady`, so a paused player keeps downloading while consuming
nothing — the buffer grows in real time toward the cap. That is the feature: pause a
station to hoard a reserve, then ride through a tunnel without a dropout. While *playing*
the buffer stays flat at whatever the server fed, because a live stream trickles at the
encode rate after its initial burst (in equals out); the reserve only accumulates while
paused. The playback/rebuffer gates stay at the small defaults, so start-up and post-drop
resume are fast regardless of how full the buffer is — the burst covers them instantly.
This replaced a user-facing buffer-seconds setting, which coupled the retention window to
the start-up gate and made a large value stall on tune-in. `bufferedMs`
(`totalBufferedDuration`) surfaces the reserve so the shell can gauge it; the 1 s state
tick, which files drive only while playing, is kept running through a radio pause so the
gauge moves while the buffer fills. If the offline stretch outlasts the buffer the stream
jumps forward to live on reconnect — a jump beats silence.

A custom `LoadErrorHandlingPolicy` reconnects once immediately then on a calm 5 s cadence
rather than in a tight loop. Only the `active` controller holds native decoder
slots — the outgoing one is released, not paused, on a mode switch, and both are
released in `onDestroy`.

**User stations live in a file, not the database.** `user_stations.json` in
`filesDir`, because `metadata.db` is dropped and recreated on any schema change
(the pre-1.0 policy) and user-authored data must survive that. The radio cursor
math in `RadioManager` is duplicated from `PlaylistManager` rather than extracted
into a shared base, on the same principle as the scanner split: the file path is
delicate and left untouched. `StationRepository.load()` is `open` so a test can
supply a known station set without the bundled JSON or a network, matching the
`LibraryScanner` seam philosophy.

## Core Features

- Offline folder-based music library, scanned recursively from a SAF tree
  (mp3, ogg, flac, aac, m4a, opus).
- Internet radio as a second subsystem, toggled from the leftmost control-bar
  button: bundled station groups plus a user group fed by an add-stream field,
  live ICY titles, a buffered-ahead gauge in place of the countdown, a timeshift
  buffer that fills while paused for offline ride-through, and gentle reconnect.
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
