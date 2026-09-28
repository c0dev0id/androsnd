# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

**androsnd** (Andro Sound) is a Kotlin-only Android music player targeting automotive/embedded devices — specifically DMD navigation units driven by a DMD Remote2 controller. It is landscape-only, fully offline, and designed for physical remote control navigation. Package: `de.codevoid.androsnd`, minSdk 26, targetSdk 35, JVM target 17. `README.md` is the end-user manual and is the reference for intended behaviour (remote button mapping, permissions, overlay). `.github/development-journal.md` records the rationale behind the key decisions; `CHANGELOG.md` (Keep a Changelog, `[Unreleased]` section) tracks user-facing changes.

## Build Commands

```bash
# Build debug APK
./gradlew assembleDebug -PappVersionName="v0.0.0-dev"

# Build release APK (minified + shrunk; requires signing env vars below)
./gradlew assembleRelease -PappVersionName="v1.2.3"

# Run lint
./gradlew lint

# Run the unit tests
./gradlew testDebugUnitTest

# Run one test class, or one test method (method names contain spaces)
./gradlew testDebugUnitTest --tests "de.codevoid.androsnd.MetadataDbTest"
./gradlew testDebugUnitTest --tests "de.codevoid.androsnd.MetadataDbTest.a stored row comes back intact"
```

**Builds are CI's job.** The Android Gradle Plugin is not reachable from the development sandbox, so do not attempt a local build or try to work around the restriction — push and let the workflows build (they run on `main` only — see below). The commands above document what CI runs.

`versionName` comes from the `appVersionName` Gradle property and falls back to `"dev"`. CI passes `dev-<short-sha>` for main-branch builds and `vX.Y.Z` for releases. `UpdateChecker.isNightlyBuild()` keys off that `dev` prefix to decide whether to offer pre-releases, and `isNewer()` deliberately degrades to string inequality when either side starts with `dev` (SHAs have no semver ordering) — so passing a realistic version matters when testing the updater. The version the updater compares is parsed out of the release's APK asset name (`androsnd-<version>.apk`, falling back to the tag). The nightly's tag is just `dev`, so the workflows' rename steps (`androsnd-dev-<sha>.apk`, `androsnd-vX.Y.Z.apk`) are what make update detection work — renaming the asset breaks it.

Release signing reads four environment variables — `SIGNING_KEYSTORE_PATH`, `SIGNING_KEYSTORE_PASSWORD`, `SIGNING_KEY_ALIAS`, `SIGNING_KEY_PASSWORD`. If any is missing the `release` signing config is silently not created and the build emits `app-release-unsigned.apk`; the release workflow treats that as a hard error.

Unit tests live in `app/src/test/kotlin` and run on the JVM. The ones that touch Android APIs use Robolectric, which supplies the pieces this code is built on — `SharedPreferences`, `Uri`, SQLite — so they exercise the real classes rather than mocks; logic that needs no Android (version comparison, the filename rules) runs as plain JUnit and stays out of the Robolectric sandbox. They need no device and no signing config. There are no instrumentation tests.

Robolectric downloads an `android-all` jar for the target SDK on first run, so the first test run needs network access. Like the rest of the build, the tests cannot run in the development sandbox — CI runs them.

Nothing is widened for the sake of a test. The library reaches `PlaylistManager` through the `LibraryScanner` interface, so a stub scanner puts a known library in place and the real `scanFolder` — cursor restore, `moveCursorTo`, `selectNextQueueSong` and all — runs under test without a SAF tree. If something looks untestable, add a seam of that shape rather than relaxing a visibility modifier or faking a `DocumentsProvider`.

