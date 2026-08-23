# Emulator visual regression suite

`scripts/visual-regression.py` builds the debug APK, installs it on a pinned
emulator, drives a small set of representative UI paths, and compares the
rendered app surface with checked-in PNG goldens in `expected/`.

The suite intentionally covers the visual failures that are easiest to
reintroduce while modernising this legacy UI:

- light event list;
- light expanded event group, including compact app rows;
- light package grouping;
- light overflow menu;
- dark event list; and
- dark expanded event group, including label contrast.

Each expected image is cropped to the application window. Status-bar clocks,
notification icons, and the gesture pill are system UI, so including them would
make comparisons flaky.

## Pinned emulator profile

Goldens are tied to the existing `Pixel_2_API_33` AVD in portrait:

- Android API 33;
- 1080 × 1920 pixels;
- 420 dpi; and
- the emulator's installed system-package set.

Start that emulator before using the suite. It is deliberately not launched by
the script:

```bash
emulator -avd Pixel_2_API_33 -gpu host
```

The harness refuses profiles with a different size or density before it clears
app state, so it cannot accidentally use the connected Pixel. When more than
one ADB device is available, select the emulator explicitly:

```bash
AUTOSTARTS_SCREENSHOT_SERIAL=emulator-5554 \
  uv run scripts/visual-regression.py verify
```

The project uses Gradle with JDK 17. The script itself has no Python
third-party dependencies.

## Commands

```bash
# Build, install, capture every scene, and compare it with the checked-in PNGs.
AUTOSTARTS_SCREENSHOT_SERIAL=emulator-5554 \
  uv run scripts/visual-regression.py verify

# Deliberately refresh every checked-in baseline after reviewing a visual change.
AUTOSTARTS_SCREENSHOT_SERIAL=emulator-5554 \
  uv run scripts/visual-regression.py record
```

`verify` writes the captured images to `app/build/visual-screenshots/`. On a
mismatch it also emits a red-highlighted `*-diff.png` there. `record` is the
only mode that overwrites the versioned PNG goldens.

The harness disables emulator animation scales, sets the requested light or
dark system mode, clears only the emulator's Autostarts app data, waits for the
receiver scan to finish, and requires three identical app-only frames before
capturing each scene. Refresh baselines only on the pinned AVD and review PNG
diffs like any other source change.
