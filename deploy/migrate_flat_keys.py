"""将 videos 下旧路径对象迁移为 videos/{原文件名}（不同内容同名时退回 videos/{sha}_{name}），并清理对象已丢失的悬空文档。

用法: python migrate_flat_keys.py <env文件> [--apply]
默认 dry-run 只打印计划。步骤: HEAD核实 → 服务端copy → size/crc64/meta校验 → Mongo改key → 删旧对象。
"""
import base64
import sys
import tempfile
from pathlib import Path

import oss2
import pymongo

APPLY = "--apply" in sys.argv
env = {}
for line in Path(sys.argv[1]).read_text().splitlines():
    if "=" in line and not line.startswith("#"):
        k, _, v = line.partition("=")
        env[k.strip()] = v.strip().strip('"')

with tempfile.TemporaryDirectory() as td:
    ca = Path(td) / "ca.pem"
    ca.write_bytes(base64.b64decode(env["DASHCAM_MONGO_CA_BASE64"]))
    mongo = pymongo.MongoClient(env["DASHCAM_MONGO_URI"], tls=True, tlsCAFile=str(ca),
                                serverSelectionTimeoutMS=15000)
    bucket = oss2.Bucket(oss2.Auth(env["OSS_ACCESS_KEY_ID"], env["OSS_ACCESS_KEY_SECRET"]),
                         env["OSS_ENDPOINT"], env["OSS_BUCKET"], connect_timeout=15)
    videos = mongo[env["DASHCAM_DB_NAME"]].videos
    docs = list(videos.find({}).sort("created_at", 1))

    moved = missing = flat = errors = 0
    for d in docs:
        sha, name, old = d["_id"], d["name"], d["object_key"]
        new = f"videos/{name}"
        if videos.find_one({"object_key": new, "_id": {"$ne": sha}}):
            new = f"videos/{sha}_{name}"
        if old == new:
            flat += 1
            continue
        try:
            head = bucket.head_object(old)
        except oss2.exceptions.NoSuchKey:
            if APPLY:
                r = videos.delete_one({"_id": sha, "object_key": old, "state": "uploaded"})
                print(f"{'删除悬空文档' if r.deleted_count else '文档状态已变,跳过'} sha={sha[:12]} {old}")
            else:
                print(f"[dry] 删除悬空文档 sha={sha[:12]} {old}")
            missing += 1
            continue
        if head.content_length != d["size"]:
            print(f"!! 大小不符 sha={sha[:12]} {old}: {head.content_length} != {d['size']}")
            errors += 1
            continue
        if APPLY:
            bucket.copy_object(env["OSS_BUCKET"], old, new)
            h2 = bucket.head_object(new)
            ok = (h2.content_length == d["size"]
                  and str(h2.headers.get("x-oss-hash-crc64ecma")) == str(d["crc64"])
                  and h2.headers.get("x-oss-meta-sha256", sha) == sha)
            if not ok:
                print(f"!! 新对象校验失败 sha={sha[:12]} {new}")
                errors += 1
                continue
            r = videos.update_one({"_id": sha, "object_key": old}, {"$set": {"object_key": new}})
            if r.modified_count != 1:
                print(f"!! Mongo更新失败 sha={sha[:12]}（状态已变，保留旧对象）")
                errors += 1
                continue
            try:
                bucket.delete_object(old)
                print(f"迁移完成 sha={sha[:12]} {new}")
            except oss2.exceptions.AccessDenied:
                # 运行时 RAM 用户按设计无删除权限；旧对象由主账号后续精确清理
                print(f"迁移完成(旧对象待主账号删除) sha={sha[:12]} {old}")
        else:
            print(f"[dry] 迁移 sha={sha[:12]} {old} -> {new}")
        moved += 1

    print(f"\n总计: 文档={len(docs)} 已平铺={flat} 待迁移={moved} 悬空={missing} 错误={errors}")
    if APPLY:
        try:
            listed = {o.key for o in oss2.ObjectIterator(bucket, prefix="videos/")}
            known = {d["object_key"] for d in videos.find({}, {"object_key": 1})}
            orphans = listed - known
            print(f"OSS对象={len(listed)} Mongo引用={len(known)} 孤儿对象={len(orphans)}")
            for k in sorted(orphans):
                print(f"  孤儿: {k}")
        except oss2.exceptions.AccessDenied:
            # 运行时 RAM 用户无列举权限；孤儿检查用主账号 CLI 完成
            print("运行时凭据无列举权限，跳过孤儿检查（用主账号 CLI 核对）")
    mongo.close()