CI (`.github/workflows/build.yml`) runs `lint`, `testDebugUnitTest`, and a signed release build as three parallel jobs on every push to `main` — and only there. Nothing runs automatically for other branches or for pull requests, so a pushed feature branch gets no build, lint or test feedback until it lands on `main`. Once the build job succeeds, `draft-release` replaces the `dev` pre-release. It gates on `build` alone, deliberately: a nightly that fails lint or tests is still published because it is the build worth installing to debug (see the journal) — do not add `needs: test`. `release.yml` is manual (`workflow_dispatch`), auto-increments the patch version from the latest `v*` tag when no version is supplied, pushes the tag, and creates a **draft** release; it runs no lint or tests. `/releases/latest` ignores drafts, so stable installs are offered a release only once a human publishes it.

## Source Layout

All Kotlin lives under `app/src/main/kotlin/de/codevoid/androsnd/`. The shared model classes are in the `model/` subpackage (`Song`, `PlaylistFolder`, `SongMetadata`); the scan's `ScannedFolder` and the published `Library` live in `Library.kt`. `MainActivity.kt` is ~1500 lines and contains both RecyclerView adapters as nested classes (`PlaylistAdapter`, `FolderGridAdapter`).

## Architecture

Four main runtime components:

**`MainActivity`** — The sole Activity (landscape-only). Owns all UI: left panel (album art, metadata, volume slider), right panel (folder/song `RecyclerView`), bottom button bar, settings panel, and folder browser overlay. Binds to `MusicService` via `ServiceConnection`.

**`MusicService`** — `MediaBrowserServiceCompat` running as a foreground service. Owns the `MediaPlayer` lifecycle, `MediaSession`, audio focus, and the persistent notification. Delegates library state to `PlaylistManager` and metadata to `MetadataRepository`. All async work runs on `serviceScope` (`SupervisorJob() + Dispatchers.Main.immediate`), with `withContext(Dispatchers.IO)` for blocking calls — no executors here.

**`PlaylistManager`** — Publishes the library and owns the playback cursor and shuffle state. No playback concerns and, since the scanner extraction, no storage concerns either: it delegates to three collaborators.

- **`LibraryScanner`** / **`SafLibraryScanner`** — finds the music. A depth-first walk over a SAF tree (`DocumentsContract`) producing a flat list of `ScannedFolder`s, one per directory holding audio (a directory without audio of its own is walked but not listed): a folder's name, path, cover URI and its songs in whatever order the provider returned them. Emits a running song count for scan progress. Has no opinion about ordering, indices or playback, and is an interface so it can be substituted.
- **`Library.of()`** — the pure builder that owns *all* ordering and numbering. Sorts folders, sorts each folder's songs, then numbers the songs to match, and returns the `songs`/`folders`/`foldersByPath` triple. Folders sort by their own display name, not their path, so the list does not follow the tree: nested folders appear as siblings ordered by leaf name.
- **`MediaFileNames`** — the naming policy: which extensions are audio (mp3/ogg/flac/aac/m4a/opus) and which filenames are cover art (`cover.jpg`, `folder.png`, `front.jpeg`, …). No Android dependency.

**`MetadataRepository` + `MetadataDb`** — `MetadataDb` is a `SQLiteOpenHelper` over `metadata.db` with a single `songs` table keyed by URI, where a row is only considered valid if its stored `last_modified` matches the file's current value. A `last_modified` of 0 (the provider reported none) never validates, so such files are re-extracted on every scan. `onUpgrade` drops and recreates — there is deliberately no migration code pre-1.0. `MetadataRepository` wraps it with `MediaMetadataRetriever` extraction and album-art handling. Two-phase loading: the scan publishes filenames instantly, then `startEnrichment()` fills in tags and art in the background. Each completed enrichment pass ends with `cleanup()`, which drops rows for songs no longer in the library — so an empty or failed scan empties the table.

Supporting components:

