# 35PHOTO

A minimal Android camera app that captures with the phone's cosmetic/computational
processing stripped out, using Camera2 directly rather than the system camera app.

## What it does

- **Viewfinder locked to 3:4**, matching the aspect ratio of the actual saved
  photo — the preview isn't stretched or cropped to fill the whole screen, so
  what you frame is what you get. Everything that isn't the photo (status
  text, RAW switch, Auto/Manual selector, manual sliders, shutter button)
  lives in the dedicated black area below the viewfinder, never overlapping
  the image.
- **Preview** with a faint rule-of-thirds grid overlay (a UI aid only — never
  baked into the saved photo).
- **Two modes**, picked via a row shared with the RAW switch:
  - **Auto** (default): normal auto-exposure and continuous autofocus —
    behaves like an ordinary camera.
  - **Manual**: reveals two sliders — ISO and shutter speed — shown only if
    the device actually supports manual sensor control. Switching into
    Manual seeds both from whatever Auto was *actually* doing on the most
    recent frame — a real reading of the current scene, not an arbitrary
    guess — since Auto mode continuously reports real auto-exposure values
    on every frame regardless of whether Manual is selected. The same
    values drive both the live preview and the actual capture, so what's on
    screen is exactly what gets saved. No metering guesswork once you're in
    Manual — every value is exactly what you set it to.
- **Focus is always continuous autofocus, in both modes — never manual.**
  (An earlier version offered a manual focus slider; it was removed because
  turning a focus dial precisely with no live sharpness feedback proved
  genuinely hard to use well, and adding that feedback properly — real-time
  focus peaking — would have been a bigger, less-proven engineering
  undertaking than anything else in this app. Continuous AF is mature and
  reliable; better to lean on it fully than offer a control that's
  difficult to use accurately.)
- **Focus indicator**: a small dot in the viewfinder's corner reflects
  `CONTROL_AF_STATE` in real time — green (focused), yellow (searching), red
  (not focused). Purely informational; doesn't affect capture in any way.
- White balance always stays auto in both modes — a basic necessary
  correction, not a creative control this app exposes.
- **Last-shot thumbnail**: after each capture, a small preview appears next
  to the shutter button; tap it to open the photo in your default viewer.
- **RAW + JPEG switch**: adds a `.dng` (RAW sensor data) from the same
  exposure as the JPEG.
- **Default capture**: best-resolution JPEG, quality 100.
- Everything cosmetic is turned off where the device allows it: edge
  sharpening, aggressive noise reduction, scene modes, color effects,
  chromatic-aberration correction. True in both Auto and Manual.
- Captures are built from `TEMPLATE_PREVIEW` rather than
  `TEMPLATE_STILL_CAPTURE`, and take a single frame — no multi-frame
  HDR/fusion stacking of any kind.

See the comment block at the top of `CameraController.kt` for the full rationale.

## Layout specifics

- The viewfinder box uses ConstraintLayout's `layout_constraintDimensionRatio="3:4"`
  to stay locked to that aspect ratio at any screen size, pinned to the top.
- The controls area below it is split into two parts: a `ScrollView` holding
  the settings (status, RAW+mode row, manual sliders) that scrolls if it ever
  doesn't fit, and the capture button + thumbnail row sitting *outside* that
  scroll view so they're always visible regardless of how much settings
  content there is.
- The shutter button is a plain `View` with an oval `shape` drawable
  background (`res/drawable/shutter_button.xml`) rather than a text-labeled
  button — the standard plain-circle shutter convention nearly every camera
  app uses. It doesn't get Material's built-in disabled styling, so
  `MainActivity` fakes a disabled look with alpha while a capture is in progress.

## Manual mode specifics

- ISO and shutter speed require the device's `MANUAL_SENSOR` capability.
  The whole Auto/Manual selector is disabled if a device doesn't support it.
