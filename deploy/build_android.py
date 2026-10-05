"""Build in place (Kotlin 2.0.20+ ignores custom build directories) and clean up after."""
import base64
import os
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

BUILD_OUTPUTS = ["android/app/build", "android/build", "android/.kotlin", "android/app/.kotlin"]


def main():
    root = Path(__file__).resolve().parents[1]
    mode = sys.argv[1]
    if mode not in ("debug", "release"):
        raise SystemExit("Choose debug or release")
    output = Path(sys.argv[2]).resolve() if len(sys.argv) > 2 else None
    try:
        env = dict(os.environ)
        if mode == "release":
            key = Path(tempfile.gettempdir()) / "dashcam-release.jks"
            key.write_bytes(base64.b64decode(env["DASHCAM_KEYSTORE_BASE64"]))
            key.chmod(0o600)
            env["DASHCAM_KEYSTORE"] = str(key)
            if not env.get("DASHCAM_API_URL") or not env.get("DASHCAM_APP_TOKEN"):
                raise SystemExit("Release endpoint and service credential required")
        cap = mode.title()
        subprocess.run([str(root / "android/gradlew"), f":app:assemble{cap}",
                        f":app:lint{cap}", f":app:test{cap}UnitTest", "--no-daemon"],
                       cwd=root / "android", env=env, check=True)
        apk = root / "android/app/build/outputs/apk" / mode / ("app-" + mode + ".apk")
        if mode == "release":
            sdk = env.get("ANDROID_HOME") or env.get("ANDROID_SDK_ROOT")
            if not sdk:
                raise SystemExit("Android SDK location required")
            subprocess.run([str(Path(sdk) / "build-tools/35.0.0/apksigner"), "verify", "--verbose",
                            str(apk)], check=True)
        if output:
            output.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(apk, output)
            print("APK saved: " + str(output))
    finally:
        for relative in BUILD_OUTPUTS:
            shutil.rmtree(root / relative, ignore_errors=True)
        key = Path(tempfile.gettempdir()) / "dashcam-release.jks"
        key.unlink(missing_ok=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