- **`OverlayToastManager`** — `SYSTEM_ALERT_WINDOW` floating "Now Playing" popup with drag/pinch gestures and fade animations. Owned by `MusicService`, configured from the settings panel (opacity, scale, enable toggle, live demo).
- **`UpdateChecker`** — Queries the GitHub releases API for `c0dev0id/androsnd` on demand (Settings → Check for Updates), compares versions, downloads the APK via `DownloadManager`, polls its progress, and launches the installer. Nightly builds track pre-releases; release builds track `/releases/latest`. This is the one component whose background work does **not** use coroutines — it runs on a single-thread `Executor` and posts back through a main-thread `Handler`.
- **`AlbumArtProvider`** — `ContentProvider` (authority `de.codevoid.androsnd.albumart`) serving JPEGs out of `cacheDir/album_art/` with a canonical-path traversal guard. Exported so the notification and Android Auto can render art.
- **`GradientButton`** — `MaterialButton` subclass that paints its own gradient/depth; used across the button bar.

There is **no** remote key-preset system and no key-mapping wizard: remote keycodes are hardcoded in `MainActivity.onRemoteKeyDown`/`onRemoteKeyUp`.

## Communication Patterns

Five paths — the first three are the intended ones, the last two are deliberate exceptions:

1. **`MainActivity` → `MusicService` (commands and state reads):** Direct method calls via the `MusicBinder` (e.g. `musicService?.play()`, `musicService?.playlistManager?.folders`). The binder is both the command interface and the way the UI reads current state.

2. **`MusicService` → `MainActivity` (signals):** `LocalBroadcastManager`, with one exception below. Broadcast actions and extras are companion-object constants in `MusicService.kt` (`BROADCAST_STATE_CHANGED`, `BROADCAST_SCAN_*`, `BROADCAST_METADATA_UPDATED`, `BROADCAST_ART_UPDATED`, `BROADCAST_ENRICHMENT_COMPLETE`). These are **signals, not state transport**: the extras only say what changed — a song index, a folder path, a running scan count — and receivers pull fresh state off the binder (`musicService.playlistManager`). `BROADCAST_METADATA_UPDATED` also carries title/artist/album/duration extras, but nothing reads them. `BROADCAST_STATE_CHANGED` doubles as the 1 s progress tick while playing, so its receiver (`updateUI()`) runs every second. Registered in `onCreate`, unregistered in `onDestroy`.

   The exception is `setOnOverlayScaleChangedListener`: a lambda through which a pinch on the overlay moves the settings panel's size slider live. It is registered in `onServiceConnected` and cleared in `onDestroy` — the service outlives the Activity, so registering it without the clear leaks the Activity.

3. **DMD Remote 2 → `MainActivity` (key events):** A global **exported** `BroadcastReceiver` for action `"com.thorkracing.wireddevices.keypress"` carrying `key_press`/`key_release` int extras. This crosses process boundaries (an external companion app sends it), unlike the `LocalBroadcastManager` path. Registered in `onResume` and unregistered in `onPause` — not in `onCreate`/`onDestroy` like the local receivers. Because the same physical press can also arrive through normal key dispatch, `onRemoteKeyDown`/`onRemoteKeyUp` de-duplicate per keycode within a 100 ms window.

4. **Adapters → `MetadataDb` (direct reads):** `PlaylistAdapter.getTextMetadata` and `getArtFile` reach through the binder straight into `musicService.metadataRepository.db` / `artFileForFolder()`. Routing per-row lookups through broadcasts would be unworkable, so `onBindViewHolder` checks an in-memory `songMetadataMap` first and otherwise dispatches the read via `adapterScope.launch { withContext(Dispatchers.IO) { ... } }` — these are off the main thread, so `MetadataDb` is genuinely read from adapter IO threads while the service writes to it.

5. **Everything → `SharedPreferences` (`androsnd_prefs`):** All four components open the same prefs file by name, and it is the only shared mutable state outside the service. Notably the volume path never crosses the binder as a value: `MainActivity.setAppVolume()` writes `app_volume` (0–100) and then calls `musicService?.applyAppVolume()`, which re-reads the pref. `overlay_enabled` needs no poke: the service reads it at every song change. The overlay sliders are the one place a value travels as well: they write `overlay_opacity`/`overlay_scale` and pass the same value to `updateOverlayOpacity()`/`updateOverlayScale()` to restyle a popup that is on screen right now, and the next popup re-reads the prefs. `OverlayToastManager` writes position and scale itself on drag/pinch, and `PlaylistManager` persists its own `folder_uri`, `last_song_uri` and `shuffle_on`. When adding a setting, follow the volume pattern — write the pref, then poke the owner to re-read.

