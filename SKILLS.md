# Dock Skills

A collection of skills helpful when working with the Dock project.

## Build & CI

### Release build

The CI signs release APKs automatically using a keystore from GitHub secrets. To set it up:

1. Create a keystore: `keytool -genkey -v -keystore dock-release.jks -alias dock -keyalg RSA -keysize 2048 -validity 10000`
2. Base64-encode: `base64 -w0 dock-release.jks`
3. Add secrets to GitHub: `KEYSTORE_B64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`
4. Tag and push: `git tag v0.2.0-beta && git push origin v0.2.0-beta`

The CI will decode the keystore, sign the APK, and attach the signed `app-release.apk` to the GitHub Release.

### Debug build on every push

Download `Dock-debug-apk` artifact — already debug-signed, installable with `adb install`.

### What the CI workflow does

`.github/workflows/build.yml`:
- Decodes the keystore from `KEYSTORE_B64` secret (on tags/main)
- Builds `assembleDebug` + `assembleRelease` (release uses `KEYSTORE_PASSWORD`/`KEY_ALIAS`/`KEY_PASSWORD` secrets)
- Runs lint + unit tests
- Uploads debug APK and signed release APK as artifacts (30-day retention)
- On `v*` tags, creates a GitHub Release and attaches the signed APK

## Key Architecture Decisions

| Decision | Rationale |
|----------|-----------|
| Two layout files (`layout` + `layout-land`) | Runtime `ConstraintSet` manipulation loses `layout_constraintWidth_percent` |
| Per-style color pref keys | Each style stores its own color; `clock_color` is universal fallback for date/battery |
| `showFullColorPicker()` writes two prefs | Writes both the style-specific key and the universal `clock_color` fallback |
| `showSimpleColorPicker()` used for sub-dialogs | Compact color picker with callback for bubble digit / gradient sub-color selection |
| Widget rail height depends on `widget_show_portrait` | Greyed out when portrait widgets are hidden |
| Slot spacer visibility in `updateSlotManageVisibility()` | Uses `PreferenceCategory` spacer keys to hide/show separators cleanly |

## Preferences

### Color keys

| Key | Stores | Used By |
|-----|--------|---------|
| `pref_key_clock_color` | Universal fallback (e.g. `#c3c2b7`) | Date/battery when style-specific is unset |
| `pref_key_clock_color_normal` | Default style color | Default, Neon, Mono, Outline (single-color picker) |
| `pref_key_clock_color_bubble` | Comma-separated hex for 5 digits | Bubble style only (per-digit picker) |
| `pref_key_clock_color_gradient` | Comma-separated hex for 2+ colors | Gradient style only (add/remove picker with live preview) |
| `pref_key_clock_color_neon` | Neon style color | Neon style (single-color) |
| `pref_key_clock_color_gradient` | Gradient style color | Gradient style (multi-color) |
| `pref_key_clock_color_mono` | Mono style color | Mono style (single-color) |
| `pref_key_clock_color_outline` | Outline style color | Outline style (single-color) |
| `pref_key_date_color` | Date text color | `dateDisplay.setTextColor()` |
| `pref_key_battery_color` | Battery text color | `batteryStatus.setTextColor()` |

### Style-to-pref mapping

The following logic in `DockDreamService.applyClockCustomization()` maps style strings to color keys:

| Style string | Pref key |
|-------------|----------|
| `"default"` | `pref_key_clock_color_normal` |
| `"bubble"` | `pref_key_clock_color_bubble` |
| `"neon"` | `pref_key_clock_color_neon` |
| `"gradient"` | `pref_key_clock_color_gradient` |
| `"mono"` | `pref_key_clock_color_mono` |
| `"outline"` | `pref_key_clock_color_outline` |

### Style visibility

In `SettingsActivity.DockSettingsFragment.updateStyleDependentPrefs()`:

| Pref visible when | Style |
|------------------|-------|
| `style == "default"` | Normal color pref |
| `style == "bubble"` | Bubble color pref |
| `style == "neon"` | Neon color pref |
| `style == "gradient"` | Gradient color pref |
| `style == "mono"` | Mono color pref |
| `style == "outline"` | Outline color pref |