- **Seeding from real Auto readings.** Every frame, while in Auto mode, the
  preview's `CaptureResult` reports back whatever `SENSOR_SENSITIVITY` and
  `SENSOR_EXPOSURE_TIME` auto-exposure is actually using for the current
  scene — these are cached continuously. The moment Manual is switched on,
  the ISO and shutter sliders are seeded from that cached reading, so the
  starting point reflects the current scene rather than a fixed guess.
  (An earlier version tried to get this same result with a one-time
  "auto-convergence" step — run auto briefly at app launch, wait for
  `CONTROL_AE_STATE` to converge, then snapshot it. That's unnecessary now
  that Auto is a real, continuously-running mode of its own: it's already
  reporting fresh readings on every frame, so there's nothing to wait for.)
- ISO: linear slider across the sensor's supported range. Shutter speed:
  logarithmic (see below).
- Focus is not part of Manual mode — it's always continuous autofocus,
  regardless of which mode is selected. See "What it does" above for why.

## Shutter slider specifics

- The slider's mapped range is capped at 1 second even on sensors that
  support longer exposures (some go to several seconds for night-mode-style
  shooting) — this keeps the whole practical handheld range using the
  slider's full precision instead of compressing it to make room for
  multi-second exposures few people would drag to anyway, and makes exactly
  1.0s cleanly reachable at the slider's own maximum. On a device whose real
  maximum is *below* 1 second, the slider caps at that device's true max
  instead — never claims a capability the hardware doesn't have. This cap
  only affects the slider's UI mapping; nothing about capture itself is
  restricted.
- The shutter label shows both the current value and the device's raw,
  *uncapped* `SENSOR_INFO_EXPOSURE_TIME_RANGE` ceiling side by side — e.g.
  "Shutter 1/10s (device max 1/10s)". Since the app's cap can only ever
  *lower* the slider's ceiling below 1s (never raise it above whatever the
  device itself reports), if the max shown here is well under 1 second, that
  number is coming directly from the phone's own Camera2 HAL. Some phones
  genuinely restrict the public manual-exposure API to a modest ceiling even
  though their stock camera's separate "Night mode" reaches multi-second
  equivalent brightness through proprietary multi-frame stacking that
  bypasses manual sensor control entirely — that mechanism isn't accessible
  through Camera2's public API by any third-party app, this one included.
- Logarithmic mapping, since exposure time spans nanoseconds to (up to) a
  full second and a linear slider would cram all the fast, usable speeds
  into an unusable sliver at one end.

## RAW vs JPEG brightness

The JPEG and DNG for a single shutter press come from **one shared
`capture()` call** targeting both output surfaces with the same
`SENSOR_SENSITIVITY`/`SENSOR_EXPOSURE_TIME` applied once — Camera2
guarantees both come from the same sensor readout, so the actual light
captured is always identical between them, by construction, in every mode.
What can differ is how each file *renders*: the JPEG has a baked-in tone
curve (a fully linear one looks dark and flat, so some curve has always been
left in — see the top of `CameraController.kt`); the DNG is raw, undeveloped,
linear sensor data with none of that applied, and needs a RAW-aware viewer
(Lightroom, Snapseed, etc. — not always a plain gallery app) to look
"correct." This divergence is most visible when a manual shutter speed is
picked far from what auto-exposure would have chosen for that scene — which
is exactly what seeding Manual mode from a real Auto reading (above) is
meant to reduce.

## Photo orientation specifics

**This took five attempts. The false leads are documented so they don't get
retried.**

The app's Activity is locked to portrait, but the phone itself still gets
physically rotated while shooting — so the correct rotation tag depends on
two things: the camera sensor's fixed mounting angle (`SENSOR_ORIENTATION`)
and how the phone is actually being held.

**Attempts 1-3** all used `Display.rotation` to get the phone's rotation.
That was the first real dead end: `Display.rotation` is read through this
app's own Activity context, and that context's window is locked to
portrait, so it gets stuck reflecting the *locked window* rather than the
phone's true physical orientation. Portrait appeared to work, but only by
coincidence (the stuck value happens to match what portrait needs anyway);
landscape never did.

