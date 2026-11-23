# The page calls the JavaScript bridges (MainActivity.ScrollBridge, Downloader.Bridge) from
# JavaScript, so their @JavascriptInterface methods must keep their names.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
