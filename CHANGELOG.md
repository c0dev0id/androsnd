# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- The library remembers which song was selected and restores it on the next start,
  scrolling straight to it instead of jumping back to the first track. The song
  starts from the beginning when you press Play — playback is not auto-resumed.
- Shuffle stays on across restarts.

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
