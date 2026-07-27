# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

**androsnd** (Andro Sound) is a Kotlin-only Android music player targeting automotive/embedded devices. It is landscape-only and designed for physical remote control navigation. Package: `de.codevoid.androsnd`, minSdk 26, targetSdk 35, JVM target 17.

## Build Commands

```bash
# Build debug APK
./gradlew assembleDebug -PappVersionName="v0.0.0-dev"

# Build release APK (minified + shrunk; requires signing env vars below)
./gradlew assembleRelease -PappVersionName="v1.2.3"

# Run lint
./gradlew lint
```

`versionName` comes from the `appVersionName` Gradle property and falls back to `"dev"`. `UpdateChecker.isNightlyBuild()` keys off that `dev` prefix to decide whether to offer pre-releases, so passing a realistic version matters when testing the updater.

Release signing reads four environment variables — `SIGNING_KEYSTORE_PATH`, `SIGNING_KEYSTORE_PASSWORD`, `SIGNING_KEY_ALIAS`, `SIGNING_KEY_PASSWORD`. If any is missing the `release` signing config is silently not created and the build emits `app-release-unsigned.apk`; the release workflow treats that as a hard error.

There are no unit or instrumentation tests in this project.

CI (`.github/workflows/build.yml`) runs `lint` and a signed release build on every push to `main`, then replaces the `dev` pre-release. `release.yml` is manual (`workflow_dispatch`) and auto-increments the patch version from the latest `v*` tag when no version is supplied.

## Source Layout

All Kotlin lives under `app/src/main/kotlin/de/codevoid/androsnd/`. Data classes are in the `model/` subpackage (`Song`, `PlaylistFolder`, `SongMetadata`). `MainActivity.kt` is ~1400 lines and contains both RecyclerView adapters as nested classes (`PlaylistAdapter`, `FolderGridAdapter`).

## Architecture

Four main runtime components:

**`MainActivity`** — The sole Activity (landscape-only). Owns all UI: left panel (album art, metadata, volume slider), right panel (folder/song `RecyclerView`), bottom button bar, settings panel, and folder browser overlay. Binds to `MusicService` via `ServiceConnection`.

**`MusicService`** — `MediaBrowserServiceCompat` running as a foreground service. Owns the `MediaPlayer` lifecycle, `MediaSession`, audio focus, and the persistent notification. Delegates library state to `PlaylistManager` and metadata to `MetadataRepository`. All async work runs on `serviceScope` (`SupervisorJob() + Dispatchers.Main.immediate`), with `withContext(Dispatchers.IO)` for blocking calls — there are no executors.

**`PlaylistManager`** — Data layer over SAF (`DocumentsContract`), no playback concerns. Recursively scans a tree URI for audio files (mp3/ogg/flac/aac/m4a/opus), builds `PlaylistFolder` → `Song`, and detects cover-art files by well-known name (`cover.jpg`, `folder.png`, `front.jpeg`, …). Also owns shuffle state and the current index.

**`MetadataRepository` + `MetadataDb`** — `MetadataDb` is a `SQLiteOpenHelper` over `metadata.db` with a single `songs` table keyed by URI, where a row is only considered valid if its stored `last_modified` matches the file's current value. `MetadataRepository` wraps it with `MediaMetadataRetriever` extraction and album-art handling. Two-phase loading: the scan publishes filenames instantly, then `startEnrichment()` fills in tags and art in the background.

Supporting components:

- **`OverlayToastManager`** — `SYSTEM_ALERT_WINDOW` floating "Now Playing" popup with drag/pinch gestures and fade animations. Owned by `MusicService`, configured from the settings panel (opacity, scale, enable toggle, live demo).
- **`UpdateChecker`** — Polls the GitHub releases API for `c0dev0id/androsnd`, compares semver, downloads the APK via `DownloadManager`, polls progress, and triggers install. Nightly builds track pre-releases; release builds track `/releases/latest`.
- **`AlbumArtProvider`** — `ContentProvider` (authority `de.codevoid.androsnd.albumart`) serving JPEGs out of `cacheDir/album_art/` with a canonical-path traversal guard. Exported so the notification and Android Auto can render art.
- **`GradientButton`** — `MaterialButton` subclass that paints its own gradient/depth; used across the button bar.

There is **no** remote key-preset system and no key-mapping wizard: remote keycodes are hardcoded in `MainActivity.onRemoteKeyDown`/`onRemoteKeyUp`.

## Communication Patterns

Four distinct paths — the first three are the intended ones, the fourth is a deliberate exception:

1. **`MainActivity` → `MusicService` (commands and state reads):** Direct method calls via the `MusicBinder` (e.g. `musicService?.play()`, `musicService?.playlistManager?.folders`). The binder is both the command interface and the way the UI reads current state.

2. **`MusicService` → `MainActivity` (signals):** `LocalBroadcastManager` only. Broadcast actions and extras are companion-object constants in `MusicService.kt` (`BROADCAST_STATE_CHANGED`, `BROADCAST_SCAN_*`, `BROADCAST_METADATA_UPDATED`, `BROADCAST_ART_UPDATED`, `BROADCAST_ENRICHMENT_COMPLETE`). These are **signals, not state transport**: except for the metadata extras, receivers react by pulling fresh state off the binder (`musicService.playlistManager`). Registered in `onCreate`, unregistered in `onDestroy`.

