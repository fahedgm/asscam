# Changelog

## v1.7 — since v1.6

- **Fixed** landscape photos coming out 180° rotated. Root cause: a sign
  error in the orientation formula — minus where it should have been plus.
  The accelerometer-based rotation detection added in v1.5 was correct;
  only the arithmetic combining it with `SENSOR_ORIENTATION` was wrong.
  Worth knowing: two conflicting versions of this formula circulate, both
  from Google (the `JPEG_ORIENTATION` docs use plus, Google's own Camera2
  app source uses minus for the back camera). Settled by real device
  measurement rather than documentation. Also worth knowing: portrait
  produces the same result under either sign, so portrait testing alone
  can never catch this — only landscape can.

## v1.6 — since v1.5

- **Removed** the temporary "Rotation test" diagnostic button and its
  underlying override mechanism (`setOrientationOverride()`) — it did its
  job identifying the accelerometer-based fix in v1.5. Note: only one of
  the two landscape directions was directly confirmed before removal.

## v1.5 — since v1.4

- **Fixed** the landscape orientation bug for real this time (hopefully) —
  root cause identified: `Display.rotation`, read through this app's own
  locked-portrait Activity context, was very likely stuck reflecting the
  locked window orientation rather than the phone's true physical rotation.
  Portrait appeared to work in v1.4 purely by coincidence (the stuck value
  happened to match what portrait needs); landscape didn't. Replaced with
  `OrientationEventListener`, which reads the accelerometer directly and
  has no relationship to the window lock at all.
- Diagnostic "Rotation test" override button still in place as a safety
  net — this is the third automatic approach shipped for this exact bug.

## v1.4 — since v1.3

- **Re-implemented** the device-rotation-aware orientation fix that v1.3
  reverted. The revert itself turned out to be the mistake — confirmed this
  time with a real, controlled on-device test using a new temporary
  diagnostic tool (a button cycling a manual orientation override through
  0°/90°/180°/270°) rather than relying on an earlier, apparently unreliable
  before/after report. Portrait confirmed correct at 90°, landscape
  confirmed correct at 0°, exactly matching the formula's output — so the
  formula was right all along.
- **Added**: temporary on-device orientation diagnostic (`setOrientationOverride()`
  in `CameraController`, "Rotation test" button in the UI). Left in place
  as a low-cost safety net rather than removed immediately, since only one
  of the two landscape directions has been directly confirmed so far.
- DNG orientation, previously logged as a separate open question, now looks
  like it was never a distinct bug — it was inheriting the same incomplete
  orientation value JPEG was during the v1.3 revert window. Should be
  correct now without any change to the DNG-specific code, though this is
  inference from the JPEG fix and still wants a direct confirmation.

## v1.3 — since v1.2

- **Reverted** the landscape-rotation fix from v1.2. It was based on a
  plausible-sounding but empirically wrong theory (that this app's
  portrait-locked Activity still needed live `Display.rotation`
  compensation). Direct before/after device testing showed the original
  simple approach — just `SENSOR_ORIENTATION`, no device-rotation logic —
  was already correct for JPEG in landscape, and the v1.2 "fix" broke it.
  Back to the simple version.
- DNG orientation remains an open question after this revert — see "Open
  question, not yet resolved" under RAW (DNG) capture specifics in the
  README for the current state and the actual next diagnostic step.

## v1.2 — since v1.1

- **Fixed**: photos taken in landscape (phone physically rotated, even
  though the app's layout stays locked to portrait) came out tilted — both
  the JPEG and the DNG. The app was only ever using the sensor's fixed
  mounting angle, never checking the phone's actual current physical
  rotation. Fixed by combining both, computed once per capture and shared
  identically across the JPEG tag, the DNG's orientation, and the
  thumbnail, so all three can never disagree with each other.
- **Removed**: shadow/highlight recovery feature entirely (the "Recover"
  button and everything behind it). Was working as designed but wasn't
  wanted going forward.
- **Icon**: resized again, this time to a genuine middle ground (40% of
  canvas) between the original 60% (clipped on real launchers) and the
  34% correction (felt too small) — checked numerically against the actual
  safe-zone circle each time, not just by eye.

## v1.1 — since the v1 baseline

- **Fixed**: DNG files had no rotation metadata (`DngCreator.setOrientation()`
  was never called — it's entirely independent from `JPEG_ORIENTATION`,
  which only ever applies to the JPEG stream). Every DNG saved before this
  fix will have the same problem; worth re-shooting anything you need
  correctly-oriented RAW files for.
- **Removed manual focus.** Turning a focus dial precisely with no live
  sharpness feedback proved genuinely hard to use well in practice. Focus
  is now always continuous autofocus, in both Auto and Manual mode — Manual
  mode is ISO + shutter speed only. The properly engineered fix (real-time
  focus peaking) was considered and set aside as a bigger, less-proven
  undertaking than anything else in this app.
- **Added**: on-demand local/adaptive shadow-highlight recovery — a
  "Recover" button next to the last-shot thumbnail runs local tone recovery
  and saves a separate, clearly-labeled file; the original capture is never
  touched. Includes clip-protection so genuinely blown highlights (or
  crushed shadows) are left alone rather than flattened into gray patches.

## v1 — reference baseline

The first complete, stable version of 35PHOTO. Marked as a deliberate
checkpoint to compare future changes against.

**Capture**
- Best-resolution JPEG (quality 100) by default; optional RAW + JPEG
  (`.dng` from the same exposure) via a switch.
- Two exposure modes: **Auto** (normal auto-exposure + continuous
  autofocus) and **Manual** (ISO, shutter speed, and focus distance all
  user-controlled via sliders, seeded from Auto's most recent real reading
  when switched on).
- Cosmetic/computational processing stripped where the hardware allows it:
  edge sharpening off, noise reduction minimal, no scene modes, no color
  effects, no chromatic-aberration correction, no multi-frame HDR/fusion of
  any kind, single frame per capture.
- Shutter slider capped at 1 second (or the device's real max, if lower),
  logarithmic, with a diagnostic label showing the device's raw uncapped
  ceiling alongside the current value.

**UI**
- Viewfinder locked to 3:4 to match the actual photo's proportions, pinned
  to the top of the screen; all controls live in the black area below,
  never overlapping the image.
- Rule-of-thirds grid overlay and a live focus-state indicator dot (green/
  yellow/red), both purely informational.
- Last-shot thumbnail next to the shutter button, tap to open in the
  system photo viewer.
- Custom app icon and name (35PHOTO), adaptive-icon compliant.

**Known fixed bugs worth remembering**
- RAW image and its capture metadata arrive via independent callbacks with
  no ordering guarantee — DNGs used to silently fail to save when the race
  resolved unfavorably. Fixed by buffering both until they're paired.
- Tap-to-focus was attempted and removed — the view-to-sensor coordinate
  transform behind it disrupted autofocus rather than failing quietly.
  Deliberately not present in this baseline.
- Live histogram + highlight zebra were attempted and removed — pushed the
  capture button off-screen on some screen sizes. The underlying layout bug
  (no scroll fallback) was fixed regardless; the histogram/zebra features
  themselves are not present in this baseline.

See `README.md` for full technical detail on all of the above.
