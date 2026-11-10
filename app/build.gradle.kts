import groovy.json.JsonSlurper
import java.awt.Color as AwtColor
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.FileInputStream
import java.util.Properties
import javax.imageio.ImageIO
import kotlin.math.roundToInt

plugins {
    id("com.android.application")
}

//========== WHICH APP TO BUILD ==========
// Everything app-specific comes from apps/<appFolder>/: config.json (name, URL,
// applicationId, version) and icon.png. Pick the folder with -PappFolder=<folder>;
// the default is in gradle.properties.

val appsDir = rootProject.file("apps")
val availableApps = appsDir.listFiles { f -> f.isDirectory }.orEmpty().map { it.name }.sorted()
val appFolder = providers.gradleProperty("appFolder").orNull?.trim()
    ?: throw GradleException("No app selected. Use -PappFolder=<folder>. Available: ${availableApps.joinToString()}")
val appDir = File(appsDir, appFolder)
if (!appDir.isDirectory) {
    throw GradleException("App \"$appFolder\" not found in apps/. Available: ${availableApps.joinToString()}")
}
val configFile = File(appDir, "config.json")
val iconFile = File(appDir, "icon.png")
if (!configFile.isFile) throw GradleException("Missing $configFile")
if (!iconFile.isFile) throw GradleException("Missing $iconFile")

@Suppress("UNCHECKED_CAST")
val appConfig = try {
    JsonSlurper().parse(configFile, "UTF-8") as Map<String, Any?>
} catch (e: Exception) {
    throw GradleException("Invalid JSON in $configFile: ${e.message}")
}

fun requiredString(key: String): String {
    val value = appConfig[key]
    if (value !is String || value.isBlank()) throw GradleException("\"$key\" is required in $configFile")
    return value.trim()
}

val appName = requiredString("appName")
val appUrl = requiredString("appUrl")
val appId = requiredString("applicationId")
val appVersion = requiredString("versionName")
val iconBackground = (appConfig["iconBackground"] as? String)?.trim() ?: "#FFFFFF"
val pullToRefreshEnabled = when (val value = appConfig["pullToRefresh"]) {
    null -> true
    is Boolean -> value
    else -> throw GradleException("\"pullToRefresh\" must be true or false in $configFile")
}

if (!Regex("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+$").matches(appId)) {
    throw GradleException("\"applicationId\" must look like com.company.app (got \"$appId\") in $configFile")
}
if (!Regex("^https?://\\S+$", RegexOption.IGNORE_CASE).matches(appUrl)) {
    throw GradleException("\"appUrl\" must be an http:// or https:// URL in $configFile")
}
if (!Regex("^#[0-9a-fA-F]{6}$").matches(iconBackground)) {
    throw GradleException("\"iconBackground\" must be a hex color like #FFFFFF in $configFile")
}

// versionCode: -PbuildNumber=<n> (build-apk.bat and GitHub Actions always pass one).
val buildNumber = providers.gradleProperty("buildNumber").orNull?.let {
    it.toIntOrNull()?.takeIf { n -> n > 0 } ?: throw GradleException("-PbuildNumber must be a whole number above 0 (got \"$it\")")
} ?: 1

fun escapeXml(value: String) = value
    .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

//========== TEMPLATE APK ==========
// The "template" build type is a ready-made APK that the Android builder app
// (builder/) turns into any app on the phone: it rewrites these placeholder
// values in the binary manifest, replaces assets/webview.json and the icons,
// and signs the result. The values are in gradle.properties so builder/ can
// read the same ones.
val templateAppName = providers.gradleProperty("templateAppName").get()
val templateApplicationId = providers.gradleProperty("templateApplicationId").get()
val templateVersionName = providers.gradleProperty("templateVersionName").get()
val templateUrl = providers.gradleProperty("templateUrl").get()
val templateIconFile = file("template/icon.png")

//========== RELEASE SIGNING ==========
// key.properties in the project root (written by CI from secrets, or by hand).
// Without it, release builds are signed with the debug key.
val keystorePropertiesFile = rootProject.file("key.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.isFile) FileInputStream(keystorePropertiesFile).use { load(it) }
}

