import hashlib
import os
from types import SimpleNamespace

import mongomock
import oss2
import pytest
from pymongo import MongoClient

from app import PART_SIZE, create_app


class Bucket:
    def __init__(self):
        self.parts = []
        self.head = None
        self.merges = 0
    def init_multipart_upload(self, key, headers=None):
        return SimpleNamespace(upload_id="session-1")
    def list_parts(self, key, upload_id, **kwargs):
        return SimpleNamespace(parts=self.parts, is_truncated=False, next_marker="")
    def head_object(self, key):
        if self.head is None:
            raise oss2.exceptions.NoSuchKey(404, {}, b"", {"Code": "NoSuchKey", "Message": "missing"})
        return self.head
    def complete_multipart_upload(self, *args):
        self.merges += 1
    def sign_url(self, method, key, expires, **kwargs):
        return f"https://example.invalid/{key}?temporary=1"


@pytest.fixture
def setup():
    uri = os.environ.get("DASHCAM_TEST_MONGO_URI")
    client = MongoClient(uri) if uri else mongomock.MongoClient()
    db = client["dashcam_transfer_test"]
    db.videos.delete_many({})
    bucket = Bucket()
    app = create_app(db, bucket, "test-access-only")
    app.testing = True
    yield app.test_client(), db, bucket
    client.drop_database("dashcam_transfer_test")
    client.close()


def metadata(size=123):
    return {"sha256": hashlib.sha256(b"sample").hexdigest(), "crc64": "123456",
            "size": size, "name": "video.mp4", "date": "2026-10-04", "owner": "phone-a-123456789"}


AUTH = {"Authorization": "Bearer test-access-only"}


def test_auth_and_input_boundaries(setup):
    c, db, b = setup
    assert c.get("/videos").status_code == 401
    assert c.post("/uploads/start", json={"size": -1}, headers=AUTH).status_code == 400
    assert c.get("/videos?limit=bad", headers=AUTH).status_code == 400
    assert db.videos.count_documents({}) == 0


def test_missing_or_incorrect_parts_cannot_mark_uploaded(setup):
    c, db, b = setup
    data = metadata(PART_SIZE + 1)
    assert c.post("/uploads/start", json=data, headers=AUTH).status_code == 200
    url = f"/uploads/{data['sha256']}/complete"
    b.parts = [SimpleNamespace(part_number=1, size=PART_SIZE, etag="part")]
    assert c.post(url, json={"owner": data["owner"]}, headers=AUTH).status_code == 409
    b.parts = [SimpleNamespace(part_number=1, size=PART_SIZE - 1, etag="one"), SimpleNamespace(part_number=2, size=2, etag="two")]
    assert c.post(url, json={"owner": data["owner"]}, headers=AUTH).status_code == 409
    assert b.merges == 0
    assert db.videos.find_one()["state"] != "uploaded"


def test_crc_mismatch_keeps_upload_unverified(setup):
    c, db, b = setup
    data = metadata()
    c.post("/uploads/start", json=data, headers=AUTH)
    b.parts = [SimpleNamespace(part_number=1, size=data["size"], etag="part")]
    b.head = SimpleNamespace(content_length=data["size"], headers={"x-oss-hash-crc64ecma": "999"})
    response = c.post(f"/uploads/{data['sha256']}/complete", json={"owner": data["owner"]}, headers=AUTH)
    assert response.status_code == 409
    assert not response.json.get("verified", False)
    assert db.videos.find_one()["state"] != "uploaded"


def test_verified_upload_is_shared_and_completion_idempotent(setup):
    c, db, b = setup
    data = metadata()
    c.post("/uploads/start", json=data, headers=AUTH)
    b.parts = [SimpleNamespace(part_number=1, size=data["size"], etag="part")]
    b.head = SimpleNamespace(content_length=data["size"], headers={"x-oss-hash-crc64ecma": data["crc64"]})
    url = f"/uploads/{data['sha256']}/complete"
    assert c.post(url, json={"owner": data["owner"]}, headers=AUTH).json["verified"] is True
    assert c.post(url, json={"owner": "phone-b-123456789"}, headers=AUTH).json["verified"] is True
    assert b.merges == 1
    assert len(c.get("/videos", headers=AUTH).json["videos"]) == 1
    data["owner"] = "phone-b-123456789"
    assert c.post("/uploads/start", json=data, headers=AUTH).json["uploaded"] is True
    assert "temporary=1" in c.get(f"/videos/{data['sha256']}/url", headers=AUTH).json["url"]
    b.head = None
    assert c.get(f"/videos/{data['sha256']}/url", headers=AUTH).status_code == 404
    assert db.videos.find_one()["state"] == "missing"
    assert len(c.get("/videos", headers=AUTH).json["videos"]) == 1


def test_second_phone_cannot_take_live_lease(setup):
    c, db, b = setup
    data = metadata()
    c.post("/uploads/start", json=data, headers=AUTH)
    data["owner"] = "phone-b-123456789"
    assert c.post("/uploads/start", json=data, headers=AUTH).status_code == 409
    assert c.post(f"/uploads/{data['sha256']}/part", json={"owner": data["owner"], "number": 1}, headers=AUTH).status_code == 409
    db.videos.update_one({}, {"$set": {"lease_until": 0}})
    assert c.post("/uploads/start", json=data, headers=AUTH).status_code == 200


