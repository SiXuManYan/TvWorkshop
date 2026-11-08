# TV Workshop

[![中文](https://img.shields.io/badge/Language-%E4%B8%AD%E6%96%87-red?style=for-the-badge)](README.zh-CN.md)

### Special Thanks

Special thanks to the authors and maintainers of the open-source projects and third-party libraries used here, and to the friends who tested the app and shared feedback. Thank you for your time, ideas, and support ❤️

This project is made possible by the following open-source projects, third-party components, and content sources:

- [fanmingming/live](https://github.com/fanmingming/live) — Reference for part of the built-in TV channel list and stream URLs, including some primary CCTV and CGTN sources. Thank you to its authors and maintainers for curating these playlists.
- [iptv-org/iptv](https://github.com/iptv-org/iptv) — Reference for some domestic and international stream URLs and categorized playlists.
- [Guovin/iptv-api](https://github.com/Guovin/iptv-api) — IPTV source-collection tooling; some built-in URLs are traceable to historical output files.
- [Kotlin](https://github.com/JetBrains/kotlin) — Application language and standard library.
- [kotlinx.coroutines](https://github.com/Kotlin/kotlinx.coroutines) — Coroutines, asynchronous work, and Flow-based state.
- [Material Components for Android](https://github.com/material-components/material-components-android) — Material widgets, themes, and dialogs.
- [AndroidX / Jetpack](https://developer.android.com/jetpack/androidx) — Core, Annotation, AppCompat, Lifecycle, ConstraintLayout, and RecyclerView for UI, lists, and screen state.
- [AndroidX Media3](https://github.com/androidx/media) — ExoPlayer, HLS, and player UI components for live-stream playback.
- [Room](https://developer.android.com/jetpack/androidx/releases/room) — Local database for favorites, recently watched channels, and imported channels.
- [Markwon](https://github.com/noties/Markwon) / [CommonMark Java](https://github.com/commonmark/commonmark-java) — Markdown parsing and rendering for offline Chinese documents. The page structure follows CollageWorkshop and RadioWorkshop.
- [JUnit 4](https://github.com/junit-team/junit4) / [AndroidX Test and Espresso](https://github.com/android/android-test) — JVM unit-test and Android UI-test infrastructure.
- [Gradle](https://github.com/gradle/gradle) / [Android Gradle Plugin](https://android.googlesource.com/platform/tools/base/) / [KSP](https://github.com/google/ksp) — Build tooling and compile-time symbol processing for Room.

See [Built-in Channels and Source Attribution](#built-in-channels-and-source-attribution) for playlist provenance and the distinction between playlist projects, stream services, and content rights holders.

---

An Android IPTV/live-TV player for personal technical learning and research. It browses, searches, and plays third-party stream URLs, with favorites, watch history, m3u/m3u8 playlist import, and automatic source failover.

Current source version: `1.0`. Requires Android 10 (API 29) or later. Built with Kotlin, Android Views/XML, ViewBinding, and MVVM. The [Chinese README](README.zh-CN.md) is the primary documentation.

## Features

- **Channel browsing** with categories: Recent, CCTV (incl. CGTN), Satellite TV, Foreign channels, and Custom. 66 built-in channels and 162 stream URLs. Imported channels are categorized by name and group, so they may appear outside Custom.
- **Favorites** and **recently watched** history, stored locally in Room. Browse-page optimistic updates confirm the matching click generation; the player favorite icon follows the database observation flow.
- **Search** by channel name with 250 ms input debouncing, background filtering, and restoration of the active query after screen recreation.
- **Import m3u / m3u8 playlists** by a single-line subscription URL or pasted text, including multiple bare URL lines. Same-name entries merge within each import with ordered source deduplication; existing IDs are skipped atomically in Room and only actual insertions are counted. Downloads are parsed line by line, while parsed results remain in memory. Imports are snapshots, with no scheduled subscription refresh.
- **Automatic source rotation**: on playback errors, the player tries subsequent sources in order. Failure at the last source shows an error state, with at most 2 retries of the current source, 8 seconds apart. Manual retry, source switching, or channel switching cancels a pending retry.
- **Quality selection** for streams with multiple video heights: Auto, Highest, or a height limit (e.g. 1080p). Hidden for single-height sources. An unavailable height falls back to Auto and clears the old limit; temporary empty tracks during loading preserve the preference. Decoder fallback is enabled; available decoders depend on the device.
- **About screen** in the main menu showing the current build version and linking to the offline changelog, user agreement, and privacy policy (Simplified Chinese for this increment). Includes chapter navigation, scroll highlighting, back to top, animated version-card expansion, and the existing source information.
- **Landscape fullscreen player** with immersive system bars and gradient shading along the top and bottom edges. Previous, channel list, and next remain directly accessible; playback settings group source selection, quality, and picture-in-picture. Double-tap / horizontal-swipe zapping shows a brief corner channel hint while controls are hidden. Controls normally hide after 3 seconds, staying visible during keyboard/remote focus, screen-reader interaction, or settings use. The searchable channel sheet opens expanded at the current channel and offers a clear-search action. The startup buffer threshold is 800 ms; actual startup time depends on the stream, network, and device. Playback continues in picture-in-picture; other background scenarios release the player.

A static performance and correctness review on **2026-11-09** fixed repeated targets during rapid zapping, stale quality limits, favorite-update races, full-list filtering on the main thread, and import deduplication. Controls are restored on return from the background or picture-in-picture, and missing channels clear the previous video. Regression test code was added; builds, tests, Lint, and device checks were not run in this review, and no measured performance improvement is claimed. See the [review record](project-docs/TECHNICAL_DESIGN.md#2026-11-09-性能与正确性审查记录).

UI refinements on the same day preserve the main list position after recreation, center loading/empty states below the toolbar with keyboard avoidance, and allow two-line channel names and wrapping metadata. Import fields now separate the label from the instructions and scroll long text. Playback error actions remain scrollable, keyboard focus is visible across the main/player/document screens, and About entries have separators. These changes received static checks only; device appearance and interaction remain to be verified. See the [UI review record](project-docs/TECHNICAL_DESIGN.md#13-ui-审查与调整2026-11-09).

Further refinements on the same day group secondary player actions in an edge menu and allow a system-bar swipe reveal. Channel names no longer appear over the center of the video. Version cards now use the reference apps’ 240 ms height, opacity, and arrow animation, reverse from their current progress, and defer chapter positioning until heights settle. See the [implementation and static-check record](project-docs/TECHNICAL_DESIGN.md#14-播放页精简与文档折叠动画2026-11-09).

## Version and Legal Documents

Open **About** from the main toolbar overflow menu to read the [changelog](docs/version-history/CHANGELOG.md), [user agreement](docs/legal/USER_AGREEMENT.md), or [privacy policy](docs/legal/PRIVACY_POLICY.md). These three documents and their new UI strings are intentionally Simplified Chinese only for now. They ship in the APK for offline reading; existing app translations remain in place. The changelog separates work in progress from the 1.0 feature baseline. The app version has not been changed and no release date has been invented.

The XML/ViewBinding reader adapts the chapter pills, version cards, and local-document structure used in CollageWorkshop and RadioWorkshop. Legal sections stay expanded, while version cards animate their body height, opacity, and arrow together. Initial display, recreation, and disabled system animations restore the target state directly. Reading position and expansion state restore after recreation. Legal cross-links stay inside the app; external HTTP(S) links show the target for confirmation and then open in the system browser. No WebView is used. Markwon core 4.6.2 renders documents in the background without a remote-image plugin. User-facing `docs/` are bundled; engineering `project-docs/` are not.

## Built-in Channels and Source Attribution

Thanks to [fanmingming/live](https://github.com/fanmingming/live), [iptv-org/iptv](https://github.com/iptv-org/iptv), and other contributors to public playlist resources. Selected entries are stored in [BuiltinChannels.kt](app/src/main/java/com/sugar/tv/data/builtin/BuiltinChannels.kt).

| Reference | Scope that can be checked |
|---|---|
| [fanmingming/live](https://github.com/fanmingming/live) | Its [tv/m3u/ipv6.m3u](https://github.com/fanmingming/live/blob/936a3a8d11a6b65cc93d659939459dcd7553cd3b/tv/m3u/ipv6.m3u) matches 18 primary CCTV URLs and 6 primary CGTN URLs. The CCTV URLs use IPv6; the CGTN URLs use HTTPS domain names. |
| [iptv-org/iptv](https://github.com/iptv-org/iptv) | The China, Australia, and news playlists, plus the raw Korean stream file, contain some domestic URLs, CGTN alternatives, and foreign-channel URLs used here. |
| [Guovin/iptv-api](https://github.com/Guovin/iptv-api) | Named in the original catalog comments; some URLs can be found in a [historical output file](https://github.com/Guovin/iptv-api/blob/6f10e865173f8e73c58eb3aab78315051f336824/output/result.m3u). The current project is a tool/framework and no longer updates the old output links. |

Historical attribution check on **2026-11-08**, before source cleanup: 81 of the then 169 built-in URLs were found in the inspected upstream files, ignoring a trailing empty `?` on the upstream CCTV URLs; 88 lacked reproducible per-URL provenance in that review. The complete catalog cannot be attributed solely to `fanmingming/live`. See [Technical Design, section 5](project-docs/TECHNICAL_DESIGN.md#5-内置频道目录) for pinned file versions and comparison scope. A playlist match does not verify playback or content authorization.

**Content sampling on 2026-11-09** checked HTTP/playlists for all 169 original URLs. Of 124 HLS candidates, 118 yielded a decoded video frame. Further checks of suspicious routes and their recorded redirect destinations identified seven URLs for CCTV-2, CCTV-16, Guangdong, Shandong, Shanxi, Ningxia, and Shaanxi that returned unrelated shopping feeds. These URLs were removed, leaving 66 channels and 162 URLs. Some later requests returned the expected programs, so removal reflects observed misrouting rather than a claim of permanent advertising. Timeouts, inaccessible IPv6 routes, test cards, and ordinary in-channel ads were not grounds for removal. User-imported data was not changed. See the [per-route evidence and sampling limits](project-docs/TECHNICAL_DESIGN.md#54-广告错配线路清理2026-11-09); the remaining sources are not guaranteed to stay correct.

The built-in catalog ships with the app and is not synchronized with those repositories at startup. The device requests the selected stream and its playlists, media segments, and related playback resources directly. Playlist curators may differ from stream operators; this app has no official affiliation with those projects or broadcasters. Primary/backup labels describe attempt order, with no guarantee of availability, quality, or official status. Some URLs require IPv6 or particular regional/operator access.

Users can import playlists they are authorized to use. An import saves the parsed channels and stream URLs, without retaining the subscription URL for later refresh. New URLs for a name already in the built-in catalog are saved as a separate imported channel. Re-importing an existing ID skips it rather than updating its sources.

The app does not host, relay, transcode, or record streams; playback still receives and buffers media. Content rights remain with the respective rights holders. Public accessibility and a learning purpose do not grant permission to use or redistribute content. Upstream repository licenses apply separately from broadcast-content rights; see the [license references](project-docs/TECHNICAL_DESIGN.md#53-来源角色与许可边界). Report incorrect attribution, broken links, or rights concerns through this repository's Issues with the channel name, URL, and relevant details. Removing a built-in entry currently requires a source change and an app update.

## Privacy

Favorites, history, and imported channels are processed and stored in a local Room database. The app has no accounts, analytics/crash-reporting SDKs, or developer-operated data collection service, and does not report those records. The manifest requests the `INTERNET` permission only.

Network access downloads user-supplied playlists and requests playback resources. Those services receive ordinary network request information, including the IP address and requested URL, under their own logging policies. Android backup is enabled (`allowBackup=true`) and the Room database is not excluded, so data may be included in system cloud backup or device transfer depending on device settings and the backup service.

The bundled [Chinese privacy policy](docs/legal/PRIVACY_POLICY.md), accessible through **About → 隐私政策**, also explains HTTP and token-bearing URLs, what deleting imported channels actually removes, system backup, and voluntary feedback.

## Build

Open the repository root in Android Studio and let Gradle sync, or run:

```bash
./gradlew :app:assembleDebug
```

Toolchain: AGP 9.5.1 (built-in Kotlin), Gradle Wrapper 9.6.1, KSP 2.3.13, compileSdk/targetSdk 37, minSdk 29. See [Technical Design](project-docs/TECHNICAL_DESIGN.md) for architecture, data flow, Room schema, m3u parsing rules, and the player/source-rotation design. Long-term engineering rules live in [AGENTS.md](AGENTS.md).
