# Emulator visual regression tests

`scripts/visual-regression.py` is a repeatable, emulator-backed screenshot test
for the app's main list and overflow menu. It builds the debug APK, installs it
on an emulator, clears the app's state, waits for the UI to stop changing, and
compares the rendered application surface with the checked-in PNG goldens.

The checked-in images are intentionally cropped to the application window:
status-bar time, notification icons, and the gesture pill are system UI and
would make a screenshot test flaky.

## Required emulator profile

The goldens are pinned to the existing `Pixel_2_API_33` profile in portrait:

- Android API 33
- 1080 × 1920 pixels
- 420 dpi
- light system mode

Start it before running the harness:

```bash
emulator -avd Pixel_2_API_33
```

If more than one adb device is connected, select this one explicitly:

```bash
AUTOSTARTS_SCREENSHOT_SERIAL=emulator-5554 ./scripts/visual-regression.py verify
```

The legacy branch uses Gradle 6.5, which needs a compatible JDK such as JDK 11:

```bash
JAVA_HOME="$(/usr/libexec/java_home -v 11)" \
  ./scripts/visual-regression.py verify
```

The modernized branch's Gradle wrapper requires JDK 17.

## Commands

```bash
# Build, install, capture, and compare against screenshot-tests/expected/.
./scripts/visual-regression.py verify

# Deliberately replace the checked-in PNG goldens after reviewing a visual change.
./scripts/visual-regression.py record
```

`verify` writes the captured PNGs to `app/build/visual-screenshots/`. On a
mismatch it also writes a red-highlighted `*-diff.png` there, so the rendered
difference can be inspected without replacing a baseline.

The harness disables emulator animation scales, requests light mode, clears the
app package, and uses an exact device profile. Do not use the goldens as a
cross-device assertion; refresh them only on the pinned profile and review PNG
changes in the corresponding merge request.
