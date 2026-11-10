"""Packs the desktop window into one Windows EXE with PyInstaller.

  pip install pyinstaller pywebview pillow
  python tool/build_exe.py 1.2.0

Result: tool/dist/WebView APK Builder.exe. It carries the project (app/,
gradle/, apps/...) as template.zip and unpacks it to Documents/WebView APK
Builder on first start (see tool/workspace.py).
"""

import shutil
import subprocess
import sys
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
TOOL = ROOT / "tool"
BUILD = TOOL / "build"

#========== TEMPLATE ZIP ==========
# What a user's workspace needs to build WebView apps. The Android builder app
# (builder/) and repository files are left out.
INCLUDE = ["app", "apps", "gradle", "gradlew", "gradlew.bat", "build.gradle.kts",
           "settings.gradle.kts", "gradle.properties", ".gitattributes", "tool/build_apk.py"]
SKIP_PARTS = {"build", ".gradle", ".kotlin", "__pycache__"}


def make_template(target: Path):
    with zipfile.ZipFile(target, "w", zipfile.ZIP_DEFLATED) as zf:
        for name in INCLUDE:
            path = ROOT / name
            files = [path] if path.is_file() else [p for p in path.rglob("*") if p.is_file()]
            for f in files:
                rel = f.relative_to(ROOT)
                if SKIP_PARTS.isdisjoint(rel.parts):
                    zf.write(f, rel.as_posix())


def make_icon(target: Path) -> bool:
    """Windows .ico from app/template/icon.png (needs Pillow; skipped without it)."""
    try:
        from PIL import Image
    except ImportError:
        return False
    image = Image.open(ROOT / "app" / "template" / "icon.png").convert("RGBA")
    background = Image.new("RGBA", image.size, (224, 224, 255, 255))  # light indigo tile behind the globe
    background.alpha_composite(image)
    background.save(target, sizes=[(16, 16), (32, 32), (48, 48), (64, 64), (128, 128), (256, 256)])
    return True


def main():
    version = sys.argv[1] if len(sys.argv) > 1 else "1.0.0"
    shutil.rmtree(BUILD, ignore_errors=True)
    BUILD.mkdir(parents=True)
    make_template(BUILD / "template.zip")
    (BUILD / "version.txt").write_text(version)
    sep = ";" if sys.platform == "win32" else ":"  # PyInstaller's --add-data separator
    args = [sys.executable, "-m", "PyInstaller", "--noconfirm", "--onefile", "--windowed",
            "--name", "WebView APK Builder",
            "--distpath", str(TOOL / "dist"), "--workpath", str(BUILD / "work"), "--specpath", str(BUILD),
            "--add-data", f"{TOOL / 'web'}{sep}web",
            "--add-data", f"{BUILD / 'template.zip'}{sep}.",
            "--add-data", f"{BUILD / 'version.txt'}{sep}.",
            "--paths", str(TOOL)]
    if make_icon(BUILD / "icon.ico"):
        args += ["--icon", str(BUILD / "icon.ico")]
    subprocess.run([*args, str(TOOL / "gui.py")], check=True)


if __name__ == "__main__":
    main()
