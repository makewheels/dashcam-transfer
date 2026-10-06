"""Reproduce preparation on real host files through a debug-only read-only document bridge."""
import argparse
import io
import json
import os
from pathlib import Path
import shutil
import subprocess
import tarfile
import tempfile
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import unquote_plus


def main():
    args = argparse.ArgumentParser()
    args.add_argument("directory", type=Path)
    args.add_argument("--review-wait", action="store_true")
    options = args.parse_args()
    source = options.directory.resolve(strict=True)
    root = Path(__file__).resolve().parents[1]
    files = {p.name: p for p in source.iterdir() if p.is_file() and p.suffix.lower() in {".mov", ".mp4", ".avi", ".mkv"} and p.resolve().parent == source}
    if len(files) < 20:
        raise SystemExit("Use the actual multi-video card directory")
    before = {name: (p.stat().st_size, p.stat().st_mtime_ns) for name, p in files.items()}
    stats = {"bytes": 0}

    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *args): pass
        def do_GET(self):
            if self.path == "/entries":
                payload = json.dumps([{"name": name, "size": size, "modified": modified // 1000000}
                                      for name, (size, modified) in sorted(before.items())]).encode()
                self.send_response(200)
                self.send_header("Content-Length", str(len(payload)))
                self.end_headers()
                self.wfile.write(payload)
                return
            name = unquote_plus(self.path.removeprefix("/file/"))
            if not self.path.startswith("/file/") or name not in files:
                self.send_error(404)
                return
            self.send_response(200)
            self.send_header("Content-Length", str(before[name][0]))
            self.end_headers()
            try:
                with files[name].open("rb") as stream:
                    while chunk := stream.read(1024 * 1024):
                        self.wfile.write(chunk)
                        stats["bytes"] += len(chunk)
            except (BrokenPipeError, ConnectionResetError): pass

    sdk = Path(os.environ.get("ANDROID_HOME", str(Path.home() / "Library/Android/sdk")))
    env = dict(os.environ)
    env["ANDROID_HOME"] = str(sdk)
    env["JAVA_HOME"] = subprocess.check_output(["/usr/libexec/java_home", "-v", "21"], text=True).strip()
    for key in list(env):
        if key.startswith("DASHCAM_"): del env[key]
    env["UV_LINK_MODE"] = "hardlink"
    process = None
    server = None
    with tempfile.TemporaryDirectory(prefix="transfer-real-card-") as task:
        directory = Path(task).resolve()
        assert directory.parent == Path(tempfile.gettempdir()).resolve() and directory.name.startswith("transfer-real-card-")
        print("REVIEW_DIR=" + str(directory), flush=True)
        try:
            baseline = directory / "baseline"
            baseline.mkdir()
            archive = subprocess.check_output(["git", "archive", "v0.6.2", "android"], cwd=root)
            with tarfile.open(fileobj=io.BytesIO(archive)) as bundle:
                bundle.extractall(baseline, filter="data")
            current = directory / "current"
            shutil.copytree(root / "android", current / "android", ignore=shutil.ignore_patterns("build", ".gradle", ".kotlin"))
            for checkout in [baseline, current]:
                for relative in ["app/src/debug/AndroidManifest.xml", "app/src/debug/kotlin/com/makewheels/dashcam/HostCardDocumentsProvider.kt", "app/src/androidTest/kotlin/com/makewheels/dashcam/HostCardFlowTest.kt"]:
                    target = checkout / "android" / relative
                    target.parent.mkdir(parents=True, exist_ok=True)
                    shutil.copyfile(root / "android" / relative, target)
                log = directory / (checkout.name + "-build.log")
                with log.open("w") as output:
                    result = subprocess.run([str(checkout / "android/gradlew"), "-p", str(checkout / "android"),
                                             "--project-cache-dir", str(directory / (checkout.name + "-cache")),
                                             ":app:assembleDebug", ":app:assembleDebugAndroidTest",
                                             ":app:lintDebug", ":app:testDebugUnitTest", "--no-daemon"],
                                            env=env, stdout=output, stderr=subprocess.STDOUT)
                if result.returncode:
                    print(log.read_text()[-4000:])
                    raise RuntimeError("Build failed: " + checkout.name)
                print(checkout.name + " build/lint/unit passed", flush=True)
            server = ThreadingHTTPServer(("127.0.0.1", 8765), Handler)
            threading.Thread(target=server.serve_forever, daemon=True).start()
            avd_home = directory / "avd"
            avd = avd_home / "hostcard.avd"
            avd.mkdir(parents=True)
            existing = Path.home() / ".android/avd/kttest.avd/config.ini"
            configuration = {}
            for line in existing.read_text().splitlines():
                if "=" in line:
                    key, value = line.split("=", 1)
                    configuration[key.strip()] = value.strip()
            for key in ["disk.dataPartition.path", "sdcard.path"]: configuration.pop(key, None)
            configuration.update({"avd.id": "hostcard", "avd.name": "hostcard", "disk.dataPartition.size": "16G", "fastboot.forceColdBoot": "yes"})
            configuration["image.sysdir.1"] = str(sdk / configuration["image.sysdir.1"])
            (avd / "config.ini").write_text("\n".join(f"{k}={v}" for k, v in configuration.items()) + "\n")
            (avd_home / "hostcard.ini").write_text("avd.ini.encoding=UTF-8\npath=" + str(avd) + "\ntarget=android-35\n")
            env["ANDROID_AVD_HOME"] = str(avd_home)
            env["ANDROID_USER_HOME"] = str(directory / "user")
            env["ANDROID_EMULATOR_HOME"] = str(directory / "user")
            adb = [str(sdk / "platform-tools/adb"), "-s", "emulator-5584"]
            with (directory / "emulator.log").open("w") as output:
                process = subprocess.Popen([str(sdk / "emulator/emulator"), "-avd", "hostcard", "-port", "5584", "-no-window", "-no-audio", "-no-snapshot", "-wipe-data"], env=env, stdout=output, stderr=subprocess.STDOUT)
            deadline = time.monotonic() + 120
            while time.monotonic() < deadline:
                boot = subprocess.run(adb + ["shell", "getprop", "sys.boot_completed"], capture_output=True, text=True)
                storage = subprocess.run(adb + ["shell", "test", "-d", "/sdcard"], capture_output=True)
                if boot.stdout.strip() == "1" and storage.returncode == 0: break
                if process.poll() is not None:
                    print((directory / "emulator.log").read_text()[-3000:])
                    raise RuntimeError("Emulator exited")
                time.sleep(1)
            else: raise RuntimeError("Emulator boot timeout")
            subprocess.run(adb + ["reverse", "tcp:8765", "tcp:8765"], check=True, capture_output=True)
            print(subprocess.check_output(adb + ["shell", "df", "-h", "/sdcard"], text=True), flush=True)
            for checkout, test in [(baseline, "HostCardBaselineTest"), (current, "HostCardFlowTest")]:
                subprocess.run(adb + ["shell", "am", "force-stop", "com.makewheels.dashcam.dev"], check=True, capture_output=True)
                for apk in ["debug/app-debug.apk", "androidTest/debug/app-debug-androidTest.apk"]:
                    subprocess.run(adb + ["install", "-r", str(checkout / "android/app/build/outputs/apk" / apk)], check=True, capture_output=True)
                subprocess.run(adb + ["shell", "pm", "grant", "com.makewheels.dashcam.dev", "android.permission.POST_NOTIFICATIONS"], check=True, capture_output=True)
                subprocess.run(adb + ["logcat", "-c"], check=True)
                result = subprocess.run(adb + ["shell", "am", "instrument", "-w", "-e", "hostCardCheck", "true", "-e", "class", "com.makewheels.dashcam." + test,
                                               "com.makewheels.dashcam.dev.test/androidx.test.runner.AndroidJUnitRunner"], capture_output=True, text=True, timeout=300)
                print(result.stdout, flush=True)
                logs = subprocess.check_output(adb + ["logcat", "-d"], text=True)
                print("\n".join(line for line in logs.splitlines() if "BASELINE:" in line or "REAL_CARD:" in line), flush=True)
                if "OK (" not in result.stdout or "FAILURES" in result.stdout:
                    logs = subprocess.check_output(adb + ["logcat", "-d", "-t", "300"], text=True)
                    print("\n".join(line for line in logs.splitlines() if any(word in line for word in ["HostCard", "NetworkOnMain", "Cleartext", "Exception", "documents"])))
                    print("Read-only bridge bytes served:", stats["bytes"], flush=True)
                    raise RuntimeError("Host-card test failed: " + test)
                print("Read-only bridge bytes served:", stats["bytes"], flush=True)
                screenshot = "host-card-baseline" if checkout == baseline else "host-card-progress"
                with (directory / (screenshot + ".png")).open("wb") as output:
                    subprocess.run(adb + ["exec-out", "run-as", "com.makewheels.dashcam.dev", "cat", "cache/" + screenshot + ".png"], stdout=output, check=True)
            after = {name: (p.stat().st_size, p.stat().st_mtime_ns) for name, p in files.items()}
            assert before == after and set(files) == {p.name for p in source.iterdir() if p.is_file() and p.suffix.lower() in {".mov", ".mp4", ".avi", ".mkv"}}
            print("Source file count, sizes and modification times unchanged; bridge supports read-only GET; no cloud credential configured", flush=True)
            if options.review_wait:
                print("REVIEW_READY", flush=True)
                input()
        finally:
            if process:
                process.terminate()
                try: process.wait(timeout=15)
                except subprocess.TimeoutExpired: process.kill(); process.wait()
            if server:
                server.shutdown()
                server.server_close()


if __name__ == "__main__":
    main()
