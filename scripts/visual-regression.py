#!/usr/bin/env python3
"""Record and verify emulator-backed visual regression screenshots.

The reference images deliberately contain only the application surface. System
bars are cropped away because their clock and gesture indicator are not stable
between runs.
"""

import argparse
import hashlib
import os
import pathlib
import struct
import subprocess
import sys
import time
import zlib


PACKAGE = "com.elsdoerfer.android.autostarts"
ACTIVITY = PACKAGE + "/.ListActivity"
ROOT = pathlib.Path(__file__).resolve().parent.parent
EXPECTED_DIR = ROOT / "screenshot-tests" / "expected"
OUTPUT_DIR = ROOT / "app" / "build" / "visual-screenshots"
APK = ROOT / "app" / "build" / "outputs" / "apk" / "debug" / "app-debug.apk"

# Pixel_2_API_33 in portrait. These bounds omit the 63 px status bar and the
# 60 px gesture navigation bar, leaving only the app's rendered surface.
SCREEN_WIDTH = 1080
SCREEN_HEIGHT = 1920
CROP_LEFT = 0
CROP_TOP = 63
CROP_RIGHT = 1080
CROP_BOTTOM = 1860


class HarnessError(RuntimeError):
    pass


class Image:
    def __init__(self, width, height, rgba):
        self.width = width
        self.height = height
        self.rgba = rgba


def run(command, *, capture=False, check=True, cwd=None):
    result = subprocess.run(
        command,
        cwd=cwd,
        stdout=subprocess.PIPE if capture else None,
        stderr=subprocess.PIPE if capture else None,
    )
    if check and result.returncode:
        if capture:
            detail = result.stderr.decode("utf-8", "replace").strip()
        else:
            detail = ""
        raise HarnessError("command failed: {}{}".format(
            " ".join(command), ": " + detail if detail else ""))
    return result.stdout if capture else b""


def png_chunk(kind, data):
    return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data) & 0xFFFFFFFF)


def paeth(left, above, upper_left):
    prediction = left + above - upper_left
    left_distance = abs(prediction - left)
    above_distance = abs(prediction - above)
    upper_left_distance = abs(prediction - upper_left)
    if left_distance <= above_distance and left_distance <= upper_left_distance:
        return left
    if above_distance <= upper_left_distance:
        return above
    return upper_left


