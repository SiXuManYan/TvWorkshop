# TV Workshop

[![中文](https://img.shields.io/badge/Language-%E4%B8%AD%E6%96%87-red?style=for-the-badge)](README.zh-CN.md)

An Android IPTV/live-TV player that browses, searches, and plays public live channels on the device, with favorites, watch history, m3u/m3u8 subscription import, and automatic source failover.

Current source version: `1.0`. Requires Android 10 (API 29) or later. Built with Kotlin, Android Views/XML, ViewBinding, and MVVM. The [Chinese README](README.zh-CN.md) is the primary documentation.

## Features

- **Channel browsing** with categories: Recent, CCTV (incl. CGTN), Satellite TV, Foreign channels, and Custom (imported). 66 built-in channels.
- **Favorites** and **recently watched** history, stored locally in Room.
- **Search** by channel name with 250 ms input debouncing.
- **Import m3u / m3u8 subscriptions** by URL or pasted playlist text; duplicate channels merge sources, re-imports are idempotent, and download + parsing are streamed line by line (no whole-file buffering).
- **Automatic source rotation**: when a stream fails, the player tries the next source in order and stops at a clear error state after the last one, with a bounded auto-retry (at most 2 attempts, cancelled by any manual action); manual switching and retry are always available.
- **Quality selection** for multi-variant HLS streams: Auto (bandwidth-adaptive), Lock-highest, or a fixed height (e.g. 1080p). Hidden for single-variant sources. Hardware decoding is preferred, with software fallback to avoid black screens.
- **About entry** in the main menu telling end users where the built-in sources come from, the copyright boundary, how failures are handled, and the privacy stance (same wording as the store listing).
- **Landscape fullscreen player** with double-tap / horizontal-swipe channel zapping, an on-screen channel OSD, picture-in-picture support, a 5-second auto-hiding control bar, a searchable channel sheet, and prev/next switching. Live-tuned buffer sizes for faster startup. No background playback.

## Important Notes on Built-in Channels

All built-in channels and their stream URLs are collected from publicly available third-party m3u playlists. Availability is not guaranteed and changes over time. Copyright of the streamed content belongs to its respective rights holders. This project is for learning and research only.

## Privacy

Everything (favorites, history, imported channels) is processed and stored locally. The app has no accounts, analytics, or data uploads; it only accesses the public stream URLs you play. The manifest requests the `INTERNET` permission only.

## Build

Open the repository root in Android Studio and let Gradle sync, or run:

```bash
./gradlew :app:assembleDebug
```

Toolchain: AGP 9.3.3 (built-in Kotlin), Gradle Wrapper 9.5.0, KSP 2.3.11, compileSdk/targetSdk 37, minSdk 29. See [Technical Design](project-docs/TECHNICAL_DESIGN.md) for architecture, data flow, Room schema, m3u parsing rules, and the player/source-rotation design. Long-term engineering rules live in [AGENTS.md](AGENTS.md).
