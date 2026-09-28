# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

### Changed

### Fixed

## [0.1.0]

### Added

- Release notes on update dialog (visible next update)
- Internet radio. A new button on the far left switches music files and internet radio.
- Add your own stream in Settings: enter a name and an http(s) URL under Internet
  Radio to add it. It will be grouped under "My Streams".
- Radio buffers ahead for offline stretches. Pausing a station keeps the stream
  downloading instead of stopping it, so the buffer grows in real time (up to 20 minutes)
  while playback is paused. Start a station, pause it to build up a reserve, and it can
  then ride through a tunnel or dead spot without a dropout. If the offline stretch outlasts the buffer,
  the stream jumps forward to live on reconnect rather than staying silent.

### Changed

- Switching between music and radio while something is playing now keeps playing:
  it stops the current source and immediately starts the other one — the last radio
  station, or the remembered song. Switching while paused or stopped stays quiet.
- The Stop button has been removed. A long press on Play now stops playback; a short
  press still toggles play and pause. This applies to both the on-screen button and
  the remote's confirm button.

### Fixed

## [v0.0.40]

### Added

- The library remembers which song was selected and restores it on the next start,
  scrolling straight to it instead of jumping back to the first track. The song
  starts from the beginning when you press Play — playback is not auto-resumed.
- Shuffle stays on across restarts.
- While a music folder is being scanned, the folder browser shows the scan
  progress instead of an empty panel or the folders of the previous scan.

### Fixed

- Bluetooth headset and steering-wheel media buttons now work straight after
  launch. Play, next and pause previously did nothing until playback had been
  started once from inside the app.
- A media button pressed while the library is still being scanned is honoured
  once the scan finishes, instead of being ignored.
- The folder browser could open without any folders although the library was
  loaded: after the app screen had been rebuilt while music kept playing (for
  example after closing the app from the recent-apps list and opening it again),
  and after a start until all song details had loaded. It now lists the folders as
  soon as the scan has found them.

### Notes

This file starts here; releases before it were not tracked in a changelog.
