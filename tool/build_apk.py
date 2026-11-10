"""Build one or more apps from apps/ into release APKs on this machine.

This is the terminal version. build-apk.bat opens the desktop window
(tool/gui.py) instead, unless you pass app names:
  python tool/build_apk.py                     # interactive menu
  python tool/build_apk.py google my-shop      # no questions asked
  python tool/build_apk.py all --build-number 12

Each APK is copied to dist/<app>-v<version>-build<number>.apk and the full
Gradle output goes to dist/logs/.

Gradle reads apps/<app>/ directly (-PappFolder=<app>), so building never
changes any file in the git working tree.

Needs a JDK 17+ (JAVA_HOME, java on the PATH, or Android Studio's bundled
one, or tools/jdk from the desktop window's setup) and the Android SDK
(local.properties, ANDROID_HOME, tools/android-sdk, or the default Android
Studio location).
"""

import argparse
import codecs
import json
import os
import re
import shutil
import subprocess
import sys
import textwrap
import threading
import time
from dataclasses import dataclass
from pathlib import Path

# The project folder. The packaged desktop app (EXE) points it at its own
# workspace with WEBVIEW_BUILDER_HOME.
ROOT = Path(os.environ.get("WEBVIEW_BUILDER_HOME") or Path(__file__).resolve().parent.parent)
APPS_DIR = ROOT / "apps"
DIST_DIR = ROOT / "dist"
LOG_DIR = DIST_DIR / "logs"
BUILD_NUMBER_FILE = DIST_DIR / ".last_build_number"
APK_OUTPUT = ROOT / "app" / "build" / "outputs" / "apk" / "release" / "app-release.apk"
KEY_PROPERTIES = ROOT / "key.properties"
LOCAL_PROPERTIES = ROOT / "local.properties"
GRADLEW = ROOT / ("gradlew.bat" if sys.platform == "win32" else "gradlew")
# Java and the Android SDK installed by the desktop window's setup screen.
TOOLS_DIR = ROOT / "tools"
MIN_FREE_GB = 2
FIRST_BUILD_NUMBER = 1

ANSI_RE = re.compile(r"\x1b\[[0-9;?]*[A-Za-z]")
ERROR_RE = re.compile(r"error|exception|failed|what went wrong", re.I)
# Gradle tasks worth showing as progress, from "> Task :app:<name>" lines.
TASK_RE = re.compile(r"^> Task :app:(\w+)")
TASK_STEPS = {
    "generateAppIcons": "Generating launcher icons",
    "compileReleaseKotlin": "Compiling Kotlin",
    "minifyReleaseWithR8": "Shrinking with R8",
    "packageRelease": "Packaging APK",
}
DISK_FULL_RE = re.compile(r"not enough space on the disk|no space left on device", re.I)
# A daemon still holding a cache lock from an interrupted build.
GRADLE_LOCK_RE = re.compile(r"Cannot lock .* as it has already been locked|Timeout waiting to lock", re.I)
# The JVM ran out of memory and the daemon was gone before it could report anything.
DAEMON_CRASH_RE = re.compile(r"daemon disappeared unexpectedly|insufficient memory for the Java Runtime", re.I)
BUILD_NO_RE = re.compile(r"-build(\d+)\.apk$")

SPINNER = "⠋⠙⠹⠸⠼⠴⠦⠧⠇⠏"
LOGO = """\
█   █ █▀▀▀ █▀▀▄ █   █ ▀█▀ █▀▀▀ █   █
█ █ █ █▀▀  █▀▀▄ ▀▄ ▄▀  █  █▀▀  █ █ █
▀▄▀▄▀ █▄▄▄ █▄▄▀   █   ▄█▄ █▄▄▄ ▀▄▀▄▀""".splitlines()

APP_TITLE = "WebView APK Builder"

COLOR = False
ENV = dict(os.environ)  # environment for Gradle, with JAVA_HOME filled in when needed
JAVA_EXE = None  # the java executable find_java() picked (keytool sits next to it)