## Key Behavioural Details

### Indices, not objects

`PlaylistFolder.songs` is a `MutableList<Int>` of indices into `PlaylistManager.songs` — resolve via `playlistManager.songs[folder.songs[i]]`. `Library.of()` **numbers the songs in display order** — folders sorted, then each folder's songs sorted, then indices assigned in that order — so index-based next/previous navigation follows what the user sees. The two sorts deliberately differ: folders compare case-sensitively (plain `String` order), songs case-insensitively. Changing either silently reshuffles every library. Any code that caches song indices across a rescan is holding stale values — including across restarts, which is why the remembered cursor is persisted as a song **URI** (`last_song_uri`) and resolved back to an index at the end of every scan. Every cursor move goes through `PlaylistManager.moveCursorTo()`; a new mutator that assigns `currentIndex` directly will silently stop the position from being remembered. The two direct writes are deliberate: `clear()`, and the restore at the end of `scanFolder()`, which must not persist — when the remembered URI does not resolve (unmounted card, revoked grant) the cursor falls back to 0 and the bookmark survives for a later, complete scan. The media IDs handed to Android Auto and the MediaSession queue item IDs are indices too, so they go stale the same way.

A third index space exists in the UI: `PlaylistAdapter` flattens folders and songs into one interleaved `items` list (`TYPE_FOLDER` header rows followed by their `TYPE_SONG` rows), so **adapter positions are not song indices**. Convert with `getSongIndexAt(pos)` / `getItemPosForSong(songIndex)` (backed by the `songIndexToItemPos` map built in `submitData`) rather than doing arithmetic on positions. `playlistFocusPos` is an adapter position; `currentIndex` is a song index.

### Library publication

`PlaylistManager` publishes `songs`, `folders`, and `foldersByPath` together as a single `@Volatile` `Library`, built entirely off to the side and swapped in with one write at the end of a scan. The three public properties are getters over that one reference. This exists specifically so a reader can never observe a half-updated triple — when adding derived collections, add them to `Library` rather than as fields beside it. The guarantee holds per read, though: each getter re-reads the reference, so code that reads `folders` and then indexes into `songs` can straddle a swap if a scan finishes in between.

Publication is not display. When the Activity is bound while a scan is running, it keeps the playlist hidden, first behind the scan's song count and then behind a `Loading N/total` counter (one tick per `BROADCAST_METADATA_UPDATED`), and loads every row at once on `BROADCAST_ENRICHMENT_COMPLETE`. Bulk loading replaced streaming row insertion on purpose (8f0d19b). An enrichment pass that never reaches its `onComplete` callback therefore leaves the list hidden.

The folder browser's grid does not wait: `updateUI()` hands it `pm.folders` on every pass (once a second while playing), and `FolderGridAdapter.submitData()` returns early for the list it already holds. It keeps the published list rather than a copy, because a copy would defeat that identity check and rebind the grid every second. Don't feed it from a one-off broadcast alone — an Activity created after the broadcast never sees it, which is how the browser used to open empty.

The playback cursor (`currentIndex`, `isShuffleOn`, `nextQueueIndex`) is three separate `@Volatile` fields, so it gets visibility but not atomicity. `currentIndex` is written by `scanFolder()` on an IO thread; the other two are mutated only from the main thread. Reads are safe anywhere, but the mutators span multiple fields — `toggleShuffle()` is a read-modify-write, and `selectNextQueueSong()` derives `nextQueueIndex` from `isShuffleOn` plus `currentIndex` — so keep those calls on the main thread. `MusicService.updateMediaSessionQueue()` is the pattern to follow: it snapshots both fields into locals before its `withContext(Dispatchers.IO)` block rather than reading them from the IO thread.

