"""Publish only to private OSS; never upload a configured personal APK to public artifacts."""
import hashlib
import json
import os
import re
import sys
from pathlib import Path

import oss2


def main():
    apk = Path(sys.argv[1])
    version = os.environ["DASHCAM_VERSION_NAME"]
    if not re.fullmatch(r"\d+\.\d+\.\d+", version):
        raise SystemExit("Invalid version")
    code = int(os.environ["DASHCAM_VERSION_CODE"])
    auth = oss2.Auth(os.environ["RELEASE_ACCESS_KEY_ID"], os.environ["RELEASE_ACCESS_KEY_SECRET"])
    bucket = oss2.Bucket(auth, os.environ["OSS_ENDPOINT"], os.environ["OSS_BUCKET"])
    # The publication principal cannot change ACL; private ownership is established during provisioning.
    data = apk.read_bytes()
    digest = hashlib.sha256(data).hexdigest()
    manifest = {"available": True, "version_name": version, "version_code": code,
                "size": len(data), "sha256": digest, "notes": os.environ.get("DASHCAM_RELEASE_NOTES", "改进行车视频转存")}
    prefix = f"releases/android/{version}/"
    if bucket.object_exists(prefix + "manifest.json"):
        existing = json.loads(bucket.get_object(prefix + "manifest.json").read())
        if existing["sha256"] != digest:
            raise SystemExit("Version already published with a different APK; increment the version")
    bucket.put_object(prefix + "app.apk", data, headers={"Content-Type": "application/vnd.android.package-archive"})
    downloaded = bucket.get_object(prefix + "app.apk").read()
    if len(downloaded) != len(data) or hashlib.sha256(downloaded).hexdigest() != digest:
        raise SystemExit("Uploaded APK verification failed; latest was not changed")
    if bucket.object_exists("releases/android/latest.json"):
        old = json.loads(bucket.get_object("releases/android/latest.json").read())
        if int(old["version_code"]) > code:
            raise SystemExit("Refusing to replace a newer release")
    payload = json.dumps(manifest, ensure_ascii=False).encode()
    bucket.put_object(prefix + "manifest.json", payload, headers={"Content-Type": "application/json"})
    bucket.put_object("releases/android/latest.json", payload, headers={"Content-Type": "application/json"})
    print(f"Published Android {version} ({code}), SHA-256 {digest}")


if __name__ == "__main__":
    main()
