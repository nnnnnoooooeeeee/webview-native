# Development guide

Technical notes for building WebView APK Builder from source, using it from
the terminal and working on the code. For everyday use, see the
[README](README.md).

## Project structure

```
apps/
  <folder>/
    config.json            <- name, URL, application ID, version
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

## Requirements for computer builds

- Java 17 or newer: a JDK on `PATH`/`JAVA_HOME`, Android Studio's bundled
  Java, or `tools/jdk` (the desktop window's **Install for me** puts Eclipse
  Temurin 17 there)
- The Android SDK: found automatically and written to `local.properties`
  (or installed into `tools/` by the window)
- Python 3 for the window and the terminal builder
- About 1 GB of disk space for the Gradle caches

## Desktop window from source

Double-click **`build-apk.bat`**. The first time it installs the window
component (`pip install pywebview`).

On any system: `pip install -r tool/requirements.txt`, then
`python tool/gui.py`.

Set `WEBVIEW_BUILDER_DEBUG=1` to open the window with developer tools.

## Terminal builder

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

## Gradle directly

```bash
./gradlew assembleRelease -PappFolder=google -PbuildNumber=5
# output: app/build/outputs/apk/release/app-release.apk
```

In Android Studio, open the project and press Run: it builds the app named by
`appFolder` in `gradle.properties`.

## Build numbers

Android only installs an update when its `versionCode` (build number) is higher
than the installed one. Each tool keeps its own counter:

- Desktop window and terminal: `dist/.last_build_number`, suggested as last
  build + 1.
- Android builder app: its own counter, shown as a chip on the Apps tab.
- GitHub Actions: the workflow run number plus the repository variable
  `BUILD_NUMBER_OFFSET` (default 0), or the number entered when running the
  workflow.

## Signing by hand

Without a release key, computer builds use the computer's debug key and the
Android app creates an automatic key on the first build.

Create a keystore once:

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

`key.properties`, `*.jks` and `*.keystore` are in `.gitignore`: never commit them.

The Android app stores its key's password encrypted with the phone's Android
Keystore.

## GitHub Actions build

Workflow: [.github/workflows/build-apk.yml](.github/workflows/build-apk.yml).
Each app builds in parallel; the APKs are uploaded as workflow artifacts and
published as GitHub Releases tagged `<folder>-v<version>-build<number>`.

## How the phone builder works

The builder app contains the WebView app pre-built with placeholder values
(the `template` build type of `app/`). For each app it rewrites the package
name, app name and version in the binary `AndroidManifest.xml`, replaces
`assets/webview.json` (URL and options) and the launcher icon PNGs, and signs
the result with APK Signature Scheme v2 and v3 using
[apksig](https://android.googlesource.com/platform/tools/apksig). The code
inside is identical to a computer build of the same version.

## Generated app details

- Pull to refresh: an arrow appears and turns as you pull; past the trigger
  point it is armed, and dragging back up before letting go cancels the
  reload. Inner scroll areas are respected (the pull only starts when they
  are at the top too). Implemented in
  `app/src/main/kotlin/.../PullToRefreshLayout.kt`.
- Android 13+ predictive back.
- `<input type="file">` honours `accept=""` and `multiple`.
- `tel:`, `mailto:`, WhatsApp, `intent:` and other links open the matching app.
- `http://` URLs work (for servers on a local network).
- Cookies are flushed when the app goes to the background.
- If the page's renderer crashes, the app reloads instead of closing.
- `minSdk` 24 (Android 7.0), any CPU architecture.

## Developing

```bash
./gradlew assembleRelease                 # WebView app for appFolder in gradle.properties
./gradlew :builder:assembleDebug          # Android builder app (also builds the template APK)
./gradlew :builder:testDebugUnitTest      # manifest patching, signing and JKS import tests
python tool/gui.py                        # desktop window from source
python tool/build_exe.py 1.2.0            # EXE into tool/dist (run on Windows for a Windows EXE)
```

Notes:

- The template's placeholder values (`templateAppName`, `templateApplicationId`,
  ...) are in `gradle.properties`; the builder app reads the same ones.
- `builder/build.gradle.kts` finds the icon files inside the template APK by
  comparing hashes with the generated icons, because the build shortens
  resource paths. That is why the template build type keeps PNGs uncrunched.

## Releasing the builder tools

Push a tag like `v1.2.0` (or run *Actions* -> *Release* -> *Run workflow* with
the version). [release.yml](.github/workflows/release.yml) then:

1. runs the builder app's tests and builds its APK (signed with the GitHub
   secrets when they are set),
2. builds the Windows EXE with PyInstaller on a Windows runner,
3. publishes both files as the GitHub Release `v1.2.0`.