### "Next" is pre-selected, not computed

`handleNext()` and track completion do not compute the next song; they play `playlistManager.nextQueueIndex`, which was chosen ahead of time by `selectNextQueueSong()` (sequential wrap-around, or with shuffle on a random pick that is never the current song — picks can still repeat). `handlePrevious()` keeps no history: with shuffle on it plays a random song, which can be the current one. `selectNextQueueSong()` is called from `OnPreparedListener` once a prepared song starts playing, from both shuffle toggles (button and `onSetShuffleMode`), and after a scan — a new playback entry point that bypasses `OnPreparedListener` will leave `nextQueueIndex` stale.

The pre-selection exists so the MediaSession queue can advertise the track that will actually play (with shuffle on, the queue is just current + next). But `updateMediaSessionQueue()` runs only from the two shuffle toggles; its post-scan call was dropped in 6b449e9, and it was never called on song change. The published queue therefore describes the moment of the last toggle, not the current song, and until shuffle is first toggled after the service starts, no queue is published at all.

### Retriever contention

`MediaMetadataRetriever` and `MediaPlayer.prepareAsync()` compete for a limited pool of native media slots, and exhausting it breaks playback. `MetadataRepository.retrieverSem` is a `Semaphore(1)` held as a **field on the repository**, so it spans background enrichment and the playback path alike and survives a rescan swapping one enrichment job for another.

Two rules follow from that, and both are load-bearing:

