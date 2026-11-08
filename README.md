# TV Workshop

[![中文](https://img.shields.io/badge/Language-%E4%B8%AD%E6%96%87-red?style=for-the-badge)](README.zh-CN.md)

An Android IPTV/live-TV player for personal technical learning and research. It browses, searches, and plays third-party stream URLs, with favorites, watch history, m3u/m3u8 playlist import, and automatic source failover.

Current source version: `1.0`. Requires Android 10 (API 29) or later. Built with Kotlin, Android Views/XML, ViewBinding, and MVVM. The [Chinese README](README.zh-CN.md) is the primary documentation.

## Features

- **Channel browsing** with categories: Recent, CCTV (incl. CGTN), Satellite TV, Foreign channels, and Custom. 66 built-in channels and 169 stream URLs. Imported channels are categorized by name and group, so they may appear outside Custom.
- **Favorites** and **recently watched** history, stored locally in Room.
- **Search** by channel name with 250 ms input debouncing.
- **Import m3u / m3u8 playlists** by URL or pasted text. Same-name entries merge within each import; existing channel IDs are skipped. Downloads are parsed line by line, while parsed results remain in memory. Imports are snapshots, with no scheduled subscription refresh.
- **Automatic source rotation**: on playback errors, the player tries subsequent sources in order. Failure at the last source shows an error state, with at most 2 retries of the current source, 8 seconds apart. Manual retry, source switching, or channel switching cancels a pending retry.
- **Quality selection** for streams with multiple video heights: Auto, Highest, or a height limit (e.g. 1080p). Hidden for single-height sources. Decoder fallback is enabled; available decoders depend on the device.
- **About entry** in the main menu explaining playlist references, content rights, source failures, and privacy.
- **Landscape fullscreen player** with double-tap / horizontal-swipe channel zapping, a channel OSD, picture-in-picture, a 5-second auto-hiding control bar, a searchable channel sheet, and prev/next switching. The startup buffer threshold is 800 ms; actual startup time depends on the stream, network, and device. Playback continues in picture-in-picture; other background scenarios release the player.

## Built-in Channels and Source Attribution

Thanks to [fanmingming/live](https://github.com/fanmingming/live), [iptv-org/iptv](https://github.com/iptv-org/iptv), and other contributors to public playlist resources. Selected entries are stored in [BuiltinChannels.kt](app/src/main/java/com/sugar/tv/data/builtin/BuiltinChannels.kt).

| Reference | Scope that can be checked |
|---|---|
| [fanmingming/live](https://github.com/fanmingming/live) | Its [tv/m3u/ipv6.m3u](https://github.com/fanmingming/live/blob/936a3a8d11a6b65cc93d659939459dcd7553cd3b/tv/m3u/ipv6.m3u) matches 18 primary CCTV URLs and 6 primary CGTN URLs. The CCTV URLs use IPv6; the CGTN URLs use HTTPS domain names. |
| [iptv-org/iptv](https://github.com/iptv-org/iptv) | The China, Australia, and news playlists, plus the raw Korean stream file, contain some domestic URLs, CGTN alternatives, and foreign-channel URLs used here. |
| [Guovin/iptv-api](https://github.com/Guovin/iptv-api) | Named in the original catalog comments; some URLs can be found in a [historical output file](https://github.com/Guovin/iptv-api/blob/6f10e865173f8e73c58eb3aab78315051f336824/output/result.m3u). The current project is a tool/framework and no longer updates the old output links. |

Checked on **2026-11-08**: 81 of the 169 built-in URLs were found in the inspected upstream files, ignoring a trailing empty `?` on the upstream CCTV URLs. The remaining 88 lack reproducible per-URL provenance in this review. The complete catalog cannot be attributed solely to `fanmingming/live`. See [Technical Design, section 5](project-docs/TECHNICAL_DESIGN.md#5-内置频道目录) for pinned file versions and comparison scope. A playlist match does not verify playback or content authorization.

The built-in catalog ships with the app and is not synchronized with those repositories at startup. The device requests the selected stream and its playlists, media segments, and related playback resources directly. Playlist curators may differ from stream operators; this app has no official affiliation with those projects or broadcasters. Primary/backup labels describe attempt order, with no guarantee of availability, quality, or official status. Some URLs require IPv6 or particular regional/operator access.

Users can import playlists they are authorized to use. An import saves the parsed channels and stream URLs, without retaining the subscription URL for later refresh. New URLs for a name already in the built-in catalog are saved as a separate imported channel. Re-importing an existing ID skips it rather than updating its sources.

The app does not host, relay, transcode, or record streams; playback still receives and buffers media. Content rights remain with the respective rights holders. Public accessibility and a learning purpose do not grant permission to use or redistribute content. Upstream repository licenses apply separately from broadcast-content rights; see the [license references](project-docs/TECHNICAL_DESIGN.md#53-来源角色与许可边界). Report incorrect attribution, broken links, or rights concerns through this repository's Issues with the channel name, URL, and relevant details. Removing a built-in entry currently requires a source change and an app update.

## Privacy

Favorites, history, and imported channels are processed and stored in a local Room database. The app has no accounts, analytics/crash-reporting SDKs, or developer-operated data collection service, and does not report those records. The manifest requests the `INTERNET` permission only.

Network access downloads user-supplied playlists and requests playback resources. Those services receive ordinary network request information, including the IP address and requested URL, under their own logging policies. Android backup is enabled (`allowBackup=true`) and the Room database is not excluded, so data may be included in system cloud backup or device transfer depending on device settings and the backup service.

## Build

Open the repository root in Android Studio and let Gradle sync, or run:

```bash
./gradlew :app:assembleDebug
```

Toolchain: AGP 9.3.3 (built-in Kotlin), Gradle Wrapper 9.5.0, KSP 2.3.11, compileSdk/targetSdk 37, minSdk 29. See [Technical Design](project-docs/TECHNICAL_DESIGN.md) for architecture, data flow, Room schema, m3u parsing rules, and the player/source-rotation design. Long-term engineering rules live in [AGENTS.md](AGENTS.md).
