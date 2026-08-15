# Androsnd

A music player for motorcycle riders. Built for [DMD](https://www.dmdnavigation.com) devices and the DMD Remote2 controller, landscape-only, fully offline, no accounts, no streaming.

This is opinionated software. I built it for my use case and my hardware setup. If that matches yours, great. If not, it probably won't make you happy.

---

## Features

- **DMD Remote2 support** — full navigation without touching the screen
- **Relative Volume** - can play at 30% volume, while the device is set to 100%
- **100% offline** — no account, no network required for playback
- **Folder-based library** — no need to fix your ID3 tag mess
- **Two-level browsing** — folders (albums) and songs within them
- **Shuffle** - play random songs
- **Now Playing overlay** — floating popup visible over other apps, draggable and resizable

---

## Supported formats

mp3, flac, ogg, aac, m4a, opus

---

## Getting started

1. Install the APK and launch Androsnd.
2. Grant the permissions it requests (see [Permissions](#permissions) below).
3. Open **Settings** and tap **Load Music Folder**.
4. Pick the root folder of your music collection. Androsnd will scan it recursively.
5. Press Play.

The library updates automatically each time you load a folder. There is no background sync.

---

## UI layout

The screen is split into two panels:

**Left panel** — album art, song title, artist, album, time remaining, and a vertical volume slider.

**Right panel** — folder/song browser. Navigate between folders with the top-level grid; tap a folder to see its songs.

**Bottom bar** — Previous, Play/Pause, Next (song), Shuffle, Folder Browser, Settings.

---

## Volume control

Unlike other players, the volume is relative to the system volume. This means you can play music at 30% volume, while you navigation system can play instructions at 100% volume. This means the player volume is a percentage of the currently set system volume.

---

## Remote control (DMD Remote2)

Androsnd is designed to be driven entirely by the DMD Remote2 without touching the screen (except the Settings menu).

| Button | Action |
|---|---|
| Up / Down | Move through the song list |
| Left / Right | Move between bottom bar buttons |
| Button 1 | Activate focused button — or play the focused song if it differs from the current one |
| Button 2 | Close folder browser → close settings → background the app |
| Lever Up | Volume up |
| Lever Down | Volume down |

A focus ring appears on the first remote key press and stays visible while navigating. Volume buttons repeat when held.

---

## Now Playing overlay

A floating "Now Playing" popup can appear over other apps when the song changes.

- **Enable/disable** — Settings → Show Now Playing Overlay
- **Opacity** — Settings → Now-Playing Popup Opacity
- **Size** — Settings → Now-Playing Popup Size (or pinch to resize live)
- **Position** — drag it anywhere; position is remembered across songs
- **Preview** — Settings → Show Demo Popup

The overlay requires the "Display over other apps" permission. If you revoke it, the overlay silently stops appearing.

---

## Permissions

| Permission | Why |
|---|---|
| Read audio files | Access your music library |
| Display over other apps | Now Playing overlay |
| Foreground service | Keep music playing when the screen is off or the app is backgrounded |
| Notifications | Playback controls in the notification shade |
| Wake lock | Prevent the CPU from sleeping mid-track |
| Internet | Update checker only — never used during playback |
| Install packages | In-app update installation |
| Ignore battery optimizations | Prevent Android from killing the service on long rides |
