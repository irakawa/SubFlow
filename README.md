# SubFlow

Finds a Turkish subtitle for whatever you're watching. If there isn't one anywhere, it grabs an
English subtitle and translates it. Free, no API key needed, and it only ever downloads the
subtitle file, never the video.

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
- Can pull a subtitle track out of a torrent without downloading the video.
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

For personal, lawful use only. SubFlow is a client for publicly available subtitle data; it does
not host or download copyrighted video. You are responsible for following the terms of the sites
it queries and the copyright law where you live.

## License

MIT, see [LICENSE](LICENSE). Third-party components are listed in
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