3. **DMD Remote 2 → `MainActivity` (key events):** A global **exported** `BroadcastReceiver` for action `"com.thorkracing.wireddevices.keypress"` carrying `key_press`/`key_release` int extras. This crosses process boundaries (an external companion app sends it), unlike the `LocalBroadcastManager` path. Registered in `onResume` and unregistered in `onPause` — not in `onCreate`/`onDestroy` like the local receivers. Because the same physical press can also arrive through normal key dispatch, `onRemoteKeyDown`/`onRemoteKeyUp` de-duplicate per keycode within a 100 ms window.

4. **Adapters → `MetadataDb` (direct reads):** `PlaylistAdapter.getTextMetadata` and `getArtFile` reach through the binder straight into `musicService.metadataRepository.db` / `artFileForFolder()`. Row binding needs synchronous per-song lookups; routing thousands of those through broadcasts would be unworkable. Keep such reads cheap and cached.

## Key Behavioural Details

### Indices, not objects

`PlaylistFolder.songs` is a `MutableList<Int>` of indices into `PlaylistManager.songs` — resolve via `playlistManager.songs[folder.songs[i]]`. After scanning, `scanFolder` **rebuilds the whole song list in display order** and rewrites every folder's index list to match, so index-based next/previous navigation follows what the user sees. Any code that caches song indices across a rescan is holding stale values.

### Snapshot publication

`PlaylistManager` publishes `songs`, `folders`, and `foldersByPath` together as a single `@Volatile` `Snapshot` data class, swapped once at the end of a scan. The three public properties are getters over that one reference. This exists specifically so a reader can never observe a half-updated triple — when adding derived collections, add them to `Snapshot` rather than as separate fields.

The playback cursor (`currentIndex`, `isShuffleOn`, `nextQueueIndex`) is three separate `@Volatile` fields, so it gets visibility but not atomicity. `currentIndex` is written by `scanFolder()` on an IO thread; the other two are mutated only from the main thread. Reads are safe anywhere, but the mutators span multiple fields — `toggleShuffle()` is a read-modify-write, and `selectNextQueueSong()` derives `nextQueueIndex` from `isShuffleOn` plus `currentIndex` — so keep those calls on the main thread. `MusicService.updateMediaSessionQueue()` is the pattern to follow: it snapshots both fields into locals before its `withContext(Dispatchers.IO)` block rather than reading them from the IO thread.

### Retriever contention

`MediaMetadataRetriever` and `MediaPlayer.prepareAsync()` compete for a limited pool of native media slots, and exhausting it breaks playback. `startEnrichment` therefore guards **every** retriever use with a single `Semaphore(1)`: the currently-playing song is enriched first while holding the permit, then remaining text and per-folder art jobs are dispatched in parallel but serialized through the same permit. Do not open a `MediaMetadataRetriever` outside that semaphore.

### Album art

Art is cached per **folder**, not per song, at `cacheDir/album_art/${folderPath.hashCode()}.jpg`, resolved via `MetadataRepository.artFileForFolder()`. Source preference: the folder's detected cover file, else the first embedded picture found in that folder. The now-playing bitmap is additionally written to `current_art.jpg` (scaled to 256 px for the media session) with a `notifyChange` so observers of the provider URI refresh. Bitmaps are decoded with `inSampleSize` bounds rather than at full size.

### MediaPlayer preparation

`MusicService` tracks `isPreparing` and `pendingPlayAfterPrepare` so a play/next issued while a track is still preparing is applied on the `OnPreparedListener` instead of touching the player in an illegal state. `OnPreparedListener` also bails out via `if (mediaPlayer !== mp) return` when a newer player has superseded it.

### Remote navigation focus model

There is no single "focus zone". Two cursors are live at once: `playlistFocusPos` (moved by up/down) and `buttonBarFocusIdx` (moved by left/right, defaults to Play). On every `onResume` the playlist scrolls to the playing song and `playlistFocusPos` is seeded to that row, so navigation resumes from what is on screen. Because the adapter is still empty at `onResume` on a cold start, that request is held in `pendingScrollToCurrent` and consumed by `updateUI()` once the data lands — anything that repopulates the playlist should route through `updateUI()` so the pending scroll still fires. Confirm acts on the focused **button**, with one special case — pressing Confirm on Play while the playlist cursor sits on a non-current song plays that song instead of toggling playback. Up/down also reset `buttonBarFocusIdx` back to Play. The focus ring stays hidden until the first remote key arrives (`hasReceivedRemoteKey`). When the folder browser overlay is open, all key handling is diverted to `handleFolderBrowserKeyDown`.

Hardcoded keycodes: F6/F7 volume up/down (with a 400 ms-delay, 150 ms-interval repeat), DPAD directions, ENTER/DPAD_CENTER/BUTTON_A confirm, ESCAPE handled on key-**up** only (closes folder browser, then settings, else backgrounds the app).

### Back button

`onBackPressed` intentionally does not call `super` — this is an always-foreground automotive app and back must never finish the Activity. It closes the folder browser, then settings, otherwise toggles play/pause.

### Android Auto

`MusicService` exposes the `MediaBrowserServiceCompat` tree (`onGetRoot`/`onLoadChildren`) with `folder_`/`song_`-prefixed media IDs, declared to Auto via the `com.google.android.gms.car.application` manifest meta-data and `res/xml/automotive_app_desc.xml`.
