# SubFlow

Finds a Turkish subtitle for whatever you're watching. If there isn't one anywhere, it grabs an
English subtitle and translates it. Free, and no API key needed. What you get out of it is a
subtitle file — see the [Disclaimer](#disclaimer) for what it does with video along the way.

Android app, Kotlin + Jetpack Compose.

## What it does

You give it a title (and season/episode for a series). It searches a set of free subtitle sites
in order, checks that each result is actually the right release, and hands you a Turkish `.srt`.
When no Turkish subtitle exists, it translates an English one itself.

## Features

- Searches many keyless sources: OpenSubtitles.org, Addic7ed, AnimeTosho, Nyaa, Kitsunekko,
  Subtitlecat, YifySubtitles, Archive.org and others. Free OpenSubtitles.com / SubDL / SubSource
  keys are optional and widen coverage.
- Resolves the title to its IMDb id, so it still finds subtitles indexed under a different name
  (e.g. an anime searched by its Japanese title).
- Title autocomplete as you type.
- Translates English subtitles to Turkish when that's all that exists.
- Can pull a subtitle track out of a torrent without fetching the whole video: it downloads
  only the pieces the subtitle track spans and deletes them afterwards. Read the
  [Disclaimer](#disclaimer) before using this one.
- On-device Whisper transcription as a last resort.
- Screenshot, torrent and video inputs, season batches, timing nudge, preview player,
  backup and restore, a home-screen widget, and a few themes.

## Build

Needs JDK 17 and the Android SDK (compileSdk 35). The NDK installs itself on the first build.

```bash
./gradlew testDebugUnitTest   # tests
./gradlew assembleDebug       # debug APK, no signing key required
```

### Signing a release

Signing credentials are not committed. Copy the template and fill it in:

```bash
cp keystore.properties.example keystore.properties
./gradlew assembleRelease
```

Without that file the release build still runs, just unsigned. `keystore.properties`, `*.jks`
and `local.properties` are git-ignored, so keep them out of commits.

## Disclaimer

For personal, lawful use only. SubFlow hosts no content of its own, and what it produces is a
subtitle file.

It does handle video in two places. Neither one fetches a video for you to keep, but both move
video data onto your device, so decide for yourself before using them:

- **Torrent subtitle extraction.** SubFlow joins the torrent's swarm, downloads the pieces of
  the video file that the subtitle track spans, reads the track out of them, and deletes them
  when it is finished. It never fetches the complete video. But video data does reach your
  device, your IP address is visible to that swarm's peers and trackers, and BitTorrent is a
  sharing protocol: while the transfer is running, pieces you already hold can be uploaded to
  other peers. In several jurisdictions it is that upload, rather than the download, that
  creates liability.
- **HTTP video source.** If you give SubFlow a direct video URL, ffmpeg reads from it to pull
  out an embedded subtitle track, or the audio when on-device transcription runs. Only the
  subtitle file and a temporary audio file are written to disk, but the video itself is read
  over the network. The preview player streams from that same URL.

You are responsible for the torrents you point it at, for the terms of the sites it queries, and
for the copyright law where you live.

## License

MIT, see [LICENSE](LICENSE). Third-party components are listed in
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