- The permit is acquired by the **leaf** functions that actually construct a retriever — `enrichText`, and the embedded-picture branches of `enrichArt` and `loadCurrentArt`. Their other branches (the folder's cover image, the cached art file) open no retriever and stay permit-free.
- Callers must **not** hold the permit on a leaf's behalf. `kotlinx.coroutines.sync.Semaphore` is not reentrant, so wrapping `startEnrichment`'s dispatch loop in a permit would deadlock against the leaves.

`startEnrichment` enriches the currently-playing song first and blocking, so the playing row fills in before the rest of the library queues up behind it; the remaining text jobs and per-folder art jobs are then dispatched in parallel and serialized through the permit. The semaphore is fair (FIFO), so for a song that is not cached yet the playback path's `fetchText`/`loadCurrentArt` queue behind every enrichment job already waiting. `playSong()` does not clear `currentTextMetadata`/`currentArtBitmap`, so during a first scan of a large library the previous song's title (and the notification's art) stay up until that request is served.

### Album art

Art is cached per **folder**, not per song, at `cacheDir/album_art/${folderPath.hashCode()}.jpg`, resolved via `MetadataRepository.artFileForFolder()`. Source preference: the folder's detected cover file, else the first embedded picture found in that folder. The cache is never invalidated — `enrichArt` skips a folder whose file exists and nothing deletes from `album_art/` — so a replaced cover only shows once the app's cache is cleared. The now-playing bitmap is additionally written to `current_art.jpg` (scaled to 256 px for the media session) with a `notifyChange` so observers of the provider URI refresh. Bitmaps are decoded with `inSampleSize` bounds rather than at full size.

### MediaPlayer preparation

`MusicService` tracks `isPreparing` and `playRequested` so a play issued before playback can start is applied at the next readiness point instead of touching the player in an illegal state. `playRequested` is the single "the user wants playback" latch: it is set only while a readiness event is actually pending — the player is preparing, or the library is still being scanned — and is consumed by whichever arrives first (`OnPreparedListener`, or the end of `scanFolderAsync`). Setting it with no pending event would leave it standing to fire at an unrelated moment later. `OnPreparedListener` also bails out via `if (mediaPlayer !== mp) return` when a newer player has superseded it, and the error and completion callbacks make the same identity check first. After a `MediaPlayer` error, `play()` refuses to build a new player for 1.5 s (`lastErrorTimeMs`), so an unplayable file does not retry in a loop.

### Volume is app-relative

There is no `AudioManager` stream manipulation. The slider sets `MediaPlayer.setVolume()` to `app_volume / 100f`, i.e. a fraction *of* whatever the system music volume is — deliberate, so navigation prompts can stay at full system volume while music plays quietly under them.

Audio focus: `AUDIOFOCUS_LOSS` and `AUDIOFOCUS_LOSS_TRANSIENT` pause; `AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK` ducks to `0.2 ×` that same app volume; `AUDIOFOCUS_GAIN` restores the volume after a duck and otherwise calls `play()`, whether or not anything was playing when focus was lost. On API 26+ the duck branch normally does not run: the request does not set `setWillPauseWhenDucked(true)`, so the system ducks the player itself without invoking the listener. Focus is requested when playback starts and is never abandoned on pause or stop — only in `onDestroy`.

### Remote navigation focus model

There is no single "focus zone". Two cursors are live at once: `playlistFocusPos` (moved by up/down) and `buttonBarFocusIdx` (moved by left/right, defaults to Play). On every `onResume` the playlist scrolls to the playing song and `playlistFocusPos` is seeded to that row, so navigation resumes from what is on screen. Because the adapter is still empty at `onResume` on a cold start, that request is held in `pendingScrollToCurrent` and consumed by `updateUI()` once the data lands — anything that repopulates the playlist should route through `updateUI()` so the pending scroll still fires. Confirm acts on the focused **button**, with one special case — pressing Confirm on Play while the playlist cursor sits on a non-current song plays that song instead of toggling playback. Up/down also reset `buttonBarFocusIdx` back to Play. The focus ring stays hidden until the first remote key arrives (`hasReceivedRemoteKey`). When the folder browser overlay is open, key-downs are diverted to `handleFolderBrowserKeyDown`; key-ups still go through `onRemoteKeyUp`, so ESCAPE and the volume-repeat cancel keep working there.

Hardcoded keycodes: F6/F7 (the README's lever up/down) volume ±5 (with a 400 ms-delay, 150 ms-interval repeat), DPAD directions, ENTER/DPAD_CENTER/BUTTON_A (Button 1) confirm, ESCAPE (Button 2) handled on key-**up** only (closes folder browser, then settings, else backgrounds the app). `dispatchKeyEvent` consumes these keycodes and drops system auto-repeats (`repeatCount > 0`); the volume repeat is the app's own. It is stopped only by an F6/F7 key-up or by `onPause` (which unregisters the receiver that key-up would arrive through), so any new key handling that swallows key-up events will leave the 150 ms loop running and drive volume to a rail.

### Back button

`onBackPressed` intentionally does not call `super` — this is an always-foreground automotive app and back must never finish the Activity. It closes the folder browser, then settings, otherwise toggles play/pause. This relies on the legacy back path: for apps targeting API 36 on Android 16+, the system no longer calls `onBackPressed()` or dispatches `KEYCODE_BACK`, so raising `targetSdk` to 36 means moving this logic onto an `OnBackPressedCallback` (or opting out with `android:enableOnBackInvokedCallback="false"`).

### Android Auto and media buttons

`MusicService` exposes the `MediaBrowserServiceCompat` tree (`onGetRoot`/`onLoadChildren`) with `folder_`/`song_`-prefixed media IDs, declared to Auto via the `com.google.android.gms.car.application` manifest meta-data and `res/xml/automotive_app_desc.xml`. `onBind` returns the local `MusicBinder` unless the intent's action is `SERVICE_INTERFACE`, so the Activity and media-browser clients bind the same service instance.

`initMediaSession()` publishes a `PlaybackState` straight away: a session left in `STATE_NONE` is never chosen as the media-button session, which is why a headset's Play used to do nothing until the first in-app play. When a key arrives as an intent instead (`MediaButtonReceiver` forwarding to the service's `MEDIA_BUTTON` filter), `onStartCommand` hands it to `MediaButtonReceiver.handleIntent` and then calls `startForeground()`, which that start requires either way.
