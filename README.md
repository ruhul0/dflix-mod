# DFLIX TV

An Android TV client for [dflix.live](https://dflix.live): browse, search and stream the library, and find subtitles online.

## Features

- Home rows (Trending, Recently Added, per-language rows), Movies / TV Shows / Anime grids with sort and language filters, and search.
- A details page with seasons and episodes, resume, and "More like this".
- Direct playback of the original MKV/MP4 files with ExoPlayer. The FFmpeg audio decoder plays AC3/E-AC3/DTS/TrueHD on TVs without passthrough.
- Subtitles:
  - Subtitle tracks inside the file, plus subtitles that dflix provides.
  - Online search with **Find subtitles** in the player. OpenSubtitles.org works without a key and matches by file hash first, so results fit the exact release. Optional keys add OpenSubtitles.com and SubDL (Settings).
  - Downloaded subtitles are converted to UTF-8, saved per file and loaded automatically next time.
  - **Subtitle sync** shifts downloaded subtitles earlier or later.
- Continue Watching, and autoplay of the next episode.
- Remote, arrow keys and keyboard: arrows and Enter navigate, Esc goes back. In the player, Space plays/pauses and Left/Right seek 10 s.
- Self-update: on launch the app checks this repo's latest GitHub release and offers to install it.

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
```

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
