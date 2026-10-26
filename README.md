# DFLIX TV

A Netflix-style Android TV and phone client for [dflix.live](https://dflix.live): browse, search and stream the library, with subtitles found online and the phone working as a remote.

## Features

**Browsing**
- Netflix-style home: billboard, Continue Watching, My List, Top 10, Top Picks for You and dflix's own rows. The last home screen shows instantly from cache while fresh rows load.
- TV Shows / Movies / Anime grids with sort and language filters; search with recent searches, voice search (🎤 or the remote's Search key) and **✨ Ask AI** ("space movies like Interstellar").
- Title pages with seasons and episodes, resume, trailers (YouTube), and a "More like this" carousel.
- A red **NEW EPISODES** tag on followed shows that gained episodes (checked daily).
- Remote, arrow keys and keyboard throughout; Back returns focus to the card you came from.

**Playback**
- Plays the original MKV/MP4 files with ExoPlayer; the FFmpeg decoder handles AC3/E-AC3/DTS/TrueHD on TVs without passthrough. Network drops are retried from the same position.
- **Audio & Subtitles** panel: audio tracks ([Original], Dolby/DTS, 5.1), English subtitle tracks, online search, subtitle timing, speed, sleep timer and text size.
- English subtitles load automatically: embedded tracks first, otherwise the best online match (OpenSubtitles.org without a key; optional OpenSubtitles.com and SubDL keys). Matches are checked against the video's length, and advert lines are removed.
- "Next episode" card with a countdown that skips the credits; picture-in-picture; on phones, double-tap to skip and swipe for brightness/volume.

**TV and phone together**
- **Play on TV**: the phone app finds DFLIX TVs on the same Wi-Fi, sends a title or episode at the phone's position, and becomes a remote (pause, ±10 s, stop). No setup or adb.
- Continue Watching and My List sync between phone and TV on the same Wi-Fi; the newest change wins.
- Shows you're watching appear in the Google TV / Android TV home screen's Play Next row.
- **Enter keys with your phone**: Settings shows a QR code; paste API keys on the phone instead of typing them with the remote.

**AI (optional)** — Top Picks for You and Ask AI use free OpenRouter models. Add a free key in Settings (or with your phone). The AI only names titles; each is looked up in the library, so every pick is playable.

**Updates** — the sideload build checks this repo's latest GitHub release on launch and offers to install it.

## Install on a TV (no adb)

Latest APK, always this URL: https://github.com/ruhul0/dflix-mod/releases/latest/download/dflix-tv.apk

- **Downloader app** (free on the Play Store / Amazon Appstore): open it, enter the URL above, and choose Install.
- **USB stick:** copy `dflix-tv.apk` onto it, then open it on the TV with a file manager (e.g. File Commander, X-plore).

Allow "Install unknown apps" for whichever app opens the APK. After that, the app updates itself.

## Google Play build

There are two build flavors:

| Flavor | Package | Updates | Output |
|---|---|---|---|
| `sideload` | `com.dflixtv.app` | GitHub self-updater | `dflix-tv.apk` on each release |
| `play` | `com.dflixtv.app.play` | Google Play | `dflix-tv-play.aab` on each release, uploaded by hand to the Play **internal testing** track |

## Releasing

Releases are built on your machine; GitHub only hosts them. With `gh` logged in and `keystore/keystore.properties` present:

```sh
scripts/release.sh                 # release notes = last commit message
scripts/release.sh "What changed"  # custom notes
```

The script picks the next `v1.0.N` from the existing tags, builds the signed APK and Play bundle with that number as `versionCode`, pushes `main` and publishes the GitHub release with `dflix-tv.apk` and `dflix-tv-play.aab`. Installed sideload apps see the newer build and offer to update; on first update, Android asks you to allow installs from DFLIX TV.

For Play, upload `dist/dflix-tv-play.aab` to **Testing → Internal testing** in Play Console.

Keep `keystore/` backed up and out of git. If the key is lost, installed apps can't update and have to be uninstalled first.

## Build locally

```sh
./gradlew assembleSideloadRelease  # needs keystore/keystore.properties
adb install -r app/build/outputs/apk/sideload/release/app-sideload-release.apk
./gradlew testSideloadDebugUnitTest  # parsing, subtitle and sync tests (release.sh runs them too)
```

Optional: `openrouter.key=…` in `local.properties` (git-ignored) builds in an OpenRouter key for AI suggestions. Without it, each viewer adds their own in Settings. The released APK is public, so a built-in key can be extracted by anyone.

## Phone ↔ TV on the local network

The TV app advertises `_dflixtv._tcp` over mDNS (port 8611, or any free port) and serves plain HTTP on the LAN:

| Request | Purpose |
|---|---|
| `GET /status` | What's playing, for the phone's remote |
| `POST /play` `title`, `file`, `pos` | Start a file at a position (only while DFLIX is open on the TV) |
| `POST /control` `action=toggle\|back\|forward\|stop` | Remote buttons |
| `POST /sync` `data=<json>` | Merge Continue Watching and My List; returns the merged state |

## How it talks to dflix.live

| Data | Source |
|---|---|
| Search | `GET /api/search?q=&limit=20` |
| Browse grids | `GET /api/browse?kind=movie|tv|all&anime=1&page=&sort=added|rating|year|title&order=&language=` |
| Home rows, title details, episodes | `/` and `/title/{id}` requested with the `RSC: 1` header (Next.js payload, parsed in `data/Rsc.kt`) |
| Player info (subtitles, next episode) | `/watch/{titleId}?e={fileId}` with `RSC: 1` |
| Video | `GET /api/stream/{fileId}` (range requests) |
| Images | `/api/image/{poster|backdrop|still}/{path}` |

These endpoints are undocumented. If dflix changes its site, parsing in `DflixApi.kt` may need updating.