def test_unexpected_part_number_rejected(setup):
    c, db, b = setup
    data = metadata()
    c.post("/uploads/start", json=data, headers=AUTH)
    for number in [0, 2, True, "1"]:
        assert c.post(f"/uploads/{data['sha256']}/part", json={"owner": data["owner"], "number": number}, headers=AUTH).status_code == 400


def test_content_keys_share_one_folder_and_resume_stable(setup):
    import uuid
    c, db, b = setup
    keys = []
    for clock, content in [("2026-10-04_08-30-01", b"morning"), ("2026-10-04_20-42-09", b"evening")]:
        data = metadata()
        data.update(sha256=hashlib.sha256(content).hexdigest(), import_time=clock, import_id=str(uuid.uuid4()))
        assert c.post("/uploads/start", json=data, headers=AUTH).status_code == 200
        key = db.videos.find_one({"_id": data["sha256"]})["object_key"]
        assert key.startswith(f"videos/{data['sha256']}_")
        assert key.endswith("_video.mp4")
        assert key.count("/") == 1
        keys.append(key)
        data["import_time"] = "2026-10-04_23-59-59"
        data["import_id"] = str(uuid.uuid4())
        assert c.post("/uploads/start", json=data, headers=AUTH).status_code == 200
        assert db.videos.find_one({"_id": data["sha256"]})["object_key"] == key
    assert keys[0] != keys[1]


def test_same_second_imports_are_isolated_and_bad_timestamps_rejected(setup):
    import uuid
    c, db, b = setup
    for payload in [b"one", b"two"]:
        data = metadata()
        data.update(sha256=hashlib.sha256(payload).hexdigest(), import_time="2026-10-04_10-10-10", import_id=str(uuid.uuid4()))
        assert c.post("/uploads/start", json=data, headers=AUTH).status_code == 200
    keys = [d["object_key"] for d in db.videos.find()]
    assert len(set(keys)) == 2
    data = metadata()
    for timestamp in ["2026-10-04_25-10-10", "2026-02-30_10-10-10", "../../bad", "2026-10-03_10-10-10"]:
        data["import_time"] = timestamp
        assert c.post("/uploads/start", json=data, headers=AUTH).status_code == 400


def test_batch_history_pagination_detail_and_legacy_records(setup):
    c, db, b = setup
    assert c.get('/batches').status_code == 401
    ids = ['11111111-1111-1111-1111-111111111111', '22222222-2222-2222-2222-222222222222']
    for index, batch in enumerate(ids):
        data = metadata()
        data.update(_id=str(index), import_id=batch, import_time=f'2026-10-04_{8+index:02}-30-00', state='uploaded', created_at=str(index))
        db.videos.insert_one(data)
    first = c.get('/batches?limit=1', headers=AUTH).json
    assert first['batches'][0]['id'] == ids[1]
    assert first['batches'][0]['count'] == 1
    second = c.get('/batches?limit=1&before='+first['next'], headers=AUTH).json
    assert second['batches'][0]['id'] == ids[0]
    assert len(c.get('/videos?batch='+ids[0], headers=AUTH).json['videos']) == 1
    db.videos.insert_one(dict(metadata(), _id='legacy', state='missing', created_at='0'))
    db.videos.insert_one(dict(metadata(), _id='pending', state='uploading', created_at='1'))
    legacy = c.get('/videos?batch=legacy:2026-10-04', headers=AUTH).json['videos']
    assert [d['_id'] for d in legacy] == ['legacy']
    batches = c.get('/batches', headers=AUTH).json['batches']
    assert sum(d['count'] for d in batches) == 3
    assert next(d for d in batches if d['id'].startswith('legacy:'))['missing'] == 1
    assert c.get('/videos?batch=invalid', headers=AUTH).status_code == 400


def test_reimported_content_is_visible_in_both_batches_without_uploading_again(setup):
    import uuid
    c, db, b = setup
    data = metadata()
    data.update(import_id=str(uuid.uuid4()), import_time='2026-10-04_08-00-00')
    c.post('/uploads/start', json=data, headers=AUTH)
    b.parts = [SimpleNamespace(part_number=1, size=data['size'], etag='part')]
    b.head = SimpleNamespace(content_length=data['size'], headers={'x-oss-hash-crc64ecma': data['crc64']})
    assert c.post(f"/uploads/{data['sha256']}/complete", json={'owner':data['owner']}, headers=AUTH).json['verified']
    first = data['import_id']
    data.update(import_id=str(uuid.uuid4()), import_time='2026-10-04_20-00-00')
    assert c.post('/uploads/start', json=data, headers=AUTH).json['uploaded']
    # Existing 0.2 files may predate the association collection. Keep them when
    # another file creates a partial association for the same batch.
    old = dict(db.videos.find_one({'_id': data['sha256']}))
    old.update(_id='older-primary-file', sha256='older-primary-file', created_at='0')
    db.videos.insert_one(old)
    summaries = c.get('/batches', headers=AUTH).json['batches']
    assert {d['id'] for d in summaries} == {first, data['import_id']}
    for batch in summaries:
        assert batch['count'] == (2 if batch['id'] == first else 1)
        assert c.get('/videos?batch='+batch['id'], headers=AUTH).json['videos'][0]['sha256'] == data['sha256']
    assert b.merges == 1
