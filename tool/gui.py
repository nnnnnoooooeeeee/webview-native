"""WebView APK Builder: the desktop window.

Opens a native window (pywebview: Edge WebView2 on Windows, WebKit on macOS
and Linux) with the screens in tool/web/, and runs the same Gradle builds as
the terminal version (tool/build_apk.py), which it imports as `core`.

Run it with build-apk.bat, `python tool/gui.py`, or the packaged EXE.
Needs: pip install pywebview
"""

import base64
import ctypes
import json
import os
import re
import shutil
import subprocess
import sys
import threading
import time
from pathlib import Path

#========== WORKSPACE ==========
# The packaged EXE first unpacks its project into a workspace folder and points
# build_apk.py at it, so this must run before build_apk is imported.

import workspace

VERSION = "1.0.0"
BUNDLE = Path(getattr(sys, "_MEIPASS", Path(__file__).resolve().parent))
if (BUNDLE / "version.txt").is_file():
    VERSION = (BUNDLE / "version.txt").read_text().strip()
if getattr(sys, "frozen", False):
    os.environ["WEBVIEW_BUILDER_HOME"] = str(workspace.prepare_workspace(BUNDLE, VERSION))

import build_apk as core  # noqa: E402  (after the workspace is set)
import webview  # noqa: E402

WEB_DIR = BUNDLE / "web"
NO_WINDOW = {"creationflags": 0x08000000} if sys.platform == "win32" else {}  # no console pop-ups

APP_ID_RE = re.compile(r"^[a-zA-Z][a-zA-Z0-9_]*(\.[a-zA-Z][a-zA-Z0-9_]*)+$")
URL_RE = re.compile(r"^https?://\S+$", re.I)
VERSION_RE = re.compile(r"^\d+(\.\d+){0,3}$")
COLOR_RE = re.compile(r"^#[0-9a-fA-F]{6}$")
FOLDER_RE = re.compile(r"^[a-zA-Z0-9][a-zA-Z0-9._-]*$")


#========== HELPERS ==========

def data_url(path: Path) -> str:
    """An image file as a data: URL the page can show."""
    try:
        kind = "jpeg" if path.suffix.lower() in (".jpg", ".jpeg") else "png"
        return f"data:image/{kind};base64," + base64.b64encode(path.read_bytes()).decode()
    except OSError:
        return ""


def move_to_trash(path: Path):
    """Recycle Bin on Windows (can be restored), plain delete elsewhere."""
    if sys.platform != "win32":
        shutil.rmtree(path, ignore_errors=True)
        return

    class SHFILEOPSTRUCTW(ctypes.Structure):
        _fields_ = [("hwnd", ctypes.c_void_p), ("wFunc", ctypes.c_uint), ("pFrom", ctypes.c_wchar_p),
                    ("pTo", ctypes.c_wchar_p), ("fFlags", ctypes.c_ushort), ("fAnyOperationsAborted", ctypes.c_int),
                    ("hNameMappings", ctypes.c_void_p), ("lpszProgressTitle", ctypes.c_wchar_p)]

    FO_DELETE, FOF_ALLOWUNDO, FOF_NOCONFIRMATION, FOF_SILENT, FOF_NOERRORUI = 3, 0x40, 0x10, 0x4, 0x400
    op = SHFILEOPSTRUCTW(wFunc=FO_DELETE, pFrom=str(path) + "\0\0",
                         fFlags=FOF_ALLOWUNDO | FOF_NOCONFIRMATION | FOF_SILENT | FOF_NOERRORUI)
    if ctypes.windll.shell32.SHFileOperationW(ctypes.byref(op)) != 0:
        shutil.rmtree(path, ignore_errors=True)


def reveal(path: Path):
    """Shows a file in Explorer/Finder (selected), or opens a folder."""
    if sys.platform == "win32":
        if path.is_file():
            subprocess.Popen(["explorer", "/select,", str(path)])
        else:
            os.startfile(path)  # noqa: S606 (opens a folder we created)
    elif sys.platform == "darwin":
        subprocess.Popen(["open", "-R", str(path)] if path.is_file() else ["open", str(path)])
    else:
        subprocess.Popen(["xdg-open", str(path if path.is_dir() else path.parent)])


