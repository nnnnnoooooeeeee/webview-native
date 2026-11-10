"""Workspace and tool setup for the desktop window.

The packaged app (WebView APK Builder.exe) carries the project as template.zip.
On first start it unpacks it to Documents/WebView APK Builder, the workspace
where apps/, dist/ and the signing key live. A newer EXE refreshes the build
files there but never touches apps/, dist/, tools/ or key.properties.

It also installs Java (Eclipse Temurin 17) and the Android SDK command-line
tools into <workspace>/tools when the computer has neither.
"""

import json
import os
import platform
import re
import shutil
import subprocess
import sys
import tarfile
import urllib.request
import zipfile
from pathlib import Path

#========== WORKSPACE ==========

# Paths inside template.zip that belong to the builder and may be replaced by a
# newer version. Everything else (apps/, dist/, tools/, key files) is the user's.
MANAGED = ("app/", "gradle/", "gradlew", "gradlew.bat", "build.gradle.kts",
           "settings.gradle.kts", "gradle.properties", ".gitattributes", "tool/")
VERSION_FILE = ".builder-template"


def default_workspace() -> Path:
    return Path.home() / "Documents" / "WebView APK Builder"


def prepare_workspace(bundle: Path, version: str) -> Path:
    """Unpacks or refreshes the bundled project and returns the workspace folder."""
    workspace = Path(os.environ.get("WEBVIEW_BUILDER_HOME") or default_workspace())
    template = bundle / "template.zip"
    if not template.is_file():
        return workspace  # running from source: the repository is the workspace
    marker = workspace / VERSION_FILE
    current = marker.read_text().strip() if marker.is_file() else None
    if current != version:
        first_run = not (workspace / "apps").is_dir()
        with zipfile.ZipFile(template) as zf:
            for name in zf.namelist():
                managed = name.startswith(MANAGED) or name in MANAGED
                if managed or (first_run and name.startswith("apps/")):
                    zf.extract(name, workspace)
        gradlew = workspace / "gradlew"
        if gradlew.exists():
            gradlew.chmod(0o755)
        marker.write_text(version)
    return workspace


#========== DOWNLOADS ==========

def _download(url: str, target: Path, progress, label: str):
    """Downloads url to target, calling progress(label, percent) as it goes."""
    request = urllib.request.Request(url, headers={"User-Agent": "WebView-APK-Builder"})
    with urllib.request.urlopen(request, timeout=60) as response, open(target, "wb") as out:
        total = int(response.headers.get("Content-Length") or 0)
        done = 0
        while chunk := response.read(256 * 1024):
            out.write(chunk)
            done += len(chunk)
            # Percent of the file downloaded (unknown size: stays at 0).
            progress(label, round(done * 100 / total) if total else 0)


def _os_name():
    return {"win32": "windows", "darwin": "mac"}.get(sys.platform, "linux")


def _arch():
    machine = platform.machine().lower()
    return "aarch64" if machine in ("arm64", "aarch64") else "x64"


def install_java(tools: Path, progress):
    """Eclipse Temurin JDK 17 into tools/jdk."""
    target = tools / "jdk"
    if target.is_dir():
        return target
    tools.mkdir(parents=True, exist_ok=True)
    url = f"https://api.adoptium.net/v3/binary/latest/17/ga/{_os_name()}/{_arch()}/jdk/hotspot/normal/eclipse"
    archive = tools / ("jdk.zip" if sys.platform == "win32" else "jdk.tar.gz")
    _download(url, archive, progress, "Downloading Java 17")
    progress("Unpacking Java", 100)
    unpack = tools / "jdk-unpack"
    shutil.rmtree(unpack, ignore_errors=True)
    if archive.suffix == ".zip":
        zipfile.ZipFile(archive).extractall(unpack)
    else:
        with tarfile.open(archive) as tf:
            tf.extractall(unpack, filter="data")
    inner = next(p for p in unpack.iterdir() if p.is_dir())
    home = inner / "Contents" / "Home" if (inner / "Contents" / "Home").is_dir() else inner  # macOS layout
    shutil.move(str(home), str(target))
    shutil.rmtree(unpack, ignore_errors=True)
    archive.unlink()
    return target


def _latest_cmdline_tools():
    """Newest command-line tools build number from Google's SDK repository index."""
    try:
        with urllib.request.urlopen("https://dl.google.com/android/repository/repository2-3.xml", timeout=30) as r:
            names = re.findall(rb"commandlinetools-\w+-(\d+)_latest\.zip", r.read())
        return max(int(n) for n in names)
    except Exception:
        return 13114758  # known good fallback


def install_android_sdk(tools: Path, java_home: Path, progress, platform_version="36", build_tools="36.0.0"):
    """Android command-line tools, licenses, platform and build tools into tools/android-sdk."""
    sdk = tools / "android-sdk"
    latest = sdk / "cmdline-tools" / "latest"
    if not latest.is_dir():
        sdk.mkdir(parents=True, exist_ok=True)
        os_part = {"windows": "win", "mac": "mac", "linux": "linux"}[_os_name()]
        url = f"https://dl.google.com/android/repository/commandlinetools-{os_part}-{_latest_cmdline_tools()}_latest.zip"
        archive = sdk / "cmdline-tools.zip"
        _download(url, archive, progress, "Downloading Android SDK tools")
        progress("Unpacking Android SDK tools", 100)
        unpack = sdk / "cmdline-unpack"
        zipfile.ZipFile(archive).extractall(unpack)
        latest.parent.mkdir(parents=True, exist_ok=True)
        shutil.move(str(unpack / "cmdline-tools"), str(latest))
        shutil.rmtree(unpack, ignore_errors=True)
        archive.unlink()
        for f in (latest / "bin").iterdir():
            f.chmod(0o755)

    sdkmanager = latest / "bin" / ("sdkmanager.bat" if sys.platform == "win32" else "sdkmanager")
    env = dict(os.environ, JAVA_HOME=str(java_home))
    flags = {"creationflags": 0x08000000} if sys.platform == "win32" else {}  # no console window
    progress("Accepting Android SDK licenses", 100)
    subprocess.run([str(sdkmanager), f"--sdk_root={sdk}", "--licenses"], input="y\n" * 40, text=True,
                   env=env, capture_output=True, **flags)
    progress("Installing Android platform and build tools", 0)
    proc = subprocess.Popen([str(sdkmanager), f"--sdk_root={sdk}", f"platforms;android-{platform_version}",
                             f"build-tools;{build_tools}"], env=env, stdout=subprocess.PIPE,
                            stderr=subprocess.STDOUT, text=True, errors="replace", **flags)
    for line in proc.stdout:  # universal newlines also split sdkmanager's \r progress updates
        if m := re.search(r"(\d+)%", line):
            progress("Installing Android platform and build tools", int(m[1]))
    if proc.wait() != 0:
        raise RuntimeError("sdkmanager failed to install the Android platform")
    return sdk


#========== SETTINGS ==========

def load_settings(workspace: Path) -> dict:
    try:
        return json.loads((workspace / ".builder-settings.json").read_text())
    except (OSError, ValueError):
        return {}


def save_settings(workspace: Path, settings: dict):
    (workspace / ".builder-settings.json").write_text(json.dumps(settings, indent=2))
