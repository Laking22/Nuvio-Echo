<div align="center">

  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="assets/brand/echo_logo_dark.png" />
    <img src="assets/brand/echo_logo_light.png" alt="Nuvio Echo" width="520" />
  </picture>

  <p>
    A fast media app for Android TV, with a glass look.
    <br />
    Bring your own addons. Nuvio Echo turns them into a library with artwork, trailers, ratings, subtitles, and your place saved.
  </p>

  <a href="https://github.com/Laking22/Nuvio-Echo/releases/latest"><b>Download the latest release</b></a>

</div>

## Download

| Your device | File |
| --- | --- |
| Most Android TV and Google TV boxes (32-bit) | [NuvioEcho-armeabi-v7a.apk](https://github.com/Laking22/Nuvio-Echo/releases/latest/download/NuvioEcho-armeabi-v7a.apk) |
| 64-bit devices (NVIDIA Shield, newer Google TV devices) | [NuvioEcho-arm64-v8a.apk](https://github.com/Laking22/Nuvio-Echo/releases/latest/download/NuvioEcho-arm64-v8a.apk) |
| Not sure | [NuvioEcho-universal.apk](https://github.com/Laking22/Nuvio-Echo/releases/latest/download/NuvioEcho-universal.apk) (works on any device, bigger download) |

To install, open the APK on your TV with a file manager or the Downloader app and allow installs from that app when asked. A newer APK installs over the old one and keeps your settings.

No API keys are built in. Add your own TMDB key, and optionally a Gemini key for smarter recommendations, in **Settings → Integrations → API keys**.

## Features

**Speed**
- Addons load fast: connections to addon servers are opened ahead, and streams are fetched as soon as you rest on a title or episode.
- Playback starts quickly, and every speed-up has its own switch under Settings → Beta speed-ups.

**Player**
- A glass player with minimal controls and the title's logo.
- Quality menu, Auto quality that switches without stopping, and automatic fallback when a source fails.
- Audio languages from every source in one menu.
- Thumbnails while you seek, and intro and credits skipping.
- A short intro animation when playback starts.

**Home and browsing**
- A glass interface with an optional edge-to-edge hero.
- Smart Highlights, Because You Watched and For You rows (optionally ranked by Gemini), and hero-sized Grand rows.
- Trailers in rows and on the hero, with a sound setting.
- Search and Genres tabs, voice search, and a card size slider.

**Pages**
- A details page with reviews, ratings, the cast's filmographies, and release reminders.
- Library bins, and a Cloud tab for files in your debrid services.
- A Calendar of upcoming episodes, anime mode, a detailed parental guide, and a speed test.

## Build from source

```bash
git clone https://github.com/Laking22/Nuvio-Echo.git
cd Nuvio-Echo
./gradlew :app:assembleFullBeta
```

The APKs land in `app/build/outputs/apk/full/beta/`. Nuvio Echo is built with Kotlin, Jetpack Compose, TV Material 3, and Media3; you need Android Studio (or a JDK 17+) and the Android SDK. Optional settings go in `local.properties`, with `local.example.properties` as the template.

## License

Nuvio Echo is free software under the [GNU General Public License v3.0](./LICENSE). It is a modified version of NuvioTV, also licensed under the GPL-3.0, and keeps that project's copyright notices as the license requires.
