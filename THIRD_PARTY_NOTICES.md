# Third-Party Notices

SubFlow bundles and depends on the following third-party software.

## Vendored

### whisper.cpp
- https://github.com/ggerganov/whisper.cpp
- Pinned at commit `7395c70a748753e3800b63e3422a2b558a097c80`
- Path: `app/src/main/cpp/whisper.cpp/`
- MIT, Copyright (c) 2023-2024 The ggml authors (see its `LICENSE`)

Included as plain source; the upstream `.git` was removed. To update, drop in a newer checkout of
that commit and bump the pin above.

## Gradle dependencies

Pulled from Maven Central / Google's Maven at build time (see `app/build.gradle.kts`). Each keeps
its own license:

- kotlinx-coroutines, AndroidX (Core, Activity, Lifecycle, Compose, Navigation, Room, Media3,
  WorkManager), Material3: Apache-2.0
- OkHttp, okio: Apache-2.0
- jsoup: MIT
- ML Kit text recognition: Google APIs Terms of Service
- ffmpeg-kit-min (com.antonkarpenko): LGPL-3.0, dynamically linked
- junrar: UnRar/BSD-style
- XZ for Java (org.tukaani): public domain
- jlibtorrent (com.frostwire): MIT

Consult each artifact's POM for the authoritative license.
