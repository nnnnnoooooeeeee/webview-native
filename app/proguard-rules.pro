# The page calls the pull-to-refresh bridge (MainActivity.ScrollBridge) from
# JavaScript, so its @JavascriptInterface methods must keep their names.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