@dataclass
class Result:
    app: str
    name: str
    version: str
    status: str = "skipped"  # ok, failed or skipped
    seconds: float = 0
    apk: Path = None
    size: str = ""


class BuildError(Exception):
    pass


# --- Terminal output -------------------------------------------------------

def _style(code):
    return lambda text: f"\x1b[{code}m{text}\x1b[0m" if COLOR else text


bold, dim, red, green, yellow, cyan = map(_style, ("1", "2", "31", "32", "33", "36"))


def use_colors():
    """ANSI colors only on a terminal, and not when NO_COLOR is set."""
    if not sys.stdout.isatty() or os.environ.get("NO_COLOR"):
        return False
    if sys.platform == "win32":
        os.system("")  # makes the classic Windows console understand ANSI codes
    return True


def columns():
    return shutil.get_terminal_size((80, 24)).columns


def box_width():
    return min(68, columns() - 2)


def fit(text, width):
    return text if len(text) <= width else text[:max(width - 1, 0)] + "…"


def box(lines, title=""):
    """A rounded box; a line is a string or (string, style)."""
    inner = box_width() - 2
    if title:
        left = (inner - len(title) - 2) // 2
        right = inner - len(title) - 2 - left
        print(dim("╭" + "─" * left) + bold(f" {title} ") + dim("─" * right + "╮"))
    else:
        print(dim("╭" + "─" * inner + "╮"))
    for line in lines:
        text, style = line if isinstance(line, tuple) else (line, str)
        text = fit(text, inner - 3)
        print(dim("│") + "  " + style(text) + " " * (inner - 2 - len(text)) + dim("│"))
    print(dim("╰" + "─" * inner + "╯"))


def rule(title, char="─", center=False):
    label = f" {title} "
    rest = max(box_width() - len(label), 4)
    left = rest // 2 if center else 2
    print(dim(char * left) + bold(label) + dim(char * (rest - left)))


def say(mark, text):
    """`text` after a one-character `mark`, wrapped to the window."""
    lines = textwrap.wrap(text, max(columns() - 6, 20)) or [""]
    print(f"  {mark} {lines[0]}")
    for line in lines[1:]:
        print(f"    {line}")


def ask(prompt, default=None):
    suffix = f" {dim(f'[{default}]')}" if default else ""
    return input(f"  {cyan('?')} {prompt}{suffix}: ").strip() or (default or "")


def confirm(prompt):
    return ask(f"{prompt} {dim('[y/n]')}").lower() in ("y", "yes", "ya")


def rel(path):
    try:
        return str(path.relative_to(ROOT))
    except ValueError:
        return str(path)


def duration(seconds):
    minutes, seconds = divmod(round(seconds), 60)
    return f"{minutes}m {seconds:02d}s" if minutes else f"{seconds}s"


def clock(seconds):
    hours, rest = divmod(int(seconds), 3600)
    return f"{hours}:{rest // 60:02d}:{rest % 60:02d}"


def size_of(path):
    return f"{path.stat().st_size / 1024 / 1024:.1f} MB"


class Spinner:
    """"⠼ Building google…  0:01:12", redrawn in place.

    Kept short on purpose: a line that wraps can't be redrawn in place.
    """

    def __init__(self, label):
        self.label = fit(label, max(columns() - 20, 10))
        self.frame = 0
        sys.stdout.write("\x1b[?25l")  # hide the cursor

    def draw(self, seconds):
        mark = SPINNER[self.frame % len(SPINNER)]
        sys.stdout.write(f"\r\x1b[2K  {cyan(mark)} {cyan(self.label + '…')}  {dim(clock(seconds))}")
        sys.stdout.flush()
        self.frame += 1

    def clear(self):
        sys.stdout.write("\r\x1b[2K")
        sys.stdout.flush()

    def stop(self):
        sys.stdout.write("\r\x1b[2K\x1b[?25h")
        sys.stdout.flush()


