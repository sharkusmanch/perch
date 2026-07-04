# Dock — Minimal Futuristic Charging Screensaver for Android

A native Android screensaver (DreamService) inspired by the *functionality* of iOS StandBy mode — clock, live widgets, optional photo background, auto-activates while charging — with its own **original minimal/futuristic visual identity**.

---

## Features

- DreamService registers as system screensaver; clock + date on warm dark background
- Settings screen with slideshow / widgets toggles
- Photo slideshow: system picker, crossfade, scrim overlay, interval
- Live widgets: `AppWidgetHost` integration, slot management, layout resize
- Night auto-dim (accent tint), responsive clock sizing, spring animations
- **6 clock styles** — Default, Bubble, Neon, Gradient, Mono, Outline
- **Per-style color pickers** (with hex input + RGB sliders)
- **Bubble per-digit colors** — individual color for each digit + colon
- **Gradient multi-color picker** — 2+ colors, add/remove, live preview
- **Independent date & battery colors** — separate from clock color
- **Per-orientation widget visibility** — show/hide rails in portrait/landscape
- **Shake animation** — accelerometer triggers per-digit spring + sine drift on all styles
- **Custom font upload** — pick `.ttf`/`.otf` from device storage
- **OLED mode** — true black background
- **About link** — opens GitHub repo in browser

---

## Screenshots

<p align="center">
  <img src="screenShots/dock_screensaver1.png" width="140">
  <img src="screenShots/dock_screensaver2.png" width="140">
  <img src="screenShots/dock_screensaver3.png" width="140">
  <img src="screenShots/dock_screensaver4.png" width="140">
  <img src="screenShots/dock_screensaver5.png" width="140">
  <img src="screenShots/dock_screensaver6.png" width="140">
  <img src="screenShots/dock_screensaver7.png" width="140">
</p>

---

## Design Language

- **Background:** `#1e1e1d` (warm near-black) or `#000000` (OLED option)
- **Primary text:** `#c3c2b7` (warm off-white)
- **Accent:** `#d57455` (terracotta — active states, glow, progress)
- **Typography:** Thin/light weight (`fontWeight=300`), large clock (~120sp)

---

## Requirements

- Android 8.0+ (API 26) — `minSdk 26` for stable `AppWidgetHost`
- Target Android 14 (API 34)
- GrapheneOS / stock AOSP compatible
- **No network permission** — the app makes zero network calls
- **No broad storage permission** — uses system photo picker only
- **No analytics / telemetry / ads** — fully open source

---

## Build

```bash
# CI builds automatically on push/PR to main via GitHub Actions.
# No local Gradle build step required — download APK from Actions artifacts.
```

### GitHub Actions

Workflow: `.github/workflows/build.yml`
Triggers: push to `main`, PRs, version tags (`v*`)
Artifacts:
- `Dock-debug.apk` — every push/PR (debug-signed, installable)
- `Dock.apk` — signed release APK on tags (signed via CI keystore)

**For a debug build you can install directly:** download `Dock-debug-apk` artifact and run `adb install app-debug.apk`.

---

## How to get a signed Release APK

The CI signs release APKs automatically. Tag and push:

```bash
git tag v1.0.0
git push origin v1.0.0
```

A GitHub Release will be created with `Dock.apk` attached, signed and ready to sideload.

---

## Installing on Device

1. Download `Dock-debug.apk` from the latest GitHub Actions run
2. Install via `adb install Dock-debug.apk` or transfer to device
3. Open **Settings → Display → Screen saver** → select **Dock**
4. Set "When to start screen saver" → **While charging**
5. Plug in and enjoy

---

## Project Structure

```
Dock/
├── app/
│   ├── src/main/
│   │   ├── java/com/nousresearch/dock/
│   │   │   ├── dream/           # DockDreamService + AnimatedClockView
│   │   │   ├── settings/        # SettingsActivity + ClockStylePreviewPreference
│   │   │   ├── slideshow/       # PhotoSlideshowManager — crossfade engine
│   │   │   └── widget/          # WidgetHostManager — AppWidgetHost integration
│   │   ├── res/
│   │   │   ├── layout/
│   │   │   │   ├── dream_dock.xml
│   │   │   │   └── dream_dock_land.xml
│   │   │   ├── xml/
│   │   │   │   ├── dream_config.xml
│   │   │   │   └── settings_preferences.xml
│   │   │   ├── values/
│   │   │   │   ├── colors.xml
│   │   │   │   ├── strings.xml
│   │   │   │   ├── themes.xml
│   │   │   │   └── attrs.xml
│   │   │   └── drawable/
│   │   └── AndroidManifest.xml
│   ├── build.gradle.kts
│   └── proguard-rules.pro
├── .github/workflows/build.yml
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
└── LICENSE
```

---

## License

Apache 2.0 — see [LICENSE](LICENSE)