def read_key_properties() -> dict:
    props = {}
    if core.KEY_PROPERTIES.is_file():
        for line in core.KEY_PROPERTIES.read_text(encoding="utf-8", errors="replace").splitlines():
            if "=" in line and not line.lstrip().startswith("#"):
                k, v = line.split("=", 1)
                props[k.strip()] = v.strip()
    return props


def keystore_path(props: dict) -> Path:
    path = Path(props.get("storeFile", ""))
    return path if path.is_absolute() else core.ROOT / path


#========== API FOR THE PAGE ==========

class Api:
    """Every public method can be called from tool/web/app.js as window.pywebview.api.<name>()."""

    def __init__(self):
        self._window = None
        self._cancel = threading.Event()
        self._proc = None
        self._building = False
        self._java = None
        self._sdk = None

    def _emit(self, event: str, data=None):
        """Sends an event to the page: window.onBackendEvent(event, data)."""
        if self._window:
            self._window.evaluate_js(f"window.onBackendEvent({json.dumps(event)}, {json.dumps(data)})")

    #========== STATE ==========

    def get_state(self):
        apps = []
        for folder in core.available_apps():
            cfg = core.read_config(folder)
            apps.append({
                "folder": folder,
                "appName": cfg.get("appName", folder),
                "appUrl": cfg.get("appUrl", ""),
                "applicationId": cfg.get("applicationId", ""),
                "versionName": cfg.get("versionName", "1.0.0"),
                "iconBackground": cfg.get("iconBackground", "#FFFFFF"),
                "pullToRefresh": cfg.get("pullToRefresh", True),
                "icon": data_url(core.APPS_DIR / folder / "icon.png"),
            })
        last = core.last_build_number()
        return {
            "version": VERSION,
            "workspace": str(core.ROOT),
            "apps": apps,
            "lastBuild": last,
            "nextBuild": core.next_build_number(last),
            "env": self.check_env(),
            "key": self.key_info(),
            "settings": workspace.load_settings(core.ROOT),
            "building": self._building,
        }

    def check_env(self):
        self._java = core.find_java()
        self._sdk = core.find_android_sdk()
        return {
            "python": sys.version.split()[0],
            "java": self._java,
            "sdk": str(self._sdk) if self._sdk else None,
            "ok": bool(self._java and self._sdk),
        }

    def set_setting(self, name, value):
        settings = workspace.load_settings(core.ROOT)
        settings[name] = value
        workspace.save_settings(core.ROOT, settings)

    #========== SETUP ==========

    def install_tools(self):
        """Downloads Java and/or the Android SDK in the background (events: setup:*)."""
        def run():
            def progress(label, percent):
                self._emit("setup:progress", {"label": label, "percent": percent})
            try:
                core.find_java()
                java_home = Path(core.ENV.get("JAVA_HOME") or "")
                if not self._java:
                    java_home = workspace.install_java(core.TOOLS_DIR, progress)
                elif not java_home.is_dir() and core.JAVA_EXE:
                    java_home = Path(core.JAVA_EXE).resolve().parent.parent
                if not core.find_android_sdk():
                    sdk = workspace.install_android_sdk(core.TOOLS_DIR, java_home, progress)
                    core.LOCAL_PROPERTIES.write_text(f"sdk.dir={sdk.as_posix()}\n", encoding="utf-8")
                self._emit("setup:done", self.check_env())
            except Exception as e:  # shown to the user as is
                self._emit("setup:error", str(e))
        threading.Thread(target=run, daemon=True).start()

    #========== APPS ==========

    def pick_icon(self):
        result = self._window.create_file_dialog(
            webview.OPEN_DIALOG, file_types=("Images (*.png;*.jpg;*.jpeg)", "All files (*.*)"))
        if not result:
            return None
        path = Path(result[0])
        return {"path": str(path), "dataUrl": data_url(path)}

    def save_app(self, data, old_folder=None, icon_path=None):
        """Validates and writes apps/<folder>/config.json (+ icon.png). Returns an error text or None."""
        folder = (data.get("folder") or "").strip()
        others = [a for a in core.available_apps() if a != old_folder]
        if not FOLDER_RE.match(folder):
            return "The folder may only contain letters, digits, '.', '-' and '_'."
        if folder in others:
            return f"Another app already uses the folder {folder}."
        if not (data.get("appName") or "").strip():
            return "The app name is empty."
        if not URL_RE.match(data.get("appUrl", "")):
            return "The URL must start with http:// or https://"
        if not APP_ID_RE.match(data.get("applicationId", "")):
            return "The application ID must look like com.company.app"
        for other in others:
            if core.read_config(other).get("applicationId") == data["applicationId"]:
                return f"{core.read_config(other).get('appName', other)} already uses this application ID."
        if not VERSION_RE.match(data.get("versionName", "")):
            return "The version must look like 1.0.0"
        if not COLOR_RE.match(data.get("iconBackground", "")):
            return "The icon background must be a color like #FFFFFF"
        if not icon_path and not old_folder:
            return "Pick an icon for the new app."

        target = core.APPS_DIR / folder
        if old_folder and old_folder != folder:
            (core.APPS_DIR / old_folder).rename(target)
        target.mkdir(parents=True, exist_ok=True)
        config = {k: data[k] for k in ("appName", "appUrl", "applicationId", "versionName", "iconBackground")}
        config["appName"] = config["appName"].strip()
        config["pullToRefresh"] = bool(data.get("pullToRefresh", True))
        (target / "config.json").write_text(json.dumps(config, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
        if icon_path:
            shutil.copyfile(icon_path, target / "icon.png")
        return None

    def delete_app(self, folder, also_apks=False):
        move_to_trash(core.APPS_DIR / folder)
        if also_apks and core.DIST_DIR.is_dir():
            for apk in core.DIST_DIR.glob(f"{folder}-v*.apk"):
                apk.unlink()

    def open_path(self, path):
        reveal(Path(path))

    def open_dist(self):
        core.DIST_DIR.mkdir(exist_ok=True)
        reveal(core.DIST_DIR)

    def open_url(self, url):
        import webbrowser
        webbrowser.open(url)

    #========== BUILD ==========

    def plan(self, folders, number):
        """Facts and warnings for the confirm dialog."""
        results = [core.Result(f, core.read_config(f).get("appName", f), core.read_config(f).get("versionName", "1.0.0"))
                   for f in folders]
        return {
            "signing": "Release key (key.properties)" if core.KEY_PROPERTIES.is_file() else "Debug key of this computer",
            "java": self._java,
            "sdk": str(self._sdk) if self._sdk else None,
            "output": str(core.DIST_DIR),
            "warnings": core.plan_warnings(results, int(number), core.last_build_number()),
        }

    def start_build(self, folders, number):
        if self._building:
            return
        self._building = True
        self._cancel.clear()
        threading.Thread(target=self._build_all, args=(list(folders), int(number)), daemon=True).start()

    def cancel_build(self):
        self._cancel.set()
        if self._proc and self._proc.poll() is None:
            core.kill(self._proc)

    def _build_all(self, folders, number):
        core.LOG_DIR.mkdir(parents=True, exist_ok=True)
        log_path = core.LOG_DIR / f"build-{time.strftime('%Y%m%d-%H%M%S')}.log"
        started = time.time()
        ok = 0
        try:
            with open(log_path, "w", encoding="utf-8", newline="") as log:
                log.write(f"{core.APP_TITLE} {VERSION}, build {number}, {time.strftime('%Y-%m-%d %H:%M:%S')}\n\n")
                for folder in folders:
                    if self._cancel.is_set():
                        self._emit("build:app", {"folder": folder, "status": "cancelled"})
                        continue
                    if self._build_one(folder, number, log):
                        ok += 1
            if ok:
                core.DIST_DIR.mkdir(exist_ok=True)
                core.BUILD_NUMBER_FILE.write_text(str(number))
        finally:
            self._building = False
            self._emit("build:finished", {"ok": ok, "total": len(folders), "seconds": time.time() - started,
                                          "log": str(log_path), "cancelled": self._cancel.is_set()})

    def _build_one(self, folder, number, log) -> bool:
        cfg = core.read_config(folder)
        version = cfg.get("versionName", "1.0.0")
        self._emit("build:app", {"folder": folder, "status": "running"})
        started = time.time()
        if core.APK_OUTPUT.exists():
            core.APK_OUTPUT.unlink()
        cmd = [str(core.GRADLEW), "assembleRelease", f"-PappFolder={folder}", f"-PbuildNumber={number}", "--console=plain"]
        code, lines = self._run(cmd, folder, log)
        # A daemon left over from an interrupted build, or one that ran out of memory: stop and retry once.
        if code not in (0, None) and any(core.GRADLE_LOCK_RE.search(l) or core.DAEMON_CRASH_RE.search(l) for l in lines):
            self._run([str(core.GRADLEW), "--stop"], folder, log)
            code, lines = self._run(cmd, folder, log)
        seconds = time.time() - started

        if self._cancel.is_set():
            self._emit("build:app", {"folder": folder, "status": "cancelled", "seconds": seconds})
            return False
        if code != 0 or not core.APK_OUTPUT.exists():
            reason = core.gradle_reason(lines) or [l for l in lines if core.ERROR_RE.search(l)][-10:] or lines[-10:]
            hints = []
            if any(core.DISK_FULL_RE.search(l) for l in lines):
                hints.append("The disk is full. Free up a few GB and try again.")
            if any(core.DAEMON_CRASH_RE.search(l) for l in lines):
                hints.append("The computer ran out of memory. Close other programs and try again.")
            self._emit("build:app", {"folder": folder, "status": "failed", "seconds": seconds,
                                     "reason": [r.strip() for r in reason], "hints": hints})
            return False

        core.DIST_DIR.mkdir(exist_ok=True)
        apk = core.DIST_DIR / f"{folder}-v{version}-build{number}.apk"
        shutil.copy2(core.APK_OUTPUT, apk)
        self._emit("build:app", {"folder": folder, "status": "ok", "seconds": seconds,
                                 "apk": str(apk), "name": apk.name, "size": core.size_of(apk)})
        return True

    def _run(self, cmd, folder, log):
        """Runs a Gradle command, logging everything and sending its progress steps to the page."""
        log.write(f"$ {' '.join([Path(cmd[0]).stem, *cmd[1:]])}\n\n")
        lines, steps = [], []
        self._proc = subprocess.Popen(cmd, cwd=core.ROOT, env=core.ENV, stdin=subprocess.DEVNULL,
                                      stdout=subprocess.PIPE, stderr=subprocess.STDOUT, **NO_WINDOW)
        last_sent = 0.0
        for raw in iter(self._proc.stdout.readline, b""):
            line = core.ANSI_RE.sub("", raw.decode("utf-8", errors="replace")).rstrip("\r\n")
            log.write(line + "\n")
            lines.append(line)
            match = core.TASK_RE.match(line.strip())
            step = match and core.TASK_STEPS.get(match[1])
            if step and step not in steps:
                steps.append(step)
                self._emit("build:step", {"folder": folder, "step": step, "steps": steps})
            # The latest output line, at most 5 times a second.
            if time.time() - last_sent > 0.2 and line.strip():
                last_sent = time.time()
                self._emit("build:log", {"folder": folder, "line": line[-160:]})
        code = self._proc.wait()
        log.write(f"\n[exit code {code}]\n\n")
        log.flush()
        return code, lines

    #========== SIGNING KEY ==========

    def _keytool(self):
        core.find_java()
        exe = "keytool.exe" if sys.platform == "win32" else "keytool"
        if core.JAVA_EXE:
            candidate = Path(core.JAVA_EXE).parent / exe
            if candidate.is_file():
                return str(candidate)
        return shutil.which("keytool")

    def _keytool_run(self, args, store_password, key_password=None):
        """keytool with the passwords passed in environment variables, not on the command line."""
        tool = self._keytool()
        if not tool:
            raise RuntimeError("keytool not found. Install Java first (Setup).")
        env = dict(core.ENV, KS_PASS=store_password, KEY_PASS=key_password or store_password)
        return subprocess.run([tool, *args], env=env, capture_output=True, text=True, errors="replace", **NO_WINDOW)

    def key_info(self):
        props = read_key_properties()
        if not props:
            return {"status": "debug"}
        info = {"status": "release", "alias": props.get("keyAlias"), "path": str(keystore_path(props))}
        try:
            out = self._keytool_run(["-list", "-v", "-keystore", info["path"], "-storepass:env", "KS_PASS",
                                     "-alias", props.get("keyAlias", "")], props.get("storePassword", ""))
            if m := re.search(r"until: (.+)", out.stdout):
                info["validUntil"] = m[1].strip()
            if m := re.search(r"SHA256: ([0-9A-F:]+)", out.stdout):
                info["fingerprint"] = m[1]
            if out.returncode != 0:
                info["problem"] = "The keystore or its password in key.properties is not valid."
        except Exception as e:
            info["problem"] = str(e)
        return info

    def pick_save_keystore(self):
        result = self._window.create_file_dialog(webview.SAVE_DIALOG, save_filename="upload-keystore.jks")
        return (result if isinstance(result, str) else (result or [None])[0])

    def pick_keystore(self):
        result = self._window.create_file_dialog(
            webview.OPEN_DIALOG, file_types=("Keystores (*.jks;*.keystore;*.p12;*.pfx)", "All files (*.*)"))
        return result[0] if result else None

    def key_create(self, opts):
        """Creates a PKCS12 keystore with keytool and points key.properties at it."""
        path = Path(opts["path"])
        if path.exists():
            return f"{path} already exists. Pick another file name."
        path.parent.mkdir(parents=True, exist_ok=True)
        dname = f"CN={opts['name']}" + (f", O={opts['org']}" if opts.get("org") else "") + \
                (f", C={opts['country']}" if len(opts.get("country", "")) == 2 else "")
        days = int(opts.get("years", 27)) * 365  # keytool wants days
        out = self._keytool_run(["-genkeypair", "-keystore", str(path), "-storetype", "PKCS12", "-keyalg", "RSA",
                                 "-keysize", "2048", "-validity", str(days), "-alias", opts["alias"], "-dname", dname,
                                 "-storepass:env", "KS_PASS", "-keypass:env", "KEY_PASS"], opts["password"])
        if out.returncode != 0:
            return out.stderr.strip() or out.stdout.strip() or "keytool failed"
        self._write_key_properties(path, opts["password"], opts["alias"], opts["password"])
        return None

    def key_import(self, opts):
        """Checks an existing keystore and its passwords, then points key.properties at it."""
        path = Path(opts["path"])
        out = self._keytool_run(["-list", "-v", "-keystore", str(path), "-storepass:env", "KS_PASS"], opts["storePassword"])
        if out.returncode != 0:
            return "Wrong keystore password, or not a keystore file."
        aliases = [m for m in re.findall(r"Alias name: (.+)", out.stdout)]
        alias = (opts.get("alias") or "").strip() or (aliases[0].strip() if aliases else "")
        if alias not in [a.strip() for a in aliases]:
            return f"No key named '{alias}'. The file has: {', '.join(a.strip() for a in aliases)}"
        self._write_key_properties(path, opts["storePassword"], alias, opts.get("keyPassword") or opts["storePassword"])
        return None

    def key_remove(self):
        """Stops using the key (the keystore file itself stays where it is)."""
        if core.KEY_PROPERTIES.is_file():
            core.KEY_PROPERTIES.unlink()

    def key_secrets(self):
        """The four GitHub Actions secrets the workflow reads, for copying."""
        props = read_key_properties()
        if not props:
            return None
        return {
            "KEYSTORE_BASE64": base64.b64encode(keystore_path(props).read_bytes()).decode(),
            "KEYSTORE_PASSWORD": props.get("storePassword", ""),
            "KEY_ALIAS": props.get("keyAlias", ""),
            "KEY_PASSWORD": props.get("keyPassword", ""),
        }

    def _write_key_properties(self, path: Path, store_password, alias, key_password):
        core.KEY_PROPERTIES.write_text(
            f"storeFile={path.as_posix()}\nstorePassword={store_password}\nkeyAlias={alias}\nkeyPassword={key_password}\n",
            encoding="utf-8")


#========== MAIN ==========

def main():
    api = Api()
    settings = workspace.load_settings(core.ROOT)
    window = webview.create_window(
        core.APP_TITLE, url=str(WEB_DIR / "index.html"), js_api=api,
        width=1100, height=740, min_size=(900, 620),
        background_color="#131318" if settings.get("theme") == "dark" else "#FCF8FF",
    )
    api._window = window
    webview.start(debug=bool(os.environ.get("WEBVIEW_BUILDER_DEBUG")))


if __name__ == "__main__":
    main()