# --- Apps ------------------------------------------------------------------

def available_apps():
    if not APPS_DIR.is_dir():
        return []
    return sorted(d.name for d in APPS_DIR.iterdir() if d.is_dir() and (d / "config.json").is_file())


def read_config(app):
    try:
        with open(APPS_DIR / app / "config.json", encoding="utf-8") as f:
            return json.load(f)
    except (OSError, ValueError):
        return {}


def parse_selection(text, apps):
    """Accepts numbers, ranges and names: '1,3', '2-5', 'google my-shop', 'all'."""
    text = text.strip()
    if not text:
        return []
    if text.lower() in ("all", "semua", "*"):
        return list(apps)
    chosen = []
    for token in text.replace(",", " ").split():
        if token in apps:
            picks = [token]
        elif "-" in token and all(p.isdigit() for p in token.split("-", 1)):
            start, end = sorted(int(p) for p in token.split("-", 1))
            if start < 1 or end > len(apps):
                raise ValueError(f"Range {token} is outside 1-{len(apps)}.")
            picks = apps[start - 1:end]
        elif token.isdigit():
            n = int(token)
            if not 1 <= n <= len(apps):
                raise ValueError(f"Number {token} is outside 1-{len(apps)}.")
            picks = [apps[n - 1]]
        else:
            raise ValueError(f"There is no app called \"{token}\".")
        chosen += [a for a in picks if a not in chosen]
    return chosen


def read_key():
    """One keypress: 'up', 'down', 'home', 'end', 'space', 'enter', 'esc' or the
    (lowercase) character."""
    if sys.platform == "win32":
        import msvcrt
        ch = msvcrt.getwch()
        if ch in ("\x00", "\xe0"):  # arrow and other special keys come as two codes
            return {"H": "up", "P": "down", "G": "home", "O": "end"}.get(msvcrt.getwch(), "")
    else:
        import select
        import termios
        import tty
        fd = sys.stdin.fileno()
        old = termios.tcgetattr(fd)
        try:
            tty.setraw(fd)
            ch = os.read(fd, 1).decode(errors="ignore")
            if ch == "\x1b" and select.select([fd], [], [], 0.05)[0]:
                seq = os.read(fd, 8).decode(errors="ignore")
                return {"[A": "up", "[B": "down", "OA": "up", "OB": "down",
                        "[H": "home", "[F": "end"}.get(seq, "")
        finally:
            termios.tcsetattr(fd, termios.TCSADRAIN, old)
    if ch == "\x03":
        raise KeyboardInterrupt
    return {" ": "space", "\r": "enter", "\n": "enter", "\x1b": "esc"}.get(ch, ch.lower())


def can_pick(rows):
    """Arrow-key menus need a real console for both input and output, tall
    enough to redraw the whole list in place."""
    return COLOR and sys.stdin.isatty() and shutil.get_terminal_size().lines >= rows + 4


