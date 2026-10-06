# StandBy-style Dock fork — design

Date: 2026-10-06
Status: awaiting review

## Goal

A personal fork of [Mobinshahidi/Dock](https://github.com/Mobinshahidi/Dock)
(v1.0.1, Apache 2.0) that behaves like iOS StandBy on a MagSafe stand in
landscape:

- swipe sideways between three full-screen pages (Widgets, Photos, Clock);
- swipe up/down on the Clock page to change clock face;
- turn dim and red when the room is dark, driven by the light sensor.

Success: on the phone, on the charger, in landscape, all three behaviours work
and the screen is comfortable to look at in a dark bedroom.

## Decisions made with the user

| Topic | Decision |
|-------|----------|
| Scope | Swipeable pages, red Night Mode, iOS-style clock faces. Side-by-side widget stacks are not in scope. |
| Identity | Separate app: `applicationId = io.github.sharkusmanch.dock`, own signing key, installs beside the original. |
| Pages | Widgets / Photos / Clock, in that order (iOS order). |
| Faces | Digital, Analog, Float. The six existing styles are removed. |
| Night Mode trigger | Light sensor only. The hour schedule is removed. |

## Starting point

Upstream is a View-based `DreamService` app (no Compose, no tests):

- `dream/DockDreamService.kt` — inflates `dream_dock.xml`, one fixed screen,
  `setInteractive(false)`, hour-window night dim via window brightness.
- `dream/AnimatedClockView.kt` — one custom View drawing six digital styles.
- `slideshow/PhotoSlideshowManager.kt` — crossfading photo background (Glide).
- `widget/WidgetHostManager.kt` — `AppWidgetHost` with 1–3 slots in a
  `LinearLayout` "rail".
- `settings/SettingsActivity.kt` + `xml/settings_preferences.xml`.

Build: AGP 8.2.2, Kotlin 1.9.22, Gradle 8.5, compileSdk/targetSdk 34, minSdk 26.

## Architecture

Keep the View system. Add paging with `androidx.viewpager2`.

```
DockDreamService (interactive)
└─ dream_dock.xml: FrameLayout root  ← Night Mode tint + brightness applied here
   └─ ViewPager2 (horizontal)  — PageAdapter, 3 pages
      ├─ WidgetsPage   → WidgetHostManager (existing)
      ├─ PhotosPage    → PhotoSlideshowManager (existing) + small time/date
      └─ ClockPage
         └─ ViewPager2 (vertical) — FaceAdapter, 3 faces
            ├─ DigitalFaceView
            ├─ AnalogFaceView
            └─ FloatFaceView
NightModeController ← light sensor → callback(on/off) → DockDreamService
```

### Units

**`dream/DockDreamService`** (rewritten, smaller). Owns the window, builds the
pager, starts/stops pages with the dream lifecycle, applies Night Mode.
`setInteractive(true)` so touches reach the pager. Exiting: an interactive dream
only wakes on the BACK key, and upstream's immersive-sticky flags make the edge
gestures need two swipes, so the service adds its own exit — a double-tap on
the Photos or Clock page calls `wakeUp()`. Back/home gestures and the power
button still work. The root view keeps `android:keepScreenOn="true"` (upstream
relies on it; `setScreenBright(false)` alone would let the screen sleep).
Persists the current page and
face index to SharedPreferences on change and restores them on start. Default
page on first run: Clock.

**`dream/pages/DreamPage`** (interface) with a two-level lifecycle:

- `attach()` / `detach()` — called once per dream start/stop. Binds widgets and
  starts the widget host listening, loads the first photo, draws each face
  once. This is where today's `WidgetHostManager.start()/stop()` and
  `PhotoSlideshowManager.start()/stop()` go, because both tear down and rebuild
  their views.
- `resume()` / `pause()` — called on page select/deselect. Only starts and
  stops timers (slideshow advance, face redraws). A page that begins scrolling
  into view gets one immediate redraw so it never slides in stale or empty.

The adapter holds all three pages alive (`offscreenPageLimit = 2`), one view
type per position, non-recyclable holders, every page `MATCH_PARENT` (ViewPager2
throws otherwise). The same rules apply to the face pager.

**`WidgetsPage`**. Hosts `WidgetHostManager` in a full-screen container instead
of the 30–35 % rail. `WidgetHostManager` is a changed unit, not just re-hosted:
its hardcoded rail orientation, portrait rail height, show-in-orientation
checks and the `widgets_enabled` gate that makes the container visible
(`createSlotViews`, `isOrientationAllowed`, `setEnabled`/`showRail`) are
replaced by "always visible, fill the page". Its static references to dream
views are cleared in `detach()`. Because the dream is interactive and shows
over the lock screen, widgets are display-only while the device is locked
(`KeyguardManager.isDeviceLocked`): each slot intercepts touches so a widget's
buttons cannot be pressed without unlocking. When the device is unlocked,
tapping a widget fires its click action, which usually opens that app and ends
the dream. In landscape the slots sit side by side
(horizontal), in portrait stacked (vertical) — the reverse of the current rail
orientation. Slot count, slot sizes and widget picking in settings are
unchanged. The "rail height" and "show in portrait/landscape" settings are
removed (the page is always available). With no widgets configured the page
shows a one-line hint pointing at the app's settings.

**`PhotosPage`**. Hosts `PhotoSlideshowManager` with its two ImageViews and
scrim. Adds a small time + date in the bottom-right corner (a `TextClock`).
With no photos picked the page shows a one-line hint.

**`ClockPage`**. Vertical `ViewPager2` of faces.

**`dream/faces/ClockFace`** (interface): `var accentColor: Int`,
`var is24Hour: Boolean`, `var nightMode: Boolean`, `resume()`, `pause()`. Each face is a custom `View`
drawing on `Canvas` and scheduling its own redraws:

- `DigitalFaceView` — time in a heavy rounded sans typeface, sized to fill
  ~85 % of the width; small date line above. Redraws once a minute, aligned to
  the minute boundary. Bundled font: Nunito (OFL), shipped as the variable
  font in `assets/fonts` and loaded at weight 900, since system fonts have no
  reliable rounded-heavy weight.
- `AnalogFaceView` — dial sized to the screen height, 60 ticks (hour ticks
  heavier), hour and minute hands in white, second hand in the accent colour.
  Redraws once a second.
- `FloatFaceView` — oversized numerals, each digit a soft colour derived from
  the accent hue, each drifting a few dp on a slow sine path. Redraws at a
  throttled ~15 fps, dropping to ~4 fps while Night Mode is on (the phone is
  charging all night; keep heat and wakeups down).

**`dream/faces/ClockMath`** (pure Kotlin object, unit tested): hand angles from
h/m/s, 12/24-hour formatting, milliseconds to next minute/second boundary.

**`dream/night/NightModeDecider`** (pure Kotlin, unit tested). The light sensor
is on-change: it reports once on registration and then stays silent while the
room is steady, so the decider cannot rely on a stream of samples. API:
`onSample(lux, nowMs)` records the latest reading; `stateAt(nowMs)` returns
on/off; `nextDeadlineMs()` returns when the pending dwell expires, if any. The
first sample after registration is applied immediately (no 5 s of full
brightness when the dream starts in a dark room). After that:

- turns ON after lux has stayed `< 5` for 5 s continuously;
- turns OFF after lux has stayed `> 15` for 5 s continuously;
- between 5 and 15 it holds its state (hysteresis).

Thresholds are constants, not settings.

**`dream/night/NightModeController`**. Registers `Sensor.TYPE_LIGHT` at
`SENSOR_DELAY_NORMAL` while dreaming, feeds `NightModeDecider`, posts a
re-evaluation on the main handler at `nextDeadlineMs()`, and reports changes to
the service. No sensor → never reports on.

**Night Mode rendering** (in the service). On: put the root view on a hardware
layer with a `ColorMatrixColorFilter` that maps luminance to the red channel
only (so faces, photos and third-party widgets all turn red), and set
`window.attributes.screenBrightness` to `0.01f`. The page background and widget
slot backgrounds are forced to pure black while on — upstream's `#1e1e1d` /
`#2a2a28` would otherwise become a full-screen dim red glow. Off: set
`LAYER_TYPE_NONE` and restore the backgrounds and the brightness override
(system default). The screen's own light cannot cause oscillation: dimming only
lowers lux, so the state latches. Transition by animating
the matrix over ~1 s.

**Settings**. Rewritten preference screen:

- Display: settings theme (kept), OLED black background (kept), Night Mode
  toggle (new, default on).
- Clock: 24-hour toggle (kept), one colour per face (3 prefs, reusing the
  existing colour-picker dialog).
- Photos: pick photos, interval (kept); "enabled" toggle removed.
- Widgets: slot count, per-slot widget and size (kept).
- Autostart guide, About (kept).

Preference keys:

| Key(s) | Fate |
|--------|------|
| `app_theme`, `oled_mode`, `clock_24h`, `battery_enabled` | kept |
| `slideshow_interval`, `pick_photos`, `slideshow_photo_uris` | kept |
| `widget_slot_count`, `manage_slot_N`, `slot_size_N` | kept |
| `auto_start_guide`, `about_license` | kept |
| `night_mode_enabled`, `face_color_digital/analog/float`, `last_page`, `last_face` | new |
| `slideshow_enabled`, `widgets_enabled`, `show_date` | removed |
| clock style, preview, per-style colours, font, font upload, position, font sizes, transition animation | removed |
| date/battery colour and size, night-dim toggle/start/end, brightness, widget rail height, show portrait/landscape | removed |

Every `android:dependency` pointing at a removed key must go with it (a
dangling one crashes the settings screen): the slideshow rows on
`slideshow_enabled`, the nine widget rows on `widgets_enabled`, the date rows on
`show_date`, and the rail rows. `SettingsActivity` code for the style preview,
style colours and font upload is deleted with `AnimatedClockView` and
`ClockStylePreviewPreference`; unused strings and arrays are removed.

The autostart guide's ADB command hardcodes
`com.dock.app/.dream.DockDreamService`; build it from
`ComponentName(context, DockDreamService::class.java).flattenToString()` so it
is right for the new ID. The About row credits and links upstream; it will
point at the fork once the fork is published somewhere.

Battery percentage: one small text overlay on the root, top-right, above the
pager (kept as a toggle), so it does not move with page swipes.

### Identity and build

- `applicationId = "io.github.sharkusmanch.dock"`; Kotlin `namespace` stays
  `com.nousresearch.dock` to keep the diff against upstream small.
- App label "Dock StandBy" so both installs are distinguishable.
- `versionName` 2.0.0, `versionCode` 3.
- Debug builds for development. Release signing with a new keystore; hosting
  releases for Obtainium is a follow-up step, confirmed with the user before
  anything is published.
- `LICENSE` and upstream attribution kept (Apache 2.0); a NOTICE line records
  the fork.
- `.github/workflows/build.yml` builds a release on every push to `main` and
  publishes a GitHub Release on any `v*` tag, using secrets the fork does not
  have. Reduce it to a manual (`workflow_dispatch`) debug build until release
  hosting is decided.
- Add `androidx.viewpager2:viewpager2` and `testImplementation("junit:junit:4.13.2")`.
- Local prerequisites: the Mac has only SDK platform 36 / build-tools 35.0.0 and
  no cached Gradle 8.5. The first build needs platform 34 and build-tools
  34.0.0 (AGP auto-download or `sdkmanager`) and downloads Gradle 8.5. JDK 21
  is supported by this toolchain. The emulator image is API 36, which runs a
  targetSdk 34 app.
- Upstream tracks both `SKILLS.md` and `skills.md`, which collide on macOS and
  show as a permanent modification. Remove one in the fork.

## Error handling

- No light sensor: Night Mode silently unavailable; the toggle is disabled with
  a summary line saying so.
- No photos / no widgets: hint text, page still swipeable.
- Widget host errors: existing handling in `WidgetHostManager` is kept.
- Orientation change: the service rebuilds the pager and restores page/face
  from prefs (same teardown/rebuild pattern upstream uses). Other
  configuration changes (theme, locale, font scale) do not rebuild.
- Photos are decoded at screen size, and a photo that fails to load is
  skipped.

## Testing

- JUnit unit tests (new `app/src/test`): `ClockMath` (angles, formatting,
  boundary delays) and `NightModeDecider` (first sample applied immediately,
  single sample then silence still flips at the deadline, enter, exit,
  hysteresis hold, flicker rejection, no samples ever). Written test-first.
- `./gradlew assembleDebug testDebugUnitTest` must pass.
- Visual check on the local Android emulator: start the dream, swipe all pages
  and faces, set the emulator's virtual light sensor to 0 and to 100 lux and
  confirm Night Mode toggles; open Settings and every sub-screen (catches
  dangling preference dependencies).
- On-device acceptance (needs the phone): system UI also claims vertical swipes
  over a dream — swipe-up from the bottom edge (unlock) and swipe-down from the
  top (notification shade). Confirm face swipes started mid-screen work, that
  double-tap exits, and that the device's own low-light screensaver (Pixel "Low
  light mode" on recent Android, if present) is disabled so it does not replace
  this dream in the dark.

## Known risks

- If the phone's system UI captures vertical swipes across the whole dream
  window rather than just the edges, the face carousel needs a different
  gesture (e.g. tap left/right halves). Only an on-device test settles this.
- Digital and Analog faces are static; burn-in is negligible at Night Mode
  brightness but real over long daytime use. Pixel shifting stays out of scope
  for this version.
- Minimum effective brightness for a `0.01f` window override is device-specific.

## Out of scope

Side-by-side widget stacks, World and Solar faces, alarm indicator, burn-in
pixel shifting, full-screen notifications / Live Activities, per-charger
remembered views, contributing upstream.
