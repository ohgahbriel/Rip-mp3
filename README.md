# RIP // MP3 — Android

YouTube → MP3 downloader for Android, built on **youtubedl-android** (yt-dlp + ffmpeg compiled for Android).

## Getting the APK

Every push to this repo automatically builds the APK via GitHub Actions:

1. Go to the **Actions** tab
2. Open the latest successful run
3. Download the **rip-mp3-apk** artifact
4. Unzip → `app-debug.apk` → install on your phone (enable "Install from unknown sources")

## Features

- **Winamp-style player is the home screen** — green LCD deck, spectrum analyzer, marquee title, playlist editor, shuffle/repeat
- **GET SONGS** button opens the download screen; paste a URL, or **share directly from the YouTube app** (Share → RIP MP3)
- **Downloads run in the background** — start a download, go back to the player and listen while it finishes (live status strip on the player)
- **Media controls in the notification shade** — pull down the status bar for prev / play-pause / next from anywhere; playback survives leaving the app
- **Full playlist support** — auto-detected, saved to its own folder with numbered tracks, overall progress across the playlist
- Format/quality: MP3 (best VBR / 320k / 192k) or **FLAC**
- Embedded thumbnail + metadata
- Download archive — re-running a playlist only grabs new tracks
- Auto-updates its internal yt-dlp on launch
- Files save to `Android/data/com.dgabesilva.ripmp3/files/MP3/` and are scanned into the media library

## Legal note

Only download audio you have the rights to — your own uploads, Creative Commons content, or material with the copyright owner's permission. Downloading copyrighted music violates YouTube's Terms of Service.