def pick_apps(apps, rows, widths):
    """Checkbox list: arrows move, space ticks, enter confirms. Returns the ticked
    apps (the highlighted one when nothing is ticked), or [] when cancelled."""
    ticked, pos, drawn = set(), 0, 0
    keys = "↑↓ move · space select · a all · enter build · esc cancel"

    def row_line(i, cursor):
        cells = [c.ljust(w) for c, w in zip(rows[i], widths)]
        pointer = cyan("❯") if cursor else " "
        mark = green("●") if i in ticked else dim("○")
        name = cyan(bold(cells[0])) if cursor else bold(cells[0])
        return f"  {pointer} {mark}  {name}   {cells[1]}   {dim(cells[2])}"

    def draw(final=False):
        nonlocal drawn
        if drawn:
            sys.stdout.write(f"\x1b[{drawn}F\x1b[J")  # back to the first row, clear below
        lines = [row_line(i, i == pos and not final) for i in range(len(rows))]
        if not final:
            count = f"{len(ticked)} selected" if ticked else "none selected"
            lines += ["", "  " + dim(fit(f"{count} · {keys}", columns() - 3))]
        sys.stdout.write("".join(line + "\n" for line in lines))
        sys.stdout.flush()
        drawn = len(lines)

    # Hide the cursor, and turn off line wrapping: the redraw moves up one
    # terminal line per list line, so a line that wrapped (narrow window, or a
    # symbol the font draws double-wide) would make it land one line too low.
    sys.stdout.write("\x1b[?25l\x1b[?7l")
    try:
        draw()
        while True:
            key = read_key()
            if key in ("up", "k"):
                pos = (pos - 1) % len(rows)
            elif key in ("down", "j"):
                pos = (pos + 1) % len(rows)
            elif key == "home":
                pos = 0
            elif key == "end":
                pos = len(rows) - 1
            elif key == "space":
                ticked ^= {pos}
            elif key == "a":
                ticked = set() if len(ticked) == len(rows) else set(range(len(rows)))
            elif key == "enter":
                ticked = ticked or {pos}
                draw(final=True)
                return [apps[i] for i in sorted(ticked)]
            elif key in ("esc", "q"):
                return []
            else:
                continue
            draw()
    finally:
        sys.stdout.write("\x1b[?7h\x1b[?25h")
        sys.stdout.flush()


def choose_apps(apps):
    configs = {app: read_config(app) for app in apps}
    head = ["#", "Folder", "App name", "Version"]
    rows = [[str(i), app, configs[app].get("appName", "(invalid config.json)"),
             configs[app].get("versionName", "-")] for i, app in enumerate(apps, 1)]
    widths = [max(len(r[i]) for r in [head, *rows]) for i in range(len(head))]

    print("\n  " + bold("Which apps do you want to build?") + "\n")
    if can_pick(len(rows)):
        print("       " + dim("   ".join(h.ljust(w) for h, w in zip(head[1:], widths[1:]))))
        chosen = pick_apps(apps, [r[1:] for r in rows], widths[1:])
        print()
        if chosen:
            say(green("✓"), f"{len(chosen)} app{'s' if len(chosen) > 1 else ''} selected:"
                f" {', '.join(chosen)}")
        return chosen

    print("   " + dim("   ".join(h.ljust(w) for h, w in zip(head, widths))))
    for row in rows:
        cells = [c.ljust(w) for c, w in zip(row, widths)]
        print("   " + "   ".join([cyan(cells[0].rjust(widths[0])), bold(cells[1]),
                                  cells[2], dim(cells[3])]))
    print()
    print("  " + dim("Type a number (3), a list (1,3,5), a range (2-4), folder names,"))
    print("  " + dim("or 'all' for every app."))
    print()
    while True:
        try:
            chosen = parse_selection(ask("Apps"), apps)
        except ValueError as e:
            say(red("✗"), str(e))
            continue
        if chosen:
            say(green("✓"), f"{len(chosen)} app{'s' if len(chosen) > 1 else ''} selected:"
                f" {', '.join(chosen)}")
            return chosen
        say(red("✗"), "Nothing selected. Type a number, or close the window to cancel.")


def last_build_number():
    """Highest build number from dist/.last_build_number and the APKs in dist/
    (0 when there is none yet)."""
    found = [0]
    try:
        found.append(int(BUILD_NUMBER_FILE.read_text().strip()))
    except (OSError, ValueError):
        pass
    if DIST_DIR.is_dir():
        found += [int(m[1]) for f in DIST_DIR.glob("*.apk") if (m := BUILD_NO_RE.search(f.name))]
    return max(found)


def next_build_number(last):
    return max(last + 1, FIRST_BUILD_NUMBER)


def choose_build_number(last):
    print()
    if last:
        print(f"  {dim('Last local build')}  {last}")
    while True:
        answer = ask("Build number (versionCode)", str(next_build_number(last)))
        if answer.isdigit() and int(answer) > 0:
            return int(answer)
        say(red("✗"), "The build number must be a whole number, starting at 1.")


