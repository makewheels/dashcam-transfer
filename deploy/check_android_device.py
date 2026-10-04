"""Run media recycling and guided UI checks on an isolated emulator, then save review screenshots."""
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import time


def main():
    serial = sys.argv[1]
    if not serial.startswith("emulator-"):
        raise SystemExit("Use an isolated emulator; this check changes development app settings")
    root = Path(__file__).resolve().parents[1]
    output = Path(sys.argv[2]).resolve()
    env = dict(os.environ)
    sdk = Path(env.get("ANDROID_HOME") or env["ANDROID_SDK_ROOT"])
    adb = [str(sdk / "platform-tools/adb"), "-s", serial]
    with tempfile.TemporaryDirectory(prefix="transfer-device-check-") as directory:
        env["DASHCAM_BUILD_ROOT"] = str(Path(directory) / "build")
        subprocess.run([str(root / "android/gradlew"), "--project-cache-dir", str(Path(directory) / "cache"),
                        ":app:assembleDebug", ":app:assembleDebugAndroidTest", ":app:lintDebug", ":app:testDebugUnitTest", "--no-daemon"],
                       cwd=root / "android", env=env, check=True)
        subprocess.run(adb + ["wait-for-device"], check=True, timeout=90)
        deadline = time.monotonic() + 90
        while subprocess.check_output(adb + ["shell", "getprop", "sys.boot_completed"], text=True).strip() != "1":
            if time.monotonic() > deadline:
                raise RuntimeError("Emulator boot timeout")
            time.sleep(1)
        for apk in ["debug/app-debug.apk", "androidTest/debug/app-debug-androidTest.apk"]:
            subprocess.run(adb + ["install", "-r", str(Path(directory) / "build/app/outputs/apk" / apk)], check=True)
        subprocess.run(adb + ["shell", "pm", "grant", "com.makewheels.dashcam.dev", "android.permission.POST_NOTIFICATIONS"], check=True)
        result = subprocess.run(adb + ["shell", "am", "instrument", "-w", "com.makewheels.dashcam.dev.test/androidx.test.runner.AndroidJUnitRunner"], capture_output=True, text=True, check=True)
        print(result.stdout)
        if "OK (" not in result.stdout or "FAILURES" in result.stdout:
            raise RuntimeError("Instrumented checks failed")
        output.mkdir(parents=True, exist_ok=True)
        for name in ["home-status", "home-disconnected", "queue-empty", "cloud-videos", "settings", "import-progress", "upload-progress", "batch-history", "batch-detail"]:
            with (output / (name + ".png")).open("wb") as file:
                subprocess.run(adb + ["exec-out", "run-as", "com.makewheels.dashcam.dev", "cat", "cache/" + name + ".png"], stdout=file, check=True)
    print("Screenshots saved: " + str(output))


if __name__ == "__main__":
    main()
