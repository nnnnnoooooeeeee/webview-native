# WebView APK Builder

Turn a website into an Android app. Give it a name, a URL and an icon, and get
an installable APK that opens the site full screen in the phone's own WebView.

There are three ways to build:

| Tool | Runs on | Needs |
|---|---|---|
| **Desktop window** (`WebView APK Builder.exe`) | Windows (also macOS/Linux from source) | Java 17 and the Android SDK: the window installs both for you |
| **Android builder app** (`WebView-APK-Builder.apk`) | Any Android 8.0+ phone | Nothing: builds on the phone, offline, no account |
| **Terminal / GitHub Actions** | Any computer, or GitHub's servers | Java 17 + Android SDK, or just a GitHub repository |

Every generated app is tiny (about 0.1 MB), runs on Android 7.0 and newer on any
CPU, and can be installed next to the others because each one has its own
application ID.

## Downloads

Ready-made builds of the desktop window and the Android builder app are on the
[Releases](../../releases) page:

- `WebView-APK-Builder-<version>-windows.exe`: double-click it. On first start it
  creates the folder `Documents\WebView APK Builder` (your apps, builds and key)
  and offers to install Java and the Android SDK (about 1 GB, once).
- `WebView-APK-Builder-<version>.apk`: install it on the phone. Allow "install
  unknown apps" for your browser or file manager when Android asks.

## What the generated apps can do

- Pull down at the top of the page to reload, like in Chrome: an arrow appears
  and turns as you pull; past the trigger point it is armed, and dragging back
  up before you let go cancels the reload. Inner scroll areas of the page are
  respected (the pull only starts when they are at the top too).
- Progress bar while loading, and an offline/error page with a Try again button
- Android back button goes to the previous page, with Android 13+ predictive back
- File uploads from `<input type="file">`, honouring `accept=""` and `multiple`
- `tel:`, `mailto:`, WhatsApp, `intent:` and other links open the matching app
- Media can autoplay; pinch-to-zoom where the page allows it
- `http://` URLs work too (for servers on a local network)
- Logins survive app restarts (cookies are saved when the app goes to the background)
- If the page's renderer crashes, the app reloads instead of closing
- English and Indonesian texts, following the phone's language

## Project structure

```
apps/
  <folder>/
    config.json            <- name, URL, application ID, version (see below)
    icon.png               <- app icon, square PNG (1024x1024 recommended)
app/                       <- the WebView app every APK is built from (Kotlin, no libraries)
  build.gradle.kts         <- reads apps/<folder>/, generates icons and assets/webview.json
  template/icon.png        <- placeholder icon of the template APK
builder/                   <- the Android builder app (Kotlin, Jetpack Compose)
tool/
  gui.py                   <- the desktop window (pywebview); screens in tool/web/
  build_apk.py             <- terminal builder, also used by the window
  workspace.py             <- workspace unpacking and Java/SDK setup for the EXE
  build_exe.py             <- packs the window into one EXE (PyInstaller)
build-apk.bat              <- Windows launcher: opens the window
.github/workflows/
  build-apk.yml            <- builds apps from apps/ on GitHub (manual)
  release.yml              <- builds the EXE and the builder APK, publishes a release
```

Building never changes files in the repository: Gradle reads `apps/<folder>/`
at build time (`-PappFolder=<folder>`) and writes everything it generates
under `app/build/`.

## App settings: `config.json`

```json
{
  "appName": "Google",
  "appUrl": "https://google.com",
  "applicationId": "com.example.google",
  "versionName": "1.0.0",
  "iconBackground": "#FFFFFF",
  "pullToRefresh": true
}
```

