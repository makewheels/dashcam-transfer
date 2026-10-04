"""Real private OSS transfer checks; only this invocation's objects and records are cleaned up."""
import base64
import hashlib
import os
import tempfile
import uuid
from datetime import datetime
from zoneinfo import ZoneInfo
from pathlib import Path

import oss2
import requests
from pymongo import MongoClient


def main():
    base = os.environ["DASHCAM_API_URL"]
    headers = {"Authorization": "Bearer " + os.environ["DASHCAM_APP_TOKEN"]}
    created = []
    auth = oss2.Auth(os.environ["ALIBABA_CLOUD_ACCESS_KEY_ID"], os.environ["ALIBABA_CLOUD_ACCESS_KEY_SECRET"])
    bucket = oss2.Bucket(auth, os.environ["OSS_ENDPOINT"], os.environ["OSS_BUCKET"])

    def api(path, data=None, expected=200):
        response = requests.post(base + path, headers=headers, json=data, timeout=60) if data is not None else requests.get(base + path, headers=headers, timeout=60)
        if response.status_code != expected:
            raise RuntimeError("Unexpected API status " + str(response.status_code) + " for " + path)
        return response.json()

    with tempfile.TemporaryDirectory(prefix="dashcam-smoke-") as directory:
        ca = Path(directory) / "ca.pem"
        ca.write_bytes(base64.b64decode(os.environ["DASHCAM_MONGO_CA_BASE64"]))
        ca.chmod(0o600)
        client = MongoClient(os.environ["DASHCAM_MONGO_URI"], tlsCAFile=str(ca), serverSelectionTimeoutMS=15000)
        database = client[os.environ["DASHCAM_DB_NAME"]]
        try:
            for wrong in [False, True]:
                data = os.urandom(9 * 1024 * 1024 if not wrong else 512 * 1024)
                sha = hashlib.sha256(data).hexdigest()
                crc = oss2.utils.Crc64()
                crc(data)
                owner = str(uuid.uuid4())
                name = "transfer-smoke-" + uuid.uuid4().hex + ".mp4"
                stamp = datetime.now(ZoneInfo("Asia/Shanghai")).strftime("%Y-%m-%d_%H-%M-%S")
                date = stamp[:10]
                import_id = str(uuid.uuid4())
                object_key = f"videos/{stamp}_{import_id[:8]}/{name}"
                created.append((sha, object_key))
                metadata = {"owner": owner, "name": name, "size": len(data), "date": date,
                            "sha256": sha, "crc64": str(crc.crc ^ 1 if wrong else crc.crc),
                            "import_time": stamp, "import_id": import_id}
                start = api("/uploads/start", metadata)
                size = start["part_size"]
                assert not start["uploaded"]
                for index, offset in enumerate(range(0, len(data), size), 1):
                    sign = api(f"/uploads/{sha}/part", {"owner": owner, "number": index})
                    response = requests.put(sign["url"], data=data[offset:offset + size], headers={"Content-Type": "application/octet-stream"}, timeout=90)
                    if response.status_code != 200:
                        raise RuntimeError("Signed part upload failed " + str(response.status_code))
                    if index == 1 and not wrong:
                        resumed = api("/uploads/start", metadata)
                        assert len(resumed["parts"]) == 1
                        second = dict(metadata, owner=str(uuid.uuid4()))
                        api("/uploads/start", second, expected=409)
                response = api(f"/uploads/{sha}/complete", {"owner": owner}, expected=409 if wrong else 200)
                if wrong:
                    assert not response.get("verified", False)
                    assert database.videos.find_one({"_id": sha})["state"] != "uploaded"
                    print("Incorrect whole-file CRC rejected; record not uploaded")
                else:
                    assert response["verified"]
                    assert api(f"/uploads/{sha}/complete", {"owner": owner})["verified"]
                    assert api("/uploads/start", dict(metadata, owner=str(uuid.uuid4())))["uploaded"]
                    url = api(f"/videos/{sha}/url")["url"]
                    downloaded = requests.get(url, timeout=60)
                    assert downloaded.status_code == 200 and hashlib.sha256(downloaded.content).hexdigest() == sha
                    assert requests.get(url.split("?", 1)[0], timeout=20).status_code == 403
                    assert any(video["_id"] == sha for video in api("/videos")["videos"])
                    print("Real multipart resume, phone conflict, CRC verification, shared listing and signed download verified; anonymous denied")
        finally:
            for sha, key in created:
                doc = database.videos.find_one({"_id": sha})
                if doc and doc.get("upload_id"):
                    try:
                        bucket.abort_multipart_upload(key, doc["upload_id"])
                    except oss2.exceptions.NoSuchUpload:
                        pass
                bucket.delete_object(key)
                database.videos.delete_one({"_id": sha})
            client.close()
    print("Only invocation-owned smoke objects and records cleaned up")


if __name__ == "__main__":
    main()
