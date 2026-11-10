package com.webview.builder.data

import android.content.Context
import java.io.File

//========== PROJECT STORE ==========

/**
 * Keeps the apps in the app's private storage, in the same layout as the
 * repository: files/apps/<folder>/config.json and icon.png.
 */
class ProjectStore(private val context: Context) {

    private val root = File(context.filesDir, "apps")

    fun list(): List<AppProject> {
        if (!root.isDirectory) seedExample()
        return root.listFiles { f -> f.isDirectory }.orEmpty()
            .mapNotNull { dir -> runCatching { load(dir.name) }.getOrNull() }
            .sortedBy { it.appName.lowercase() }
    }

    fun load(folder: String): AppProject =
        AppProject.fromJson(folder, File(File(root, folder), CONFIG).readText())

    fun iconFile(folder: String) = File(File(root, folder), ICON)

    /**
     * Saves [project]. [oldFolder] is the folder it had before (null for a new app);
     * a different folder name renames it. [icon] are new icon PNG bytes, if any.
     */
    fun save(project: AppProject, oldFolder: String?, icon: ByteArray?) {
        val dir = File(root, project.folder)
        if (oldFolder != null && oldFolder != project.folder) {
            File(root, oldFolder).renameTo(dir)
        }
        dir.mkdirs()
        File(dir, CONFIG).writeText(project.toJson() + "\n")
        when {
            icon != null -> File(dir, ICON).writeBytes(icon)
            !File(dir, ICON).isFile -> File(dir, ICON).writeBytes(defaultIcon())
        }
    }

    fun delete(folder: String) {
        File(root, folder).deleteRecursively()
    }

    fun exists(folder: String) = File(root, folder).isDirectory

    /** The icon new apps start with (the template's globe). */
    fun defaultIcon(): ByteArray = context.assets.open("template/default_icon.png").use { it.readBytes() }

    /** First run: one example app, so the list is not empty. */
    private fun seedExample() {
        save(
            AppProject(
                folder = "google",
                appName = "Google",
                appUrl = "https://google.com",
                applicationId = "com.example.google",
                versionName = "1.0.0",
            ),
            oldFolder = null,
            icon = null,
        )
    }

    private companion object {
        const val CONFIG = "config.json"
        const val ICON = "icon.png"
    }
}
