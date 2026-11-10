package com.webview.webview_app

import android.content.Context
import org.json.JSONObject

//========== APP CONFIG ==========

/**
 * The per-app settings, read at runtime from assets/webview.json.
 *
 * Gradle writes that file from apps/<app>/config.json. The on-device builder
 * replaces it inside a ready-made APK, which is why these values live in an
 * asset instead of being compiled into the code.
 */
data class AppConfig(
    val url: String,
    val pullToRefresh: Boolean,
) {
    companion object {
        private const val FILE = "webview.json"

        fun load(context: Context): AppConfig {
            val json = context.assets.open(FILE).bufferedReader().use { JSONObject(it.readText()) }
            return AppConfig(
                url = json.getString("url"),
                pullToRefresh = json.optBoolean("pullToRefresh", true),
            )
        }
    }
}
