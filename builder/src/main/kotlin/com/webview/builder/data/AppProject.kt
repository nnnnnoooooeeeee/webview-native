package com.webview.builder.data

import org.json.JSONObject

//========== APP PROJECT ==========

/** One app the builder can turn into an APK: the same fields as apps/<folder>/config.json. */
data class AppProject(
    val folder: String,
    val appName: String,
    val appUrl: String,
    val applicationId: String,
    val versionName: String,
    val iconBackground: String = "#FFFFFF",
    val pullToRefresh: Boolean = true,
) {
    fun toJson(): String = JSONObject().apply {
        put("appName", appName)
        put("appUrl", appUrl)
        put("applicationId", applicationId)
        put("versionName", versionName)
        put("iconBackground", iconBackground)
        put("pullToRefresh", pullToRefresh)
    }.toString(2)

    /** Short text for the round avatar: first letter of each word, numbers kept whole. */
    val initials: String
        get() = appName.split(Regex("\\s+")).filter { it.isNotEmpty() }
            .joinToString("") { if (it.all(Char::isDigit)) it else it.take(1) }
            .take(3).uppercase().ifEmpty { "?" }

    companion object {
        fun fromJson(folder: String, text: String): AppProject {
            val json = JSONObject(text)
            return AppProject(
                folder = folder,
                appName = json.optString("appName"),
                appUrl = json.optString("appUrl"),
                applicationId = json.optString("applicationId"),
                versionName = json.optString("versionName", "1.0.0"),
                iconBackground = json.optString("iconBackground", "#FFFFFF"),
                pullToRefresh = json.optBoolean("pullToRefresh", true),
            )
        }
    }
}

//========== VALIDATION ==========
// The same rules as app/build.gradle.kts, so a project made here also builds on a computer.

object Validation {
    private val APP_ID = Regex("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+$")
    private val URL = Regex("^https?://\\S+$", RegexOption.IGNORE_CASE)
    private val VERSION = Regex("^\\d+(\\.\\d+){0,3}$")
    private val COLOR = Regex("^#[0-9a-fA-F]{6}$")
    private val FOLDER = Regex("^[a-zA-Z0-9][a-zA-Z0-9._-]*$")

    fun isAppId(value: String) = APP_ID.matches(value)
    fun isUrl(value: String) = URL.matches(value)
    fun isVersion(value: String) = VERSION.matches(value)
    fun isColor(value: String) = COLOR.matches(value)
    fun isFolder(value: String) = FOLDER.matches(value)

    /** Folder name from an app name: lower case, every run of other characters becomes one "-". */
    fun slug(name: String) = name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')

    /** A free application ID for a new app, e.g. "My Shop" -> com.example.myshop. */
    fun suggestAppId(name: String) = "com.example." + (slug(name).replace("-", "").ifEmpty { "app" })
        .let { if (it.first().isDigit()) "app$it" else it }

    /** "1.2.3" -> "1.2.4" (part = 2), "1.3.0" (part = 1) or "2.0.0" (part = 0). */
    fun bump(version: String, part: Int): String {
        val parts = version.split(".").map { it.toIntOrNull() ?: 0 }.toMutableList()
        while (parts.size < 3) parts.add(0)
        parts[part] += 1
        for (i in part + 1 until parts.size) parts[i] = 0
        return parts.joinToString(".")
    }
}