| Field | Description |
|---|---|
| `appName` | Name shown below the icon. |
| `appUrl` | Website to open (`http://` or `https://`). |
| `applicationId` | **Must be unique per app** and look like `com.company.app`. Android uses it to tell apps apart, so two apps with the same ID replace each other. Do not change it after you have given the app to people. |
| `versionName` | Version shown to users, e.g. `1.0.0`. The build number (`versionCode`) is chosen at build time, see [Build numbers](#build-numbers). |
| `iconBackground` | (optional) Background color of the adaptive icon as `#RRGGBB`. Default `#FFFFFF`. |
| `pullToRefresh` | (optional) `false` turns pull-to-refresh off. Default `true`. |

The build stops with a clear message if a field is missing or malformed. The
desktop window and the Android app check the same rules while you type.

## Adding an app

- **Desktop window or Android app**: tap **Add app**, fill in the form and pick
  an icon. The folder name and application ID are suggested from the app name.
- **By hand**: copy `apps/google` to `apps/<new-folder>`, replace `icon.png`
  and edit `config.json` (especially a **different** `applicationId`).

## Building on a computer

### Desktop window

Double-click **`build-apk.bat`** (or run the EXE). The first time it installs
the window component (`pip install pywebview`). Then:

1. If Java or the Android SDK is missing, **Install for me** downloads Eclipse
   Temurin 17 and the Android command-line tools into `tools/`.
2. Tick the apps to build, check the build number, press **Build**.
3. Confirm the build plan (signing key, Java, SDK and any warnings).
4. Watch each app's Gradle steps; at the end open the output folder or the log.

Apps are edited with the pencil button and deleted from the edit screen (the
folder goes to the Recycle Bin, so it can be restored).

From source on any system: `pip install -r tool/requirements.txt`, then
`python tool/gui.py`.

### Terminal

```bash
python tool/build_apk.py                        # menu: arrows + space to pick apps
python tool/build_apk.py google my-shop         # these apps, no questions
python tool/build_apk.py all --build-number 12  # every app with build number 12
```

`build-apk.bat google` does the same on Windows.

Output (both window and terminal):

- APKs: `dist/<folder>-v<version>-build<number>.apk`
- Full Gradle log: `dist/logs/build-<timestamp>.log`
- Last used build number: `dist/.last_build_number`

### Gradle directly

```bash
./gradlew assembleRelease -PappFolder=google -PbuildNumber=5
# output: app/build/outputs/apk/release/app-release.apk
```

In Android Studio, open the project and press Run: it builds the app named by
`appFolder` in `gradle.properties`.

Requirements for computer builds: Java 17 or newer (a JDK on `PATH`/`JAVA_HOME`,
Android Studio's bundled Java, or `tools/jdk`), the Android SDK (found
automatically, written to `local.properties`), Python 3 for the window and the
terminal builder, and about 1 GB of disk space for the Gradle caches.

## Building on the phone (Android builder app)

The builder app makes APKs without Gradle, a computer or an internet
connection:

1. **Apps** tab: add or edit apps (long-press or the pencil button), tick the
   ones to build and tap **Build**.
2. The progress screen shows each step; then tap **Install** or **Share**.
3. **Builds** tab: every APK built so far, with Install, Share, Save copy and Delete.
4. **Key** tab: the signing key (see [Signing](#signing)).
5. **Settings** tab: theme (System, Light, Dark), **AMOLED black** for pure black
   dark mode, and wallpaper colors on Android 12+.

How it works: the builder app contains the WebView app pre-built with
placeholder values (the `template` build type of `app/`). For each app it
rewrites the package name, app name and version in the binary
`AndroidManifest.xml`, replaces `assets/webview.json` (URL and options) and
the launcher icon PNGs, and signs the result with APK Signature Scheme v2 and
v3 using [apksig](https://android.googlesource.com/platform/tools/apksig).
The code inside is identical to a computer build of the same version.

## Building with GitHub Actions

Workflow: [.github/workflows/build-apk.yml](.github/workflows/build-apk.yml)

*Actions* tab -> *Build APK* -> *Run workflow*, then enter an app folder name
(e.g. `google`) or `all`, and optionally a build number. Each app builds in
parallel; the APKs are uploaded as workflow artifacts and published as
**GitHub Releases** tagged `<folder>-v<version>-build<number>`.

## Build numbers

Android only installs an update when its `versionCode` (build number) is higher
than the installed one.

- Desktop window and terminal: their own counter (`dist/.last_build_number`),
  suggested as last build + 1. You can change it before each build.
- Android builder app: its own counter, shown as a chip on the Apps tab (tap to change).
- GitHub Actions: the workflow run number plus the repository variable
  `BUILD_NUMBER_OFFSET` (default 0), or the number you enter.

The counters are independent. If phones get APKs from more than one place,
pick a build number higher than any build they already have.

## Signing

Android only updates an app when the new APK is signed with the **same key**
as the installed one. Use one release key for all your apps and keep a backup
of it and its password: without it, people have to uninstall the app before
they can install an update (and lose its data).

Without a release key, computer builds use the computer's debug key and the
Android app creates an automatic key on the first build. Both work for
testing, but each computer or phone signs differently.

**Desktop window**, *Signing* screen:

- *Use an existing keystore*: choose a `.jks`, `.keystore` or `.p12` file and
  enter its passwords. The window checks them and writes `key.properties`.
- *Create a new key*: alias, password, name and where to save it. The window
  creates the keystore with `keytool` and writes `key.properties`.
- *Copy GitHub secrets*: the four values for GitHub Actions (below).

**Android app**, *Key* tab: create a new key, import a `.jks` or `.p12` file,
export a backup (`.p12`) and show its password. The key's password is stored
encrypted with the phone's Android Keystore.

**By hand**: create a keystore once,

```bash
keytool -genkeypair -v -keystore upload-keystore.jks -storetype PKCS12 -keyalg RSA -keysize 2048 -validity 10000 -alias upload
```

and create `key.properties` in the project root (a relative `storeFile` is
relative to the project root):

```
storeFile=C:/path/to/upload-keystore.jks
storePassword=...
keyAlias=upload
keyPassword=...
```

**GitHub Actions**: add these repository secrets under *Settings -> Secrets and
variables -> Actions*:

| Secret | Value |
|---|---|
| `KEYSTORE_BASE64` | Keystore file in base64 (PowerShell: `[Convert]::ToBase64String([IO.File]::ReadAllBytes("upload-keystore.jks"))`) |
| `KEYSTORE_PASSWORD` | Keystore password |
| `KEY_ALIAS` | Key alias, e.g. `upload` |
| `KEY_PASSWORD` | Key password (can stay empty when it equals the keystore password) |

`key.properties`, `*.jks` and `*.keystore` are in `.gitignore`: never commit them.

To sign apps from the Android app and from a computer with the same key,
export it from one (Android: *Export backup*; computer: the keystore file) and
import it into the other.

## Releasing the builder tools

Push a tag like `v1.2.0` (or run *Actions* -> *Release* -> *Run workflow* with
the version). [release.yml](.github/workflows/release.yml) then:

1. runs the builder app's tests and builds its APK (signed with the secrets
   above when they are set),
2. builds the Windows EXE with PyInstaller on a Windows runner,
3. publishes both files as the GitHub Release `v1.2.0`.

## Developing

```bash
./gradlew assembleRelease                 # WebView app for appFolder in gradle.properties
./gradlew :builder:assembleDebug          # Android builder app (also builds the template APK)
./gradlew :builder:testDebugUnitTest      # manifest patching, signing and JKS import tests
python tool/gui.py                        # desktop window from source
python tool/build_exe.py 1.2.0            # EXE into tool/dist (run on Windows for a Windows EXE)
```

Set `WEBVIEW_BUILDER_DEBUG=1` to open the window with developer tools.

Notes:

- The template's placeholder values (`templateAppName`, `templateApplicationId`,
  ...) are in `gradle.properties`; the builder app reads the same ones.
- `builder/build.gradle.kts` finds the icon files inside the template APK by
  comparing hashes with the generated icons, because the build shortens
  resource paths. That is why the template build type keeps PNGs uncrunched.
- The pull-to-refresh view is `app/src/main/kotlin/.../PullToRefreshLayout.kt`.
