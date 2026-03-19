# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

**androsnd** (Andro Sound) is a Kotlin-only Android music player targeting automotive/embedded devices. It is landscape-only and designed for physical remote control navigation. Package: `de.codevoid.androsnd`, minSdk 26, targetSdk 35.

## Build Commands

```bash
# Build debug APK
./gradlew assembleDebug -PappVersionName="v0.0.0-dev"

# Build release APK (requires signing env vars below)
./gradlew assembleRelease -PappVersionName="v1.2.3"

# Run lint
./gradlew lint
```

Release signing uses environment variables: `SIGNING_KEYSTORE_PATH`, `SIGNING_KEYSTORE_PASSWORD`, `SIGNING_KEY_ALIAS`, `SIGNING_KEY_PASSWORD`.

There are no unit or instrumentation tests in this project.

## Source Layout

All Kotlin lives under `app/src/main/kotlin/de/codevoid/androsnd/`. Data classes are in the `model/` subpackage (`Song`, `PlaylistFolder`, `SongMetadata`).

## Architecture

The app has four main runtime components:

**`MainActivity`** — The sole Activity (landscape-only). Owns all UI: left panel (album art, metadata, seek bar), right panel (folder/song `RecyclerView`), bottom button bar, settings panel, and key mapping wizard. Binds to `MusicService` via `ServiceConnection` and reacts to playback state via `LocalBroadcastManager` intents.

**`MusicService`** — `MediaBrowserServiceCompat` running as a foreground service. Owns the `MediaPlayer` lifecycle, `MediaSession`, audio focus, and the persistent notification. Delegates all playlist/library state to `PlaylistManager`. Sends state broadcasts on song change, play/pause, scan progress, etc.

**`PlaylistManager`** — Pure data layer (no framework dependencies). Scans device storage via SAF for audio files (mp3/ogg/flac/aac/m4a/opus), organises them into `PlaylistFolder` → `Song` structure, detects cover art files, and manages shuffle state. Thread safety relies on JVM reference-assignment atomicity: `_songs`/`_folders` are swapped atomically after a full scan completes, so any thread holding the old reference keeps seeing a consistent snapshot. `PlaylistFolder.songs` is a `MutableList<Int>` of indices into `PlaylistManager.songs`, not Song objects — resolve via `playlistManager.songs[folder.songs[i]]`.

**`MetadataCache`** — Reads ID3/media metadata in background executor threads; persists results to `metadata_cache.json` in internal storage, keyed by URI and validated by `lastModified` timestamp. Two-phase loading: instant scan first, metadata enriched in background. Threading: `MusicService` owns a single-thread `scanExecutor` for folder scanning and a `@Volatile` single-thread `metadataExecutor` for metadata loading (volatile because it can be restarted).

Supporting components:

- **`OverlayToastManager`** — `SYSTEM_ALERT_WINDOW` floating "Now Playing" popup with drag/pinch gestures and fade animations.
- **`UpdateChecker`** — Polls GitHub releases API, compares semver, downloads APK via `DownloadManager`, and triggers install.
- **`RemotePresetManager` / `RemoteKeyPreset`** — Maps physical remote keycodes to 8 actions (volume up/down, area select, nav up/down/left/right, confirm). Two presets: built-in "DMD Remote 2" (fixed) and "Custom" (user-configured via key wizard, persisted to `SharedPreferences`). The service also exposes a `MediaBrowserServiceCompat` API used by Android Auto (`com.google.android.gms.car.application` in the manifest).
- **`AlbumArtProvider`** — `ContentProvider` (authority `de.codevoid.androsnd.albumart`) serving cover images from cache, with path traversal protection.

## Communication Patterns

There are three distinct communication paths:

1. **`MainActivity` → `MusicService` (commands):** Direct method calls via the `MusicBinder` (e.g. `musicService?.play()`). The binder is the command interface.

2. **`MusicService` → `MainActivity` (state):** `LocalBroadcastManager` only. Constants for broadcast actions and extras live in `MusicService.kt` as companion object members. `MainActivity` registers receivers in `onCreate`/and unregisters in `onDestroy`. Never call service methods for state queries.

3. **DMD Remote 2 → `MainActivity` (key events):** A global exported `BroadcastReceiver` listening for action `"com.thorkracing.wireddevices.keypress"` with `key_press`/`key_release` int extras. This crosses process boundaries (the external companion app sends it), unlike the `LocalBroadcastManager` path. Received in `remoteReceiver`, dispatched to `onRemoteKeyDown`/`onRemoteKeyUp`.

## Key Behavioural Details

- The right-panel `RecyclerView` has a two-level list: folder headers (`PlaylistFolder`) and song rows (`Song`), rendered via a flat adapter that mixes both item types.
- Remote navigation tracks a "focus zone" (playlist vs. button bar) and the currently highlighted row index. Key events from the remote are translated in `MainActivity.onKeyDown` before reaching standard Android focus handling.
- Shuffle mode is tracked in `PlaylistManager`; the service asks it for the next song index, never computing order itself.
- `MusicService` handles rapid song switches (play-next while MediaPlayer is still preparing) via a pending-next-song slot to avoid the crash fixed in #110.
