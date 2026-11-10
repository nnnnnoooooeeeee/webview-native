package com.webview.builder.build

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.webview.builder.data.AppProject
import java.io.File

//========== BUILD JOB ==========

enum class JobStatus { WAITING, RUNNING, DONE, FAILED, CANCELLED }

/** The steps of one app's build, in order (the progress bar uses their position). */
enum class BuildStep(val label: String) {
    TEMPLATE("Reading the template"),
    MANIFEST("Setting name, package and version"),
    CONFIG("Writing the website settings"),
    ICONS("Drawing the icons"),
    PACKAGE("Packing the APK"),
    SIGN("Signing"),
}

/** One app in a build run. Its fields are Compose state, so the progress screen follows along. */
class BuildJob(val project: AppProject) {
    var status by mutableStateOf(JobStatus.WAITING)
        internal set
    var step by mutableStateOf<BuildStep?>(null)
        internal set
    /** How long the build took, once done. */
    var seconds by mutableDoubleStateOf(0.0)
        internal set
    var output by mutableStateOf<File?>(null)
        internal set
    var error by mutableStateOf<String?>(null)
        internal set
}

//========== BUILT APK ==========

/** An APK in the output folder, shown on the Builds tab. */
data class BuiltApk(val file: File) {
    val name: String get() = file.name

    val sizeText: String
        get() {
            val bytes = file.length()
            return if (bytes >= 1024 * 1024) "%.1f MB".format(bytes / 1048576.0) else "%d KB".format((bytes + 1023) / 1024)
        }
}