**Attempt 4** replaced it with `OrientationEventListener`, which reads the
accelerometer directly and has no relationship to the window lock at all.
That part was correct and is still what's used. But the formula combining
it with `SENSOR_ORIENTATION` had a **sign error** — minus instead of plus —
which produces correct portrait and 180°-flipped landscape.

**Attempt 5 (current)** fixes the sign. The formula is now
`(sensorOrientation + deviceRotation + 360) % 360`.

**On that sign, because it's a genuine trap**: two conflicting versions of
this formula circulate, both from Google. The official
`CaptureRequest.JPEG_ORIENTATION` documentation uses **plus** (negating
`deviceOrientation` only for front-facing cameras). Google's own Camera2
*app source* uses **minus** for the back camera. They can't both be right
for the same device, and searching for the "correct" formula turns up both.
What settled it was real device measurement, not documentation:

| Orientation | accelerometer | needs | `+` gives | `-` gives |
|---|---|---|---|---|
| Portrait | 0° | 90° | 90° ✓ | 90° ✓ |
| Landscape | 270° | 0° | 0° ✓ | 180° ✗ |

Note the trap in that table: **portrait cannot distinguish the two signs** —
both produce 90°. Only landscape actually tests it. An earlier claim that
the minus version had been "validated against both confirmed data points"
was hollow for exactly this reason.

A temporary on-device diagnostic (a button cycling a manual orientation
override through 0°/90°/180°/270°) was used to establish the ground-truth
values in that table by direct shoot-and-check, then removed once the
values were known. Only one of the two landscape directions was measured;
if the other direction or upside-down portrait is wrong, that's a real
remaining gap.

Computed once per capture and shared identically across the JPEG tag, the
DNG's orientation, and the thumbnail's rotation, so those three can't
disagree with each other.

## RAW (DNG) capture specifics

