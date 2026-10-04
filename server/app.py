"""Small control plane; video bytes travel directly between the phone and private OSS."""
import hashlib
import hmac
import json
import logging
import os
import re
import time
import uuid
from datetime import datetime, timezone
from zoneinfo import ZoneInfo
from functools import wraps

import oss2
from flask import Flask, jsonify, request
from pymongo import MongoClient, ReturnDocument
from pymongo.errors import DuplicateKeyError

PART_SIZE = 8 * 1024 * 1024
LEASE_SECONDS = 1800
SIGN_SECONDS = 900


def create_app(database=None, bucket=None, token=None):
    app = Flask(__name__)
    app.config["MAX_CONTENT_LENGTH"] = 128 * 1024
    if database is None:
        kwargs = {"serverSelectionTimeoutMS": 10000, "connectTimeoutMS": 10000}
        ca = os.environ.get("DASHCAM_MONGO_CA_PEM")
        if ca:
            # Deployment injects the CA as a controlled runtime file, never public source.
            import tempfile
            import atexit
            fd, path = tempfile.mkstemp(prefix="dashcam-ca-", suffix=".pem")
            os.fchmod(fd, 0o600)
            with os.fdopen(fd, "w") as output:
                output.write(ca)
            atexit.register(lambda: os.path.exists(path) and os.unlink(path))
            kwargs["tlsCAFile"] = path
        database = MongoClient(os.environ["DASHCAM_MONGO_URI"], **kwargs)[os.environ.get("DASHCAM_DB_NAME", "dashcam_transfer")]
    if bucket is None:
        auth = oss2.Auth(os.environ["OSS_ACCESS_KEY_ID"], os.environ["OSS_ACCESS_KEY_SECRET"])
        bucket = oss2.Bucket(auth, os.environ["OSS_ENDPOINT"], os.environ["OSS_BUCKET"], connect_timeout=15)
    secret = token or os.environ["DASHCAM_APP_TOKEN"]
    imports = database.import_batches
    imports.create_index("import_time")
    videos = database.videos
    videos.create_index([("state", 1), ("created_at", -1)])

    def protected(fn):
        @wraps(fn)
        def wrapper(*args, **kwargs):
            supplied = request.headers.get("Authorization", "")
            if not hmac.compare_digest(supplied, "Bearer " + secret):
                return jsonify(error="unauthorized"), 401
            return fn(*args, **kwargs)
        return wrapper

    def public(doc):
        return {k: doc.get(k) for k in ("_id", "name", "size", "date", "sha256", "crc64", "state", "created_at", "import_time", "import_id")}

    def head_valid(doc):
        try:
            head = bucket.head_object(doc["object_key"])
            crc = head.headers.get("x-oss-hash-crc64ecma")
            return head.content_length == doc["size"] and str(crc) == str(doc["crc64"])
        except oss2.exceptions.NoSuchKey:
            return False

    def leased(file_id, owner):
        doc = videos.find_one({"_id": file_id, "owner": owner, "state": "uploading"})
        if not doc:
            return None
        videos.update_one({"_id": file_id, "owner": owner}, {"$set": {"lease_until": time.time() + LEASE_SECONDS}})
        return doc

    @app.get("/healthz")
    def health():
        return jsonify(ok=True)

    @app.get("/videos")
    @protected
    def listing():
        try:
            limit = min(max(int(request.args.get("limit", 100)), 1), 200)
        except ValueError:
            return jsonify(error="invalid limit"), 400
        query = {"state": {"$in": ["uploaded", "missing"]}}
        batch = request.args.get("batch")
        if batch:
            if re.fullmatch(r"[a-f0-9-]{36}", batch):
                association = imports.find_one({"_id": batch})
                query["$or"] = [{"import_id": batch}, {"_id": {"$in": association.get("file_ids", []) if association else []}}]
            elif re.fullmatch(r"legacy:\d{4}-\d{2}-\d{2}", batch):
                query.update({"date": batch[7:], "import_id": None})
            else:
                return jsonify(error="invalid batch"), 400
        cursor = request.args.get("before")
        if cursor:
            query["created_at"] = {"$lt": cursor}
        docs = list(videos.find(query).sort("created_at", -1).limit(limit))
        return jsonify(videos=[public(d) for d in docs], next=docs[-1]["created_at"] if len(docs) == limit else None)

    @app.get("/batches")
    @protected
    def batches():
        try:
            limit = min(max(int(request.args.get("limit", 100)), 1), 200)
        except ValueError:
            return jsonify(error="invalid limit"), 400
        pipeline = [
            {"$match": {"state": {"$in": ["uploaded", "missing"]}}},
            {"$group": {"_id": {"$ifNull": ["$import_id", {"$concat": ["legacy:", "$date"]}]},
                        "import_time": {"$max": "$import_time"}, "date": {"$max": "$date"},
                        "count": {"$sum": 1}, "size": {"$sum": "$size"},
                        "missing": {"$sum": {"$cond": [{"$eq": ["$state", "missing"]}, 1, 0]}}}},
            {"$addFields": {"sort": {"$concat": [{"$ifNull": ["$import_time", "$date"]}, "|", "$_id"]}}},
        ]
        # Keep associations separately so importing the same content again still has a batch.
        related = list(imports.aggregate([
            {"$lookup": {"from": "videos", "localField": "file_ids", "foreignField": "_id", "as": "linked"}},
            {"$lookup": {"from": "videos", "localField": "_id", "foreignField": "import_id", "as": "primary"}},
            {"$project": {"import_time": 1, "date": 1, "files": {"$setUnion": ["$linked", "$primary"]}}},
            {"$unwind": "$files"}, {"$match": {"files.state": {"$in": ["uploaded", "missing"]}}},
            {"$group": {"_id": "$_id", "import_time": {"$first": "$import_time"}, "date": {"$first": "$date"},
                        "count": {"$sum": 1}, "size": {"$sum": "$files.size"},
                        "missing": {"$sum": {"$cond": [{"$eq": ["$files.state", "missing"]}, 1, 0]}}}}
        ]))
        merged = {d["_id"]: d for d in videos.aggregate(pipeline)}
        merged.update({d["_id"]: d for d in related})
        docs = list(merged.values())
        for doc in docs:
            doc["sort"] = (doc.get("import_time") or doc.get("date") or "") + "|" + doc["_id"]
        cursor = request.args.get("before")
        docs = sorted((d for d in docs if not cursor or d["sort"] < cursor), key=lambda d: d["sort"], reverse=True)[:limit]
        following = docs[-1]["sort"] if len(docs) == limit else None
        for doc in docs:
            doc["id"] = doc.pop("_id")
            doc.pop("sort")
        return jsonify(batches=docs, next=following)

    @app.post("/uploads/start")
    @protected
    def start():
        data = request.get_json(force=True)
        sha = str(data.get("sha256", ""))
        crc = str(data.get("crc64", ""))
        name = str(data.get("name", ""))
        date = str(data.get("date", ""))
        size = data.get("size")
        if (not re.fullmatch(r"[a-f0-9]{64}", sha) or not crc.isdigit() or int(crc) >= 2**64
                or not isinstance(size, int) or isinstance(size, bool) or size <= 0 or size > 1024**4
                or not name or len(name) > 240 or "/" in name or "\\" in name
                or not re.fullmatch(r"\d{4}-\d{2}-\d{2}", date)):
            return jsonify(error="invalid file metadata"), 400
        import_time = str(data.get("import_time", ""))
        import_id = str(data.get("import_id", ""))
        if import_time:
            try:
                parsed = datetime.strptime(import_time, "%Y-%m-%d_%H-%M-%S")
                if parsed.strftime("%Y-%m-%d_%H-%M-%S") != import_time or import_time[:10] != date:
                    raise ValueError("timestamp mismatch")
            except ValueError:
                return jsonify(error="invalid import timestamp"), 400
        else:
            # Legacy clients lack an import time; choose once at session creation.
            import_time = datetime.now(ZoneInfo("Asia/Shanghai")).strftime("%Y-%m-%d_%H-%M-%S")
        if import_id and not re.fullmatch(r"[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}", import_id):
            return jsonify(error="invalid import identity"), 400
        import_id = import_id or str(uuid.uuid4())
        def remember_import():
            imports.update_one({"_id": import_id}, {"$setOnInsert": {"import_time": import_time, "date": date},
                                                      "$addToSet": {"file_ids": sha}}, upsert=True)
        existing = videos.find_one({"_id": sha})
        if existing and (existing["size"] != size or existing["crc64"] != crc):
            return jsonify(error="file identity mismatch"), 409
        if existing and existing["state"] == "uploaded" and head_valid(existing):
            remember_import()
            return jsonify(uploaded=True, video=public(existing))
        owner = str(data.get("owner", ""))
        if not re.fullmatch(r"[a-zA-Z0-9-]{16,80}", owner):
            return jsonify(error="invalid owner"), 400
        doc = {"_id": sha, "sha256": sha, "crc64": crc, "size": size, "name": name,
               "date": date, "import_time": import_time, "import_id": import_id,
               "object_key": f"videos/{import_time}_{import_id[:8]}/{name}",
               "created_at": datetime.now(timezone.utc).isoformat() + "-" + uuid.uuid4().hex,
               "state": "waiting", "lease_until": 0}
        try:
            videos.update_one({"_id": sha}, {"$setOnInsert": doc}, upsert=True)
        except DuplicateKeyError:
            pass
        doc = videos.find_one_and_update(
            {"_id": sha, "$or": [{"owner": owner}, {"lease_until": {"$lt": time.time()}}]},
            {"$set": {"owner": owner, "lease_until": time.time() + LEASE_SECONDS, "state": "uploading"}},
            return_document=ReturnDocument.AFTER)
        if not doc:
            return jsonify(error="another phone is uploading; retry later"), 409
        remember_import()
        if not doc.get("upload_id"):
            upload_id = bucket.init_multipart_upload(doc["object_key"], headers={"x-oss-meta-sha256": sha}).upload_id
            videos.update_one({"_id": sha, "owner": owner}, {"$set": {"upload_id": upload_id}})
            doc["upload_id"] = upload_id
        try:
            parts = [{"number": p.part_number, "etag": p.etag, "size": p.size}
                     for p in oss2.PartIterator(bucket, doc["object_key"], doc["upload_id"])]
        except oss2.exceptions.NoSuchUpload:
            if head_valid(doc):
                videos.update_one({"_id": sha}, {"$set": {"state": "uploaded", "lease_until": 0}})
                return jsonify(uploaded=True)
            upload_id = bucket.init_multipart_upload(doc["object_key"], headers={"x-oss-meta-sha256": sha}).upload_id
            videos.update_one({"_id": sha, "owner": owner}, {"$set": {"upload_id": upload_id}})
            doc["upload_id"] = upload_id
            parts = []
        return jsonify(uploaded=False, id=sha, part_size=PART_SIZE, parts=parts)

    @app.post("/uploads/<file_id>/part")
    @protected
    def sign_part(file_id):
        data = request.get_json(force=True)
        doc = leased(file_id, data.get("owner"))
        if not doc:
            return jsonify(error="upload session unavailable"), 409
        number = data.get("number")
        if not isinstance(number, int) or isinstance(number, bool) or not 1 <= number <= (doc["size"] + PART_SIZE - 1) // PART_SIZE:
            return jsonify(error="invalid part number"), 400
        url = bucket.sign_url("PUT", doc["object_key"], SIGN_SECONDS, headers={"Content-Type": "application/octet-stream"},
                              params={"uploadId": doc["upload_id"], "partNumber": str(number)}, slash_safe=True)
        return jsonify(url=url, expires_in=SIGN_SECONDS)

    @app.post("/uploads/<file_id>/complete")
    @protected
    def complete(file_id):
        data = request.get_json(force=True)
        doc = leased(file_id, data.get("owner"))
        if not doc:
            existing = videos.find_one({"_id": file_id, "state": "uploaded"})
            if existing and head_valid(existing):
                return jsonify(verified=True)
            return jsonify(error="upload session unavailable"), 409
        try:
            parts = list(oss2.PartIterator(bucket, doc["object_key"], doc["upload_id"]))
            parts.sort(key=lambda p: p.part_number)
            count = (doc["size"] + PART_SIZE - 1) // PART_SIZE
            if len(parts) != count or sum(p.size for p in parts) != doc["size"] or [p.part_number for p in parts] != list(range(1, count + 1)):
                return jsonify(error="parts incomplete"), 409
            for part in parts:
                expected = min(PART_SIZE, doc["size"] - (part.part_number - 1) * PART_SIZE)
                if part.size != expected:
                    return jsonify(error="part size mismatch"), 409
            bucket.complete_multipart_upload(doc["object_key"], doc["upload_id"], [oss2.models.PartInfo(p.part_number, p.etag) for p in parts])
        except oss2.exceptions.NoSuchUpload:
            pass  # Completion may have succeeded before the response was lost.
        if not head_valid(doc):
            videos.update_one({"_id": file_id, "owner": doc["owner"]}, {"$unset": {"upload_id": ""}})
            return jsonify(error="cloud integrity verification failed; local file must be kept"), 409
        videos.update_one({"_id": file_id, "owner": doc["owner"]}, {"$set": {"state": "uploaded", "lease_until": 0, "verified_at": time.time()}})
        return jsonify(verified=True)

    @app.get("/videos/<file_id>/url")
    @protected
    def video_url(file_id):
        doc = videos.find_one({"_id": file_id, "state": {"$in": ["uploaded", "missing"]}})
        if not doc:
            return jsonify(error="not found"), 404
        if not head_valid(doc):
            videos.update_one({"_id": file_id}, {"$set": {"state": "missing"}})
            return jsonify(error="cloud file no longer exists"), 404
        if doc["state"] == "missing":
            videos.update_one({"_id": file_id}, {"$set": {"state": "uploaded"}})
        return jsonify(url=bucket.sign_url("GET", doc["object_key"], SIGN_SECONDS, slash_safe=True))

    @app.get("/updates/android")
    @protected
    def update():
        try:
            manifest = json.loads(bucket.get_object("releases/android/latest.json").read())
            version = str(manifest["version_name"])
            if not re.fullmatch(r"\d+\.\d+\.\d+", version):
                raise ValueError("invalid release")
            manifest["url"] = bucket.sign_url("GET", f"releases/android/{version}/app.apk", SIGN_SECONDS, slash_safe=True)
            return jsonify(manifest)
        except oss2.exceptions.NoSuchKey:
            return jsonify(available=False)

    @app.errorhandler(Exception)
    def errors(error):
        from werkzeug.exceptions import HTTPException
        if isinstance(error, HTTPException):
            return jsonify(error=error.name), error.code
        logging.error("API request failed: %s", type(error).__name__)
        return jsonify(error="service temporarily unavailable; retry later"), 503

    return app


if __name__ == "__main__":
    create_app().run(host="127.0.0.1", port=9000)