def read_png(path):
    raw = pathlib.Path(path).read_bytes()
    signature = b"\x89PNG\r\n\x1a\n"
    if not raw.startswith(signature):
        raise HarnessError("{} is not a PNG".format(path))

    position = len(signature)
    header = None
    compressed = bytearray()
    while position < len(raw):
        if position + 12 > len(raw):
            raise HarnessError("{} has a truncated PNG chunk".format(path))
        length = struct.unpack(">I", raw[position:position + 4])[0]
        kind = raw[position + 4:position + 8]
        data_start = position + 8
        data_end = data_start + length
        if data_end + 4 > len(raw):
            raise HarnessError("{} has a truncated PNG payload".format(path))
        data = raw[data_start:data_end]
        if kind == b"IHDR":
            header = struct.unpack(">IIBBBBB", data)
        elif kind == b"IDAT":
            compressed.extend(data)
        elif kind == b"IEND":
            break
        position = data_end + 4

    if header is None:
        raise HarnessError("{} has no PNG header".format(path))
    width, height, bit_depth, color_type, compression, filtering, interlace = header
    if bit_depth != 8 or color_type not in (2, 6) or compression != 0 or filtering != 0 or interlace != 0:
        raise HarnessError(
            "{} must be a non-interlaced 8-bit RGB or RGBA PNG".format(path))

    source_channels = 4 if color_type == 6 else 3
    stride = width * source_channels
    encoded = zlib.decompress(bytes(compressed))
    expected_length = (stride + 1) * height
    if len(encoded) != expected_length:
        raise HarnessError("{} has an unexpected decompressed size".format(path))

    rows = bytearray()
    prior = bytearray(stride)
    cursor = 0
    for _ in range(height):
        filter_kind = encoded[cursor]
        cursor += 1
        scanline = bytearray(encoded[cursor:cursor + stride])
        cursor += stride
        if filter_kind not in (0, 1, 2, 3, 4):
            raise HarnessError("{} has an unsupported PNG filter".format(path))
        for index in range(stride):
            left = scanline[index - source_channels] if index >= source_channels else 0
            above = prior[index]
            upper_left = prior[index - source_channels] if index >= source_channels else 0
            if filter_kind == 1:
                scanline[index] = (scanline[index] + left) & 0xFF
            elif filter_kind == 2:
                scanline[index] = (scanline[index] + above) & 0xFF
            elif filter_kind == 3:
                scanline[index] = (scanline[index] + ((left + above) // 2)) & 0xFF
            elif filter_kind == 4:
                scanline[index] = (scanline[index] + paeth(left, above, upper_left)) & 0xFF
        rows.extend(scanline)
        prior = scanline

    if source_channels == 4:
        return Image(width, height, bytes(rows))

    rgba = bytearray(width * height * 4)
    source = 0
    destination = 0
    while source < len(rows):
        rgba[destination:destination + 3] = rows[source:source + 3]
        rgba[destination + 3] = 255
        source += 3
        destination += 4
    return Image(width, height, bytes(rgba))


def write_png(path, image):
    path = pathlib.Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    stride = image.width * 4
    scanlines = bytearray()
    for offset in range(0, len(image.rgba), stride):
        scanlines.append(0)  # no filter; makes goldens simple and portable
        scanlines.extend(image.rgba[offset:offset + stride])
    payload = (
        b"\x89PNG\r\n\x1a\n"
        + png_chunk(b"IHDR", struct.pack(">IIBBBBB", image.width, image.height, 8, 6, 0, 0, 0))
        + png_chunk(b"IDAT", zlib.compress(bytes(scanlines), 9))
        + png_chunk(b"IEND", b"")
    )
    path.write_bytes(payload)


def crop(image):
    if image.width != SCREEN_WIDTH or image.height != SCREEN_HEIGHT:
        raise HarnessError(
            "expected a {}x{} screenshot, got {}x{}".format(
                SCREEN_WIDTH, SCREEN_HEIGHT, image.width, image.height))
    width = CROP_RIGHT - CROP_LEFT
    height = CROP_BOTTOM - CROP_TOP
    result = bytearray(width * height * 4)
    destination = 0
    source_stride = image.width * 4
    crop_stride = width * 4
    for row in range(CROP_TOP, CROP_BOTTOM):
        start = row * source_stride + CROP_LEFT * 4
        result[destination:destination + crop_stride] = image.rgba[start:start + crop_stride]
        destination += crop_stride
    return Image(width, height, bytes(result))


def visual_diff(expected, actual):
    if (expected.width, expected.height) != (actual.width, actual.height):
        raise HarnessError("cannot diff screenshots with different dimensions")
    result = bytearray(len(expected.rgba))
    differences = 0
    for offset in range(0, len(expected.rgba), 4):
        expected_pixel = expected.rgba[offset:offset + 4]
        actual_pixel = actual.rgba[offset:offset + 4]
        if expected_pixel == actual_pixel:
            # Keep a dimmed version of matching pixels as context in the diff.
            result[offset] = expected_pixel[0] // 4
            result[offset + 1] = expected_pixel[1] // 4
            result[offset + 2] = expected_pixel[2] // 4
        else:
            differences += 1
            delta = max(abs(expected_pixel[channel] - actual_pixel[channel]) for channel in range(3))
            result[offset] = 255
            result[offset + 1] = max(0, 255 - delta)
            result[offset + 2] = 0
        result[offset + 3] = 255
    return differences, Image(expected.width, expected.height, bytes(result))


class Device:
    def __init__(self, adb, serial):
        self.adb = adb
        self.serial = serial

    def command(self, *arguments, capture=False, check=True):
        return run([self.adb, "-s", self.serial, *arguments], capture=capture, check=check)

    def shell(self, *arguments, check=True):
        return self.command("shell", *arguments, capture=True, check=check).decode("utf-8", "replace")

    def validate_profile(self):
        size = self.shell("wm", "size")
        density = self.shell("wm", "density")
        if "Physical size: {}x{}".format(SCREEN_WIDTH, SCREEN_HEIGHT) not in size:
            raise HarnessError("visual goldens require a {}x{} emulator; got: {}".format(
                SCREEN_WIDTH, SCREEN_HEIGHT, size.strip()))
        if "Physical density: 420" not in density:
            raise HarnessError("visual goldens require 420 dpi; got: {}".format(density.strip()))

    def prepare(self):
        # These settings make rendering deterministic. They are intentionally best
        # effort: some physical devices restrict individual settings to the shell.
        for setting in (
            ("global", "window_animation_scale", "0"),
            ("global", "transition_animation_scale", "0"),
            ("global", "animator_duration_scale", "0"),
            ("system", "font_scale", "1.0"),
        ):
            self.command("shell", "settings", "put", *setting, check=False)
        self.command("shell", "cmd", "uimode", "night", "no", check=False)
        self.command("shell", "am", "force-stop", PACKAGE, check=False)
        self.command("shell", "pm", "clear", PACKAGE, check=False)
        # LoadTask logs this only from onPostExecute(), after its asynchronous
        # package-manager scan has supplied the complete list.
        self.command("logcat", "-c", check=False)

    def install_and_launch(self):
        self.command("install", "-r", "-d", str(APK))
        self.command("shell", "am", "start", "-W", "-n", ACTIVITY)
        deadline = time.monotonic() + 30
        while time.monotonic() < deadline:
            focus = self.shell("dumpsys", "window", "windows", check=False)
            if PACKAGE in focus and "ListActivity" in focus:
                self.wait_for_initial_load()
                return
            time.sleep(0.5)
        raise HarnessError("{} did not become the foreground activity".format(ACTIVITY))

    def wait_for_initial_load(self):
        deadline = time.monotonic() + 60
        while time.monotonic() < deadline:
            logs = self.command(
                "logcat", "-d", "-v", "brief", "-s", "Autostarts:D", "*:S",
                capture=True,
                check=False,
            )
            if b"Loading receivers took" in logs:
                return
            time.sleep(0.5)
        raise HarnessError("the initial receiver scan did not finish within 60 seconds")

    def wait_for_text(self, text, present=True):
        deadline = time.monotonic() + 15
        marker = 'text="{}"'.format(text).encode("utf-8")
        while time.monotonic() < deadline:
            self.command("shell", "uiautomator", "dump", "/sdcard/autostarts-window.xml", check=False)
            hierarchy = self.command("exec-out", "cat", "/sdcard/autostarts-window.xml", capture=True, check=False)
            if (marker in hierarchy) == present:
                return
            time.sleep(0.5)
        expectation = "find" if present else "dismiss"
        raise HarnessError("did not {} {!r} in the visible UI hierarchy".format(expectation, text))

    def screenshot(self):
        png = self.command("exec-out", "screencap", "-p", capture=True)
        temp = OUTPUT_DIR / "raw.png"
        temp.parent.mkdir(parents=True, exist_ok=True)
        temp.write_bytes(png)
        return crop(read_png(temp))

    def stable_screenshot(self, scene):
        # The initial receiver scan is awaited through LoadTask's completion log.
        # Three identical app-only frames then prevent recording a still-animating
        # popup or a transient frame.
        deadline = time.monotonic() + 40
        previous_hash = None
        matching_frames = 0
        while time.monotonic() < deadline:
            image = self.screenshot()
            image_hash = hashlib.sha256(image.rgba).digest()
            if image_hash == previous_hash:
                matching_frames += 1
            else:
                matching_frames = 1
                previous_hash = image_hash
            if matching_frames >= 3:
                return image
            time.sleep(1)
        raise HarnessError("{} never produced three stable frames".format(scene))


def choose_serial(adb):
    explicit = os.environ.get("AUTOSTARTS_SCREENSHOT_SERIAL") or os.environ.get("ANDROID_SERIAL")
    if explicit:
        return explicit
    output = run([adb, "devices"], capture=True).decode("utf-8", "replace")
    devices = [
        line.split("\t", 1)[0]
        for line in output.splitlines()[1:]
        if "\tdevice" in line
    ]
    if len(devices) == 1:
        return devices[0]
    if not devices:
        raise HarnessError("no adb device is available; start Pixel_2_API_33 first")
    raise HarnessError("multiple adb devices are connected; set AUTOSTARTS_SCREENSHOT_SERIAL")


def build():
    run([str(ROOT / "gradlew"), "--no-daemon", ":app:assembleDebug"], cwd=ROOT)
    if not APK.is_file():
        raise HarnessError("Gradle completed but did not produce {}".format(APK))


def process_scene(mode, scene, image):
    expected = EXPECTED_DIR / (scene + ".png")
    actual = OUTPUT_DIR / (scene + "-actual.png")
    write_png(actual, image)
    if mode == "record":
        write_png(expected, image)
        print("recorded {}".format(expected.relative_to(ROOT)))
        return True
    if not expected.is_file():
        print("missing {}; run: {} record".format(expected.relative_to(ROOT), pathlib.Path(sys.argv[0]).as_posix()))
        return False
    reference = read_png(expected)
    differences, diff = visual_diff(reference, image)
    if differences:
        diff_path = OUTPUT_DIR / (scene + "-diff.png")
        write_png(diff_path, diff)
        print("FAIL {}: {} changed pixels (actual: {}, diff: {})".format(
            scene,
            differences,
            actual.relative_to(ROOT),
            diff_path.relative_to(ROOT),
        ))
        return False
    print("PASS {}".format(scene))
    return True


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mode", choices=("verify", "record"), help="compare against or replace checked-in goldens")
    parser.add_argument("--skip-build", action="store_true", help="reuse app/build/outputs/apk/debug/app-debug.apk")
    arguments = parser.parse_args()

    adb = os.environ.get("ADB", "adb")
    try:
        serial = choose_serial(adb)
        device = Device(adb, serial)
        device.command("get-state", capture=True)
        device.validate_profile()
        if not arguments.skip_build:
            build()
        device.prepare()
        device.install_and_launch()

        # The legacy platform action bar applies its final action-item state
        # during its first overflow-menu preparation. Warm it once so the main
        # screenshot does not depend on whether Android has prepared that menu.
        device.command("shell", "input", "keyevent", "KEYCODE_MENU")
        device.wait_for_text("Help")
        device.command("shell", "input", "keyevent", "KEYCODE_BACK")
        device.wait_for_text("Help", present=False)

        list_image = device.stable_screenshot("list")
        passed = process_scene(arguments.mode, "list", list_image)

        device.command("shell", "input", "keyevent", "KEYCODE_MENU")
        device.wait_for_text("Help")
        overflow_image = device.stable_screenshot("overflow")
        passed = process_scene(arguments.mode, "overflow", overflow_image) and passed
    except HarnessError as error:
        print("visual regression harness: {}".format(error), file=sys.stderr)
        return 2

    return 0 if passed else 1


if __name__ == "__main__":
    sys.exit(main())
