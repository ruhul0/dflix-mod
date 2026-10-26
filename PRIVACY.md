# Privacy Policy: DFLIX TV (Unofficial)

_Last updated: 26 October 2026_

DFLIX TV is an unofficial Android TV client for the dflix.live media library. It is not affiliated with dflix.live.

## Data the app collects

The app has no accounts, analytics or advertising. The only data sent to the developer is diagnostic:

- **Crash reports**, sent automatically when the app crashes, through [Sentry](https://sentry.io): the error and stack trace, app version, device model, Android version and whether it runs on a TV or phone. No names, account details or IP-based identifiers are attached to reports.
- **Bug reports** you choose to send from Settings > Diagnostics: the same device details plus the results of the app's connection checks (whether dflix.live, the subtitle service, the AI service and the phone–TV link respond) and recent crash logs.

This is used only to find and fix bugs. Sentry stores it in the EU and deletes it after its retention period (90 days on the free plan).

## Data stored on your device

These stay on your device and are deleted when you uninstall the app or clear its data:

- Playback positions, "Continue Watching", My List and recent searches
- Subtitle files you download
- Optional API keys you enter in Settings (subtitle providers, OpenRouter)
- The last few crash logs

## Network requests

To work, the app connects directly from your device to:

- **dflix.live**: to browse, search and stream titles. Subject to dflix.live's own terms and privacy practices.
- **Subtitle providers** (OpenSubtitles.org, and, if you add a key, OpenSubtitles.com and SubDL): to search and download subtitles. These requests include the title, season/episode, the IMDb/TMDB ID and a hash of the video file. They don't include anything that identifies you.
- **OpenRouter** (only if an OpenRouter key is set): for "Top Picks for You" and "Ask AI", the app sends the titles in your Continue Watching and My List, or the text you type into Ask AI.
- **GitHub**: to check for app updates.
- **Your local network**: the phone and TV apps find each other on the same Wi-Fi and exchange Continue Watching and My List directly, without any server.

These services receive your IP address as part of any internet request.

## Children

The app is not directed at children under 13.

## Contact

Questions: open an issue at https://github.com/ruhul0/dflix-mod/issues
