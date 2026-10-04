"""Run tests against an isolated real MongoDB, preserving any existing local instance."""
import os
import socket
import subprocess
import tempfile
import time
from pathlib import Path

from pymongo import MongoClient


def main():
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        port = sock.getsockname()[1]
    with tempfile.TemporaryDirectory(prefix="dashcam-mongo-test-") as directory:
        process = subprocess.Popen(["mongod", "--dbpath", directory, "--port", str(port), "--bind_ip", "127.0.0.1",
                                    "--logpath", directory + "/mongo.log"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        client = MongoClient(f"mongodb://127.0.0.1:{port}", serverSelectionTimeoutMS=500)
        try:
            for _ in range(30):
                try:
                    client.admin.command("ping")
                    break
                except Exception:
                    time.sleep(0.2)
            else:
                raise RuntimeError("Isolated MongoDB did not start")
            env = dict(os.environ, DASHCAM_TEST_MONGO_URI=f"mongodb://127.0.0.1:{port}")
            result = subprocess.run(["uv", "run", "--locked", "pytest", "-q"], env=env, cwd=Path(__file__).resolve().parents[1])
            return result.returncode
        finally:
            client.close()
            process.terminate()
            try:
                process.wait(timeout=15)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait()


if __name__ == "__main__":
    raise SystemExit(main())