The RAW image (from its `ImageReader` callback) and its metadata (from the
capture's `CaptureCallback`, needed by `DngCreator`) arrive via two
independent callbacks with no guaranteed ordering — on some devices the RAW
buffer can be ready before the metadata result finalizes. Both are buffered
until their counterpart also arrives, rather than assuming the metadata is
always first; assuming that order silently dropped the DNG (capture still
"completed," just with no file written) whenever it didn't hold.

`DngCreator` also has its own separate orientation setting
(`setOrientation()`, taking an `android.media.ExifInterface` constant) —
completely independent from `CaptureRequest.JPEG_ORIENTATION`, which only
ever applies to the JPEG output stream. An earlier version set
`JPEG_ORIENTATION` correctly but never called `DngCreator.setOrientation()`,
so every DNG was written with no real rotation tag, defaulting to
"unrotated" — while the JPEG, an entirely separate code path, was fine the
whole time. See "Photo orientation specifics" above for the full picture,
including the device-rotation fix that very likely explains this too.

**Update**: at the time this note was first written, DNG orientation was
still reported wrong even after the fix above — which led to real suspicion
that `exifOrientationFor()`'s degree-to-`ExifInterface`-constant mapping was
separately broken. In hindsight, that's very likely a red herring: at that
point, `currentCaptureOrientationDegrees` (the shared value both JPEG and
DNG read from) was itself still wrong for landscape, since the
device-rotation compensation had been mistakenly reverted (see "Photo
orientation specifics"). Since DNG was sharing that same incomplete value,
of course it looked wrong too — there was no need for a second, independent
bug in the EXIF-constant mapping to explain it. With the device-rotation
fix back in place and confirmed correct, DNG should now be right without
the mapping itself ever having needed to change. Worth a quick confirmation
in a RAW-aware viewer (Lightroom, etc.) regardless, since this is inference
from the JPEG fix, not a direct DNG-specific test — same diagnostic
approach that resolved the RAW-vs-JPEG brightness question earlier in this
project.

## App icon

Generated from the uploaded 35PHOTO logo as a proper Android adaptive icon:
the navy background (`#20292F`) is a flat color layer, and the "35 PHOTO"
text was extracted from the source artwork with the background chroma-keyed
to transparent, then scaled to sit within Android's adaptive-icon safe zone
(so it isn't clipped on circular/squircle-masked launchers) at all five
mipmap densities. Since minSdk is 26 — the same version adaptive icons were
introduced in — there's no legacy fallback PNG needed; every supported
device uses `mipmap-anydpi-v26/ic_launcher.xml`.

**Sizing note**: the safe zone that's guaranteed visible on every launcher
mask shape is a *circle* about 61% of the canvas diameter — not a square —
and "35 PHOTO" is a wide, short shape. The original version sized the logo
to 60% of canvas *width*, which put the far left/right edges of the text
right at the edge of that circle with no margin, and it clipped on real
launchers. A follow-up correction dropped to 34%, which fixed the clipping
but read as too small. Settled on **40%** as the working value — checked
numerically (not just by eye) against the actual safe-zone circle each
time: every opaque foreground pixel sits at 85% of the safe radius at most,
a genuine ~15% margin on every side, while being clearly larger than the
34% version.

## Focus indicator specifics

`CONTROL_AF_STATE` is read directly off every preview frame's `CaptureResult`
— no extra camera stream needed, that data is already there as part of the
normal repeating request.

## Last-shot thumbnail specifics

- Decoded straight from the same JPEG bytes already in memory after a
  capture — no extra file read.
- `JPEG_ORIENTATION` is only an EXIF hint; it doesn't rotate the actual pixel
  data. The thumbnail decode applies the same rotation the capture itself
  used, so it isn't shown sideways.
- Tapping it opens the photo via `ACTION_VIEW` on its MediaStore URI — your
  device's default photo viewer, not a viewer built into this app.

## Where files are saved

Photos and DNGs are inserted into the system's **Pictures** collection via
`MediaStore`, under `Pictures/RawCam/` — so they show up immediately in your
phone's Gallery/Photos app. JPEG and DNG from the same capture share a
filename base, e.g. `IMG_20260826_143022.jpg` / `.dng`.

On Android 9 and below, this needs the `WRITE_EXTERNAL_STORAGE` permission
(requested alongside camera permission on first launch); Android 10+ doesn't
need it for this.

## Project structure

```
app/src/main/java/com/example/rawcam/
  MainActivity.kt        UI wiring, permission handling, ISO/shutter
                          slider mapping, last-shot tap-to-view
  CameraController.kt    Camera2 session + capture-parameter policy
  GridOverlayView.kt     Rule-of-thirds grid + focus-state dot
app/src/main/res/        Layout, strings, theme, drawables, icon assets
.github/workflows/       CI build (see below)
```

## Building via GitHub Actions

The workflow at `.github/workflows/build.yml` builds a debug APK on every push to
`main` and uploads it as a workflow artifact. It generates the Gradle wrapper
on the runner itself (pinned to Gradle 8.7) before building — nothing to
install locally.

An optional one-time workflow, `bootstrap-wrapper.yml`, can be run manually
from the Actions tab if you'd rather have the wrapper actually committed to
the repo instead of regenerated every run.

## Building locally

Open the folder in Android Studio (Koala or newer), let it sync, and run on a
physical device — the emulator's virtual camera won't exercise the manual
exposure path meaningfully.

## Known simplifications / next steps

- Preview aspect-ratio transform (fitting the sensor's buffer into the 3:4
  box) is the standard basic version; not tuned for every sensor aspect ratio.
- No sharing/share-sheet integration yet — photos land in Gallery, but there's
  no in-app "share" button (the last-shot thumbnail opens the system viewer,
  which isn't quite the same thing).
- DNG capture silently no-ops on devices that don't report the `RAW`
  capability; the status bar tells you when that's the case. Same
  graceful-degradation pattern for manual controls on devices without
  `MANUAL_SENSOR`.
