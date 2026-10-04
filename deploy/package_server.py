"""Linux-compatible FC ZIP built in a task-owned system temporary directory."""
import os
import subprocess
import sys
import tempfile
import zipfile
import shutil
from pathlib import Path


def package(output):
    root = Path(__file__).resolve().parents[1]
    with tempfile.TemporaryDirectory(prefix="dashcam-fc-build-") as directory:
        target = Path(directory) / "package"
        target.mkdir()
        requirements = Path(directory) / "requirements.txt"
        with requirements.open("w") as file:
            subprocess.run(["uv", "export", "--project", str(root / "server"), "--no-dev", "--no-hashes", "--no-emit-project"], stdout=file, check=True)
        # crcmod only ships a source distribution. A host-built native wheel cannot run in FC.
        requirements.write_text("\n".join(line for line in requirements.read_text().splitlines() if not line.startswith("crcmod==")) + "\n")
        subprocess.run(["uv", "pip", "install", "--no-deps", "--target", str(target), "--python-version", "3.10", "--python-platform", "x86_64-manylinux_2_17", "-r", str(requirements)], check=True)
        import crcmod
        crc_target = target / "crcmod"
        crc_target.mkdir()
        for source in Path(crcmod.__file__).parent.glob("*.py"):
            shutil.copyfile(source, crc_target / source.name)
        (target / "app.py").write_bytes((root / "server/app.py").read_bytes())
        with zipfile.ZipFile(output, "w", zipfile.ZIP_DEFLATED) as archive:
            for path in target.rglob("*"):
                if path.is_file() and "__pycache__" not in path.parts:
                    archive.write(path, path.relative_to(target))
    return output


if __name__ == "__main__":
    package(Path(sys.argv[1]))