android {
    // namespace is the Kotlin package and stays the same for every app.
    // applicationId (below) is what makes each installed app unique.
    namespace = "com.webview.webview_app"
    compileSdk = 36

    defaultConfig {
        applicationId = appId
        minSdk = 24
        targetSdk = 36
        versionCode = buildNumber
        versionName = appVersion
        manifestPlaceholders["appName"] = escapeXml(appName)
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    signingConfigs {
        if (keystorePropertiesFile.isFile) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword") ?: keystoreProperties.getProperty("storePassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
        create("template") {
            initWith(getByName("release"))
            // Keep every resource and every PNG byte-for-byte as generated, so the
            // builder can find the icon files inside the APK (see builder/build.gradle.kts).
            isShrinkResources = false
            isCrunchPngs = false
            // Re-signed on the phone anyway.
            signingConfig = signingConfigs.getByName("debug")
            manifestPlaceholders["appName"] = escapeXml(templateAppName)
            matchingFallbacks += "release"
        }
    }

    packaging {
        resources {
            // Kotlin metadata the app never reads at runtime.
            excludes += listOf("kotlin/**", "META-INF/*.kotlin_module", "META-INF/*.version", "DebugProbesKt.bin")
        }
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}

//========== LAUNCHER ICONS ==========
// Generated from apps/<appFolder>/icon.png at build time: a legacy icon plus an
// adaptive icon (foreground with a 20% inset on a solid background). The
// background is a PNG, not a color resource, so the on-device builder can
// replace it like the other icon files.

abstract class GenerateAppIcons : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val icon: RegularFileProperty

    @get:Input
    abstract val background: Property<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        System.setProperty("java.awt.headless", "true")
        val out = outputDir.get().asFile
        out.deleteRecursively()
        val source = ImageIO.read(icon.get().asFile)
            ?: throw GradleException("${icon.get().asFile} is not a valid PNG image")
        val color = AwtColor.decode(background.get())

        // Pixel size = dp size x density scale (mdpi = 1x ... xxxhdpi = 4x).
        val densities = mapOf("mdpi" to 1.0, "hdpi" to 1.5, "xhdpi" to 2.0, "xxhdpi" to 3.0, "xxxhdpi" to 4.0)
        for ((density, scale) in densities) {
            save(resize(source, (48 * scale).roundToInt()), File(out, "mipmap-$density/ic_launcher.png"))
            val layer = (108 * scale).roundToInt() // adaptive icon layers are 108dp
            save(resize(source, layer), File(out, "drawable-$density/ic_launcher_foreground.png"))
            save(solid(color, layer), File(out, "drawable-$density/ic_launcher_background.png"))
        }
        write(File(out, "mipmap-anydpi-v26/ic_launcher.xml"), """
            <?xml version="1.0" encoding="utf-8"?>
            <adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
                <background android:drawable="@drawable/ic_launcher_background" />
                <foreground>
                    <inset android:drawable="@drawable/ic_launcher_foreground" android:inset="20%" />
                </foreground>
            </adaptive-icon>
        """.trimIndent())
    }

    /** Halves the image step by step before the final resize, for a sharp, smooth result. */
    private fun resize(source: BufferedImage, size: Int): BufferedImage {
        var image = source
        while (image.width / 2 >= size && image.height / 2 >= size) {
            image = draw(image, image.width / 2, image.height / 2)
        }
        return draw(image, size, size)
    }

    private fun draw(source: BufferedImage, width: Int, height: Int): BufferedImage {
        val target = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val g = target.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.drawImage(source, 0, 0, width, height, null)
        g.dispose()
        return target
    }

    private fun solid(color: AwtColor, size: Int): BufferedImage {
        val image = BufferedImage(size, size, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        g.color = color
        g.fillRect(0, 0, size, size)
        g.dispose()
        return image
    }

    private fun save(image: BufferedImage, file: File) {
        file.parentFile.mkdirs()
        ImageIO.write(image, "png", file)
    }

    private fun write(file: File, text: String) {
        file.parentFile.mkdirs()
        file.writeText(text + "\n")
    }
}

//========== WEB CONFIG ASSET ==========
// assets/webview.json, read by AppConfig.kt at runtime.

abstract class GenerateWebConfig : DefaultTask() {
    @get:Input
    abstract val url: Property<String>

    @get:Input
    abstract val pullToRefresh: Property<Boolean>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val out = outputDir.get().asFile
        out.deleteRecursively()
        out.mkdirs()
        val json = groovy.json.JsonOutput.toJson(mapOf("url" to url.get(), "pullToRefresh" to pullToRefresh.get()))
        File(out, "webview.json").writeText(groovy.json.JsonOutput.prettyPrint(json) + "\n")
    }
}

val generateAppIcons = tasks.register<GenerateAppIcons>("generateAppIcons") {
    icon.set(iconFile)
    background.set(iconBackground)
}
val generateWebConfig = tasks.register<GenerateWebConfig>("generateWebConfig") {
    url.set(appUrl)
    pullToRefresh.set(pullToRefreshEnabled)
}

// Fixed output folders: builder/build.gradle.kts reads the template icons from here.
val generateTemplateIcons = tasks.register<GenerateAppIcons>("generateTemplateIcons") {
    icon.set(templateIconFile)
    background.set("#FFFFFF")
    outputDir.set(layout.buildDirectory.dir("generated/templateIcons"))
}
val generateTemplateWebConfig = tasks.register<GenerateWebConfig>("generateTemplateWebConfig") {
    url.set(templateUrl)
    pullToRefresh.set(true)
}

androidComponents {
    onVariants { variant ->
        val template = variant.buildType == "template"
        if (template) {
            variant.sources.res?.addGeneratedSourceDirectory(generateTemplateIcons, GenerateAppIcons::outputDir)
            variant.sources.assets?.addGeneratedSourceDirectory(generateTemplateWebConfig, GenerateWebConfig::outputDir)
            // Placeholders the builder app replaces; versionCode 1 is rewritten too.
            variant.applicationId.set(templateApplicationId)
            variant.outputs.forEach {
                it.versionCode.set(1)
                it.versionName.set(templateVersionName)
            }
        } else {
            variant.sources.res?.addGeneratedSourceDirectory(generateAppIcons, GenerateAppIcons::outputDir)
            variant.sources.assets?.addGeneratedSourceDirectory(generateWebConfig, GenerateWebConfig::outputDir)
        }
    }
}

println("App: $appFolder  ($appName v$appVersion, build $buildNumber, $appId)")
