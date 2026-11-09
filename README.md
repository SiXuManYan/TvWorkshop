# TV Workshop

[![中文](https://img.shields.io/badge/Language-%E4%B8%AD%E6%96%87-red?style=for-the-badge)](README.zh-CN.md)

TV Workshop is an Android live-TV player for personal technical learning and research. It browses, searches, and plays third-party stream URLs on the device, with favorites, watch history, m3u/m3u8 import, source failover, quality selection, and picture-in-picture.

The current source version is `1.0` and the minimum supported Android version is Android 10 (API 29). The app uses Kotlin, Android Views/XML, ViewBinding, and MVVM. Long-term engineering rules are in [AGENTS.md](AGENTS.md), and the [Chinese README](README.zh-CN.md) is the primary user guide.

## At a glance

| Area | Current behavior |
| --- | --- |
| Built-in catalog | 66 channels and 162 stream URLs, shipped with the app and not synchronized at startup |
| Import | A single HTTP(S) subscription URL or pasted m3u/m3u8 text; each import is a snapshot |
| Playback | Fullscreen and picture-in-picture; normal backgrounding stops playback |
| Local data | Favorites, watch history, and imported channels are stored locally; Android may include them in backup or device transfer |
| Network | The device requests subscription and playback resources directly from third parties; the app does not send telemetry |
| Offline documents | About provides the changelog, user agreement, privacy policy, and channel-source notes |

## Features

### Browsing and import

- Browse Recent, CCTV, Satellite TV, Foreign channels, and Custom categories.
- Search by channel name with 250 ms input debouncing, background filtering, and query restoration after recreation.
- Favorite a channel from the list or player, and find recently requested channels in watch history.
- Import a subscription URL or paste playlist text, including multiple bare URLs. Same-name entries in one import are merged with ordered source deduplication.
- A same-name entry with a new URL is stored separately from the built-in channel. Re-importing an existing entry does not update its other sources. Imported channels are categorized by name and group, so they may appear outside Custom.
- “Clear custom channels” removes all imported channels while keeping favorites and watch history. Subscriptions are never refreshed automatically.

### Playback and zapping

- On playback errors, try later sources in order. After the last source fails, retry the current source every 8 seconds, at most twice.
- Previous channel, channel list, and next channel stay in the bottom bar. Source selection, quality, and picture-in-picture are available in the settings menu.
- For streams with multiple video heights, choose Auto, Highest, or a height limit. If a new source does not provide the selected height, the player returns to Auto.
- Double-tap the left or right half of the video, or make a qualifying horizontal swipe, to change channels. When controls are hidden, a short channel hint appears at the top edge instead of covering the center of the picture.
- Controls normally hide after 3 seconds. They stay visible during keyboard or remote focus, screen-reader interaction, and error actions.
- The searchable channel panel shares the page with the video: side by side in wide windows and stacked in narrow windows. Select a channel, close the panel, press Back, or tap the preview to return to fullscreen. The query and list position restore after recreation.
- Enter picture-in-picture when the device supports it. Other background transitions release the player and reload the current source on return.

### About and offline documents

The main menu opens About with the current build version and links to:

- [Changelog](docs/version-history/CHANGELOG.md)
- [User agreement](docs/legal/USER_AGREEMENT.md)
- [Privacy policy](docs/legal/PRIVACY_POLICY.md)
- Channel-source notes, with details in the [source records](project-docs/CHANNEL_SOURCES.md)

About and the three user documents are currently Simplified Chinese only; existing general UI translations remain in place. The documents ship in the APK, support offline reading, chapter navigation, position restoration, and back to top. Version cards expand independently, and the changelog separates work in progress from the `v1.0` baseline.

## Built-in channels and source attribution

The built-in catalog references parts of the public lists or historical outputs from [fanmingming/live](https://github.com/fanmingming/live), [iptv-org/iptv](https://github.com/iptv-org/iptv), and [Guovin/iptv-api](https://github.com/Guovin/iptv-api). Some URLs still lack reproducible per-address provenance. Fixed snapshots, address comparisons, the 2026-11-09 content sampling, and the seven removed misrouted URLs are documented in [Built-in channels and source records](project-docs/CHANNEL_SOURCES.md).

List curators, stream operators, and broadcast rights holders may be different parties. This app has no official affiliation with those projects or broadcasters. “Primary” and “backup” labels describe attempt order only; they do not certify origin, quality, availability, or authorization. Streams can depend on region, operator, IPv6 connectivity, and third-party changes.

The app does not host, relay, or transcode streams, and it has no recording or media-download feature. Playback still receives and buffers media. Public access and a learning purpose do not grant content rights; software and playlist licenses do not grant broadcast rights either. Use only content you are authorized to access, and follow the relevant service terms and applicable law.

Report attribution errors, broken links, or rights concerns through [Issues](https://github.com/SiXuManYan/TvWorkshop/issues). Remove tokens from URLs before posting publicly. Built-in catalog changes require a source edit and an app update.

## Privacy and data boundaries

Favorites, watch history, and imported channels are processed in a local Room database. The app has no accounts, analytics, crash-reporting SDK, or developer-operated cloud sync, and it does not report search terms, playback history, or device identifiers.

Subscription downloads, playlists, media segments, and external webpages still create third-party network requests. Those services may receive the IP address, requested URL, and ordinary request headers. Query parameters in user-provided URLs are sent as entered and may contain tokens. Android backup is enabled and does not exclude the Room database, so system settings may include these records in cloud backup or device transfer.

See the [privacy policy](docs/legal/PRIVACY_POLICY.md) for the full description. Import, playback, and content-use boundaries are in the [user agreement](docs/legal/USER_AGREEMENT.md).

## Build and toolchain

Open the repository root in Android Studio and let Gradle sync, or run:

```bash
./gradlew :app:assembleDebug
```

Current toolchain:

- AGP 9.5.1 with built-in Kotlin;
- Gradle Wrapper 9.6.1;
- KSP 2.3.13;
- compileSdk / targetSdk 37, minSdk 29;
- JDK 17 or later to run AGP, with Java 11 compilation targets;
- Media3 1.11.1, Room 2.8.5, Material 1.14.0, Lifecycle 2.11.0, and Coroutines 1.11.0.

Recent engineering reviews relied on static source and resource checks. Builds, tests, Lint, and device validation were not run for those revisions, and no measured performance or compatibility result is claimed. See [Technical Design](project-docs/TECHNICAL_DESIGN.md) for current behavior, and [Implementation History](project-docs/IMPLEMENTATION_HISTORY.md) for the reasons and verification scope of each revision.

## Documentation map

| Need | Entry point |
| --- | --- |
| User behavior, usage boundaries, and privacy | This page, the [user agreement](docs/legal/USER_AGREEMENT.md), and the [privacy policy](docs/legal/PRIVACY_POLICY.md) |
| Current architecture and implementation | [Technical Design](project-docs/TECHNICAL_DESIGN.md) |
| Built-in channels, attribution, and sampling evidence | [Built-in channels and source records](project-docs/CHANNEL_SOURCES.md) |
| Dated engineering reviews and UI changes | [Implementation History](project-docs/IMPLEMENTATION_HISTORY.md) and the [changelog](docs/version-history/CHANGELOG.md) |
| Long-term development rules | [AGENTS.md](AGENTS.md) |
| Project documentation index | [project-docs/README.md](project-docs/README.md) |
| Chinese entry | [README.zh-CN.md](README.zh-CN.md) |

## Acknowledgements

Thanks to the friends who tested the app and shared feedback, and to the projects and maintainers whose code, tools, lists, and documentation support this project:

- [fanmingming/live](https://github.com/fanmingming/live), [iptv-org/iptv](https://github.com/iptv-org/iptv), and [Guovin/iptv-api](https://github.com/Guovin/iptv-api) for reference channel lists and source tooling;
- [Kotlin](https://github.com/JetBrains/kotlin) and [kotlinx.coroutines](https://github.com/Kotlin/kotlinx.coroutines) for the language, coroutines, and Flow;
- [AndroidX / Jetpack](https://developer.android.com/jetpack/androidx), [Material Components](https://github.com/material-components/material-components-android), [AndroidX Media3](https://github.com/androidx/media), and [Room](https://developer.android.com/jetpack/androidx/releases/room) for UI, lifecycle, local data, and playback;
- [Markwon](https://github.com/noties/Markwon) and [CommonMark Java](https://github.com/commonmark/commonmark-java) for offline Markdown documents;
- [JUnit 4](https://github.com/junit-team/junit4), [AndroidX Test](https://github.com/android/android-test), [Gradle](https://github.com/gradle/gradle), [Android Gradle Plugin](https://android.googlesource.com/platform/tools/base/), and [KSP](https://github.com/google/ksp) for tests and build tooling.

The document reader's chapter navigation, version cards, and interaction patterns follow CollageWorkshop and RadioWorkshop, adapted to this project's XML/ViewBinding implementation.
