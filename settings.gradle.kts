pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "webview-apk-builder"

// app/     the WebView app every generated APK is made from.
// builder/ the Android builder app. Optional: the desktop builder ships the
//          project without it, so it only needs the WebView app's dependencies.
include(":app")
if (file("builder").isDirectory) include(":builder")