def free_gb(path):
    try:
        return shutil.disk_usage(path).free / 1024 ** 3
    except OSError:
        return None


# --- Tools -----------------------------------------------------------------

def java_major(java):
    """Major version of a java executable (17, 21, 25...), or 0 if it won't run."""
    try:
        out = subprocess.run([str(java), "-version"], capture_output=True, text=True,
                             errors="replace", timeout=30).stderr
    except (OSError, subprocess.SubprocessError):
        return 0
    match = re.search(r'version "(\d+)(?:\.(\d+))?', out)
    if not match:
        return 0
    major = int(match[1])
    return int(match[2] or 0) if major == 1 else major


def find_java():
    """tools/jdk, JAVA_HOME, java on the PATH, then Android Studio's bundled JDK.
    Sets JAVA_HOME in ENV when it has to fall back. Returns a description or None."""
    exe = "java.exe" if sys.platform == "win32" else "java"
    candidates = []
    local_jdk = TOOLS_DIR / "jdk"
    candidates.append(("installed by the builder", local_jdk / "bin" / exe, local_jdk))
    if os.environ.get("JAVA_HOME"):
        candidates.append(("JAVA_HOME", Path(os.environ["JAVA_HOME"]) / "bin" / exe, None))
    if shutil.which("java"):
        candidates.append(("PATH", Path(shutil.which("java")), None))
    studio_dirs = [Path(os.environ[v]) / "Android" / "Android Studio"
                   for v in ("ProgramFiles", "LOCALAPPDATA") if os.environ.get(v)]
    studio_dirs.append(Path("/Applications/Android Studio.app/Contents"))
    for studio in studio_dirs:
        for jbr in ("jbr", "jre"):
            home = studio / jbr
            candidates.append(("Android Studio", home / "bin" / exe, home))
    global JAVA_EXE
    for source, java, home in candidates:
        if java.is_file() and (major := java_major(java)) >= 17:
            if home:
                ENV["JAVA_HOME"] = str(home)
            JAVA_EXE = java
            return f"Java {major} ({source})"
    return None


def find_android_sdk():
    """sdk.dir from local.properties, else ANDROID_HOME / the default location,
    which is then written to local.properties. Returns the SDK path or None."""
    if LOCAL_PROPERTIES.is_file():
        for line in LOCAL_PROPERTIES.read_text(encoding="utf-8", errors="replace").splitlines():
            if line.strip().startswith("sdk.dir="):
                path = line.split("=", 1)[1].strip().replace("\\:", ":").replace("\\\\", "\\")
                if Path(path).is_dir():
                    return Path(path)
    candidates = [os.environ.get("ANDROID_HOME"), os.environ.get("ANDROID_SDK_ROOT"),
                  str(TOOLS_DIR / "android-sdk")]
    if os.environ.get("LOCALAPPDATA"):
        candidates.append(str(Path(os.environ["LOCALAPPDATA"]) / "Android" / "Sdk"))
    candidates += [str(Path.home() / "Library" / "Android" / "sdk"), str(Path.home() / "Android" / "Sdk")]
    for path in candidates:
        # A folder only counts once it has the command-line tools or a platform in it.
        if path and Path(path).is_dir() and any((Path(path) / d).is_dir() for d in ("cmdline-tools", "platforms")):
            # Forward slashes need no escaping in a .properties file.
            LOCAL_PROPERTIES.write_text(f"sdk.dir={Path(path).as_posix()}\n", encoding="utf-8")
            return Path(path)
    return None


# --- Running commands ------------------------------------------------------

