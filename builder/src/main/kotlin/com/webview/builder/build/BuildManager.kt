package com.webview.builder.build

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import com.webview.builder.data.AppProject
import com.webview.builder.data.ProjectStore
import com.webview.builder.data.Settings
import com.webview.builder.data.Validation
import com.webview.builder.sign.SigningKey
import com.webview.builder.sign.SigningKeys
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.concurrent.thread

//========== BUILD MANAGER ==========

/**
 * Turns apps into APKs from the template APK in the assets, one after the
 * other on a background thread, and manages the output folder.
 *
 * Lives in [com.webview.builder.BuilderApp], so a build keeps running (and its
 * progress stays visible) when the screen rotates or the user switches tabs.
 */
class BuildManager(
    private val context: Context,
    private val projects: ProjectStore,
    private val keys: SigningKeys,
    private val settings: Settings,
) {
    private val main = Handler(Looper.getMainLooper())
    private val outputDir = File(context.filesDir, "output")
    private val workDir = File(context.cacheDir, "build")

    var running by mutableStateOf(false)
        private set

    /** The versionCode of the current (or last) run. */
    var buildNumber by mutableIntStateOf(settings.nextBuildNumber)
        private set

    var jobs by mutableStateOf<List<BuildJob>>(emptyList())
        private set

    /** The APKs in the output folder, newest first. */
    var outputs by mutableStateOf<List<BuiltApk>>(emptyList())
        private set

    @Volatile
    private var cancelled = false

    //========== RUNNING BUILDS ==========

    /** Builds [selected] with the next build number. Ignored while a build runs. */
    fun start(selected: List<AppProject>) {
        if (running || selected.isEmpty()) return
        val number = settings.nextBuildNumber
        val run = selected.map { BuildJob(it) }
        buildNumber = number
        jobs = run
        cancelled = false
        running = true
        thread(name = "apk-build", isDaemon = true) { buildAll(run, number) }
    }

    /** Stops after the current step; apps not started yet are skipped. */
    fun cancel() {
        cancelled = true
    }

    private fun buildAll(run: List<BuildJob>, number: Int) {
        var built = 0
        val template = runCatching { loadTemplate() }
        val key = runCatching { keys.loadOrCreateAutomatic() }
        for (job in run) {
            if (cancelled) {
                ui { job.status = JobStatus.CANCELLED }
                continue
            }
            ui { job.status = JobStatus.RUNNING }
            val started = SystemClock.elapsedRealtime()
            try {
                val apk = buildOne(job, template.getOrThrow(), key.getOrThrow(), number)
                val seconds = (SystemClock.elapsedRealtime() - started) / 1000.0
                built++
                ui {
                    job.output = apk
                    job.seconds = seconds
                    job.status = JobStatus.DONE
                    refreshOutputs()
                }
            } catch (e: CancelledException) {
                ui { job.status = JobStatus.CANCELLED }
            } catch (e: Throwable) {
                val message = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
                ui {
                    job.error = message
                    job.status = JobStatus.FAILED
                }
            }
        }
        val anyBuilt = built > 0
        ui {
            // The number is used up once an APK carries it.
            if (anyBuilt && settings.nextBuildNumber <= number) settings.updateNextBuildNumber(number + 1)
            running = false
        }
    }

    private fun buildOne(job: BuildJob, template: Template, key: SigningKey, number: Int): File {
        val p = job.project
        check(Validation.isAppId(p.applicationId)) { "Application ID must look like com.company.app" }
        check(Validation.isUrl(p.appUrl)) { "Website must be an http:// or https:// URL" }
        check(Validation.isVersion(p.versionName)) { "Version must look like 1.0.0" }
        check(Validation.isColor(p.iconBackground)) { "Icon background must be a color like #FFFFFF" }
        check(p.appName.isNotBlank()) { "App name is missing" }

        step(job, BuildStep.TEMPLATE)
        val manifest = template.manifest()

        step(job, BuildStep.MANIFEST)
        val replacements = mutableMapOf<String, ByteArray>()
        replacements[MANIFEST] = ManifestPatcher.patch(
            manifest,
            packageName = template.applicationId to p.applicationId,
            strings = mapOf(template.appName to p.appName.trim(), template.versionName to p.versionName),
            versionCode = number,
        )

        step(job, BuildStep.CONFIG)
        val config = JSONObject().put("url", p.appUrl.trim()).put("pullToRefresh", p.pullToRefresh)
        replacements[template.configAsset] = (config.toString(2) + "\n").toByteArray()

        step(job, BuildStep.ICONS)
        val source = BitmapFactory.decodeFile(projects.iconFile(p.folder).path)
            ?: projects.defaultIcon().let { BitmapFactory.decodeByteArray(it, 0, it.size) }
            ?: error("The icon is not a valid image")
        val background = Color.parseColor(p.iconBackground)
        try {
            for (icon in template.icons) replacements[icon.entry] = renderIcon(source, icon, background)
        } finally {
            source.recycle()
        }

        step(job, BuildStep.PACKAGE)
        workDir.mkdirs()
        val unsigned = File(workDir, "${p.folder}-unsigned.apk")
        val signed = File(workDir, "${p.folder}-signed.apk")
        try {
            ApkWriter.repack(template.apk, unsigned, replacements)

            step(job, BuildStep.SIGN)
            ApkWriter.sign(unsigned, signed, key.privateKey, key.chain)
            checkCancelled()

            outputDir.mkdirs()
            val output = File(outputDir, "${p.folder}-v${p.versionName}-build$number.apk")
            output.delete()
            if (!signed.renameTo(output)) {
                signed.copyTo(output, overwrite = true)
            }
            return output
        } finally {
            unsigned.delete()
            signed.delete()
        }
    }

    private fun step(job: BuildJob, step: BuildStep) {
        checkCancelled()
        ui { job.step = step }
    }

    private fun checkCancelled() {
        if (cancelled) throw CancelledException()
    }

    private class CancelledException : Exception("Cancelled")

    private fun ui(block: () -> Unit) {
        main.post(block)
    }

    //========== ICONS ==========
    // The same images as app/build.gradle.kts makes on a computer: the icon
    // scaled to the file's size, and the adaptive icon background as a solid color.

    private fun renderIcon(source: Bitmap, icon: TemplateIcon, background: Int): ByteArray {
        val image = when (icon.kind) {
            "background" -> Bitmap.createBitmap(icon.size, icon.size, Bitmap.Config.ARGB_8888).apply { eraseColor(background) }
            "legacy", "foreground" -> resize(source, icon.size)
            else -> error("Unknown icon kind ${icon.kind}")
        }
        return ByteArrayOutputStream().use { out ->
            image.compress(Bitmap.CompressFormat.PNG, 100, out)
            if (image !== source) image.recycle()
            out.toByteArray()
        }
    }

    /** Halves the image step by step before the final resize, for a sharp, smooth result. */
    private fun resize(source: Bitmap, size: Int): Bitmap {
        var image = source
        while (image.width / 2 >= size && image.height / 2 >= size) {
            val half = Bitmap.createScaledBitmap(image, image.width / 2, image.height / 2, true)
            if (image !== source) image.recycle()
            image = half
        }
        val result = Bitmap.createScaledBitmap(image, size, size, true)
        if (image !== source && image !== result) image.recycle()
        return result
    }

    //========== TEMPLATE ==========

    private class TemplateIcon(val entry: String, val kind: String, val size: Int)

    private class Template(
        val apk: File,
        val appName: String,
        val applicationId: String,
        val versionName: String,
        val configAsset: String,
        val icons: List<TemplateIcon>,
    ) {
        fun manifest(): ByteArray = java.util.zip.ZipFile(apk).use { zip ->
            val entry = zip.getEntry(MANIFEST) ?: error("Template APK has no $MANIFEST")
            zip.getInputStream(entry).use { it.readBytes() }
        }
    }

    /** Copies the template APK out of the assets (ZipFile needs a real file) and reads template.json. */
    private fun loadTemplate(): Template {
        workDir.mkdirs()
        val apk = File(workDir, "template.apk")
        context.assets.open("$TEMPLATE_DIR/template.apk").use { input -> apk.outputStream().use { input.copyTo(it) } }
        val json = context.assets.open("$TEMPLATE_DIR/template.json").bufferedReader().use { JSONObject(it.readText()) }
        val icons = json.getJSONArray("icons").let { array ->
            List(array.length()) { i ->
                val icon = array.getJSONObject(i)
                TemplateIcon(icon.getString("entry"), icon.getString("kind"), icon.getInt("size"))
            }
        }
        return Template(
            apk = apk,
            appName = json.getString("appName"),
            applicationId = json.getString("applicationId"),
            versionName = json.getString("versionName"),
            configAsset = json.getString("configAsset"),
            icons = icons,
        )
    }

    //========== OUTPUT FOLDER ==========

    fun refreshOutputs() {
        outputs = outputDir.listFiles { f -> f.isFile && f.name.endsWith(".apk") }.orEmpty()
            .sortedWith(compareByDescending<File> { it.lastModified() }.thenBy { it.name })
            .map { BuiltApk(it) }
    }

    fun delete(apk: BuiltApk) {
        apk.file.delete()
        refreshOutputs()
    }

    /** Deletes the APKs built from the app in [folder] (named <folder>-v<version>-build<n>.apk). */
    fun deleteOutputsOf(folder: String) {
        val pattern = Regex("^" + Regex.escape(folder) + "-v[0-9.]+-build\\d+\\.apk$")
        outputDir.listFiles { f -> pattern.matches(f.name) }.orEmpty().forEach { it.delete() }
        refreshOutputs()
    }

    /** Writes a copy of [file] to [uri] (picked with the system's save dialog). */
    fun saveCopy(file: File, uri: Uri) {
        context.contentResolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
    }

    //========== SHARE / INSTALL ==========

    private fun uriOf(file: File): Uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)

    /** A share sheet for one or more APKs. */
    fun shareIntent(files: List<File>): Intent {
        val uris = files.map(::uriOf)
        val send = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris.first())
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        }
        send.type = APK_TYPE
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        if (uris.isNotEmpty()) {
            send.clipData = ClipData.newRawUri(null, uris.first()).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
        }
        return Intent.createChooser(send, if (files.size == 1) "Share APK" else "Share APKs")
    }

    /**
     * Opens the system installer for [file]. When this app may not install apps
     * yet, returns the settings screen to allow it instead (the caller opens it).
     */
    fun install(file: File): Intent? {
        if (!context.packageManager.canRequestPackageInstalls()) {
            return Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${context.packageName}".toUri())
        }
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uriOf(file), APK_TYPE)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return null
    }

    companion object {
        const val APK_TYPE = "application/vnd.android.package-archive"
        private const val MANIFEST = "AndroidManifest.xml"
        private const val TEMPLATE_DIR = "template"
    }
}