def kill(proc):
    """Stop the process and everything it started (Gradle daemons excluded)."""
    if sys.platform == "win32":
        subprocess.call(["taskkill", "/T", "/F", "/PID", str(proc.pid)],
                        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    else:
        proc.kill()


def run(cmd, label, log):
    """Run `cmd` with its output going to `log`, printing its progress lines
    under a spinner. Returns the exit code and the output lines."""
    shown_cmd = " ".join([Path(cmd[0]).stem, *cmd[1:]])
    log.write(f"$ {shown_cmd}\n\n")
    log.flush()
    proc = subprocess.Popen(cmd, cwd=ROOT, env=ENV, stdin=subprocess.DEVNULL,
                            stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    lines, steps = [], []

    def read():
        decoder = codecs.getincrementaldecoder("utf-8")(errors="replace")
        rest = ""
        while chunk := proc.stdout.read1(65536):
            text = ANSI_RE.sub("", decoder.decode(chunk))
            log.write(text)
            *done, rest = re.split(r"\r\n?|\n", rest + text)
            lines.extend(done)
            for line in done:
                match = TASK_RE.match(line.strip())
                step = match and TASK_STEPS.get(match[1])
                if step and step not in steps:
                    steps.append(step)
        if rest:
            lines.append(rest)

    reader = threading.Thread(target=read, daemon=True)
    reader.start()
    started = time.time()
    spinner = Spinner(label) if COLOR else None
    shown = 0

    def show_steps():
        nonlocal shown
        new = steps[shown:]
        if new and spinner:
            spinner.clear()
        for step in new:
            print("    " + dim(f"· {fit(step, columns() - 8)}"))
        shown += len(new)

    if not spinner:
        print(f"  {label}...")
    try:
        while proc.poll() is None:
            show_steps()
            if spinner:
                spinner.draw(time.time() - started)
            time.sleep(0.1)
    except KeyboardInterrupt:
        kill(proc)
        raise
    finally:
        if spinner:
            spinner.stop()
    reader.join()
    show_steps()
    log.write(f"\n[exit code {proc.returncode}]\n\n")
    log.flush()
    return proc.returncode, lines


def gradle_reason(lines):
    """The lines under Gradle's "* What went wrong:" headings, up to "* Try:"."""
    reason, inside = [], False
    for line in lines:
        text = line.strip()
        if text == "* What went wrong:":
            inside = True
        elif text.startswith("* "):
            inside = False
        elif inside and text:
            reason.append(text)
    # Long cause chains: the first line says what failed, the last ones why.
    return reason if len(reason) <= 8 else [*reason[:2], "…", *reason[-5:]]


def show_failure(what, code, lines, seconds):
    say(red("✗"), f"{what} failed after {duration(seconds)} (exit code {code})")
    # Wrapped, not cut off: the reason is often at the end of the line.
    shown = (gradle_reason(lines) or [l for l in lines if ERROR_RE.search(l)]
             or [l for l in lines if l.strip()])
    for line in shown[-10:]:
        for part in textwrap.wrap(line.strip(), max(columns() - 7, 20), subsequent_indent="  "):
            print("    " + dim(part))
    if any(DISK_FULL_RE.search(l) for l in lines):
        say(yellow("!"), "The disk is full. Free up a few GB on the drive with the project"
            " and with C:\\Users\\<you>\\.gradle, then try again.")
    if any(DAEMON_CRASH_RE.search(l) for l in lines):
        say(yellow("!"), "The computer ran out of memory during the Gradle build. Close other"
            " programs (extra VS Code windows, Android Studio, browsers) and try again; the JVM's"
            " crash report is hs_err_pid*.log in the project folder.")


def run_step(cmd, label, log):
    """Run `cmd`; raise BuildError (after printing why) when it fails."""
    started = time.time()
    code, lines = run(cmd, label, log)
    if code != 0 and GRADLEW.exists() and any(GRADLE_LOCK_RE.search(l) for l in lines):
        say(yellow("!"), "A Gradle daemon still holds a lock from an interrupted build."
            " Stopping the Gradle daemons and trying again.")
        retry = True
    elif code != 0 and GRADLEW.exists() and any(DAEMON_CRASH_RE.search(l) for l in lines):
        say(yellow("!"), "The Gradle daemon ran out of memory. Stopping idle Gradle daemons"
            " to free memory and trying again.")
        retry = True
    else:
        retry = False
    if retry:
        run([str(GRADLEW), "--stop"], "Stopping Gradle daemons", log)
        started = time.time()
        code, lines = run(cmd, label, log)
    if code != 0:
        show_failure(label, code, lines, time.time() - started)
        raise BuildError(label)


# --- Building --------------------------------------------------------------

def build_app(result, number, log):
    started = time.time()
    try:
        if APK_OUTPUT.exists():
            APK_OUTPUT.unlink()
        run_step([str(GRADLEW), "assembleRelease", f"-PappFolder={result.app}",
                  f"-PbuildNumber={number}", "--console=plain"], f"Building {result.app}", log)
        if not APK_OUTPUT.exists():
            say(red("✗"), f"Gradle finished but {rel(APK_OUTPUT)} is missing.")
            raise BuildError("missing APK")
    except BuildError:
        result.status = "failed"
        return
    finally:
        result.seconds = time.time() - started

    DIST_DIR.mkdir(exist_ok=True)
    result.apk = DIST_DIR / f"{result.app}-v{result.version}-build{number}.apk"
    shutil.copy2(APK_OUTPUT, result.apk)
    result.status, result.size = "ok", size_of(result.apk)
    say(green("✓"), f"Built in {duration(result.seconds)}")
    print(f"  {cyan('→')} {rel(result.apk)}  " + dim(f"({result.size})"))


# --- Screens ---------------------------------------------------------------

def banner():
    print()
    if columns() > max(map(len, LOGO)) + 4:
        for line in LOGO:
            print("   " + cyan(line))
        print()
    box([(APP_TITLE, bold), ("Build release APKs for the apps in apps/", dim)])


def plan(results, number, java, sdk):
    rows = [f"{'Apps' if i == 0 else '':<10}{i + 1}. {r.app}  ({r.name} v{r.version})"
            for i, r in enumerate(results)]
    rows += [f"Build     {number}",
             f"Output    {rel(DIST_DIR)}{os.sep}",
             f"Signing   {'release key (key.properties)' if KEY_PROPERTIES.is_file() else 'debug key'}",
             f"Java      {java}",
             f"SDK       {sdk}"]
    print()
    box(rows, "Build plan")


def plan_warnings(results, number, last):
    warnings = []
    if not KEY_PROPERTIES.is_file():
        warnings.append("key.properties is missing: the APKs get the debug key and"
                        " won't install as an update over an APK from GitHub Releases"
                        " (uninstall it first).")
    if last and number <= last:
        warnings.append(f"Build number {number} is not higher than the last local build"
                        f" {last}: phones refuse it as an update over that build.")
    for path in {ROOT.anchor, Path.home().anchor}:
        free = free_gb(path)
        if free is not None and free < MIN_FREE_GB:
            warnings.append(f"Only {free:.1f} GB free on {path} - the build needs about"
                            f" {MIN_FREE_GB} GB and will probably fail.")
    existing = [r.app for r in results
                if (DIST_DIR / f"{r.app}-v{r.version}-build{number}.apk").exists()]
    if existing:
        warnings.append(f"dist/ already has build {number} of {', '.join(existing)}:"
                        " it will be replaced.")
    return warnings


def summary(results, seconds, log_path):
    head = ["App", "Status", "Time", "APK", "Size"]
    status_text = {"ok": "✓ OK", "failed": "✗ Failed", "skipped": "- Skipped"}
    status_style = {"ok": green, "failed": red, "skipped": dim}
    rows = [[r.app, status_text[r.status], duration(r.seconds) if r.seconds else "-",
             r.apk.name if r.apk else "-", r.size or "-"] for r in results]
    widths = [max(len(row[i]) for row in [head, *rows]) for i in range(len(head))]

    print()
    rule("Summary", "═", center=True)
    print("  " + dim("   ".join(h.ljust(w) for h, w in zip(head, widths)).rstrip()))
    for r, row in zip(results, rows):
        cells = [cell.ljust(w) for cell, w in zip(row, widths)]
        cells[1] = status_style[r.status](cells[1])
        print("  " + "   ".join(cells).rstrip())

    ok = sum(r.status == "ok" for r in results)
    if ok == len(results):
        result = green(bold("Done")) + f" in {duration(seconds)}"
    else:
        result = red(bold(f"{len(results) - ok} of {len(results)} failed")) + \
            f" after {duration(seconds)}"
    print("\n  " + " · ".join([result, f"full log: {rel(log_path)}"]))


# --- Main ------------------------------------------------------------------

def main():
    global COLOR
    parser = argparse.ArgumentParser(description="Build APKs for the apps in apps/.")
    parser.add_argument("apps", nargs="*", help="Folder names, numbers or 'all'. Empty: menu.")
    parser.add_argument("--build-number", type=int,
                        help=f"versionCode (default: last local build + 1, at least {FIRST_BUILD_NUMBER})")
    args = parser.parse_args()

    COLOR = use_colors()
    interactive = not args.apps
    banner()

    apps = available_apps()
    if not apps:
        say(red("✗"), f"No apps found in {rel(APPS_DIR)}{os.sep}.")
        return 1
    java = find_java()
    if not java:
        say(red("✗"), "No Java 17 or newer found. Install Android Studio (it brings its own"
            " Java) or a JDK such as https://adoptium.net/, or set JAVA_HOME.")
        return 1
    sdk = find_android_sdk()
    if not sdk:
        say(red("✗"), "Android SDK not found. Install Android Studio, or set ANDROID_HOME,"
            " or put sdk.dir=<path> in local.properties.")
        return 1

    if interactive:
        chosen = choose_apps(apps)
        if not chosen:
            print("  Cancelled.")
            return 1
    else:
        try:
            chosen = parse_selection(" ".join(args.apps), apps)
        except ValueError as e:
            say(red("✗"), f"{e} Available: {', '.join(apps)}")
            return 1

    last = last_build_number()
    if args.build_number:
        number = args.build_number
    elif interactive:
        number = choose_build_number(last)
    else:
        number = next_build_number(last)

    results = []
    for app in chosen:
        cfg = read_config(app)
        results.append(Result(app, cfg.get("appName", app), cfg.get("versionName", "1.0.0")))

    plan(results, number, java, sdk)
    for warning in plan_warnings(results, number, last):
        say(yellow("!"), warning)
    if interactive and not confirm("Start the build?"):
        print("  Cancelled.")
        return 1

    LOG_DIR.mkdir(parents=True, exist_ok=True)
    log_path = LOG_DIR / f"build-{time.strftime('%Y%m%d-%H%M%S')}.log"
    started = time.time()
    with open(log_path, "w", encoding="utf-8", newline="") as log:
        log.write(f"{APP_TITLE}, build {number},"
                  f" {time.strftime('%Y-%m-%d %H:%M:%S')}\n")
        log.write(f"Apps: {', '.join(chosen)}\n{java}\nSDK: {sdk}\n\n")
        for i, result in enumerate(results, 1):
            print()
            rule(f"[{i}/{len(results)}] {result.app} · {result.name} v{result.version}")
            build_app(result, number, log)

    if any(r.status == "ok" for r in results):
        BUILD_NUMBER_FILE.write_text(str(number))
    summary(results, time.time() - started, log_path)

    if interactive and sys.platform == "win32" and any(r.status == "ok" for r in results):
        print()
        if confirm("Open the dist folder?"):
            os.startfile(DIST_DIR)
    return 0 if all(r.status == "ok" for r in results) else 1


if __name__ == "__main__":
    # Never crash on a symbol the console can't show.
    sys.stdout.reconfigure(errors="replace")
    try:
        sys.exit(main())
    except (KeyboardInterrupt, EOFError):
        sys.stdout.write("\x1b[?25h")
        print("\n  Cancelled.")
        sys.exit(1)
