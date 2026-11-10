import groovy.json.JsonOutput
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.Properties
import java.util.zip.ZipFile
import javax.imageio.ImageIO

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

//========== VERSION ==========
// CI passes -PbuilderVersion=<x.y.z> (from the release tag) and -PbuilderVersionCode=<n>.
val builderVersion = providers.gradleProperty("builderVersion").orNull ?: "1.0.0"
val builderVersionCode = providers.gradleProperty("builderVersionCode").orNull?.toIntOrNull() ?: 1

//========== RELEASE SIGNING ==========
// Same key.properties as the WebView apps. Without it the debug key is used.
val keystorePropertiesFile = rootProject.file("key.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.isFile) FileInputStream(keystorePropertiesFile).use { load(it) }
}

android {
    namespace = "com.webview.builder"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.webview.builder"
        minSdk = 26
        targetSdk = 36
        versionCode = builderVersionCode
        versionName = builderVersion
    }

    buildFeatures {
        compose = true
        buildConfig = true
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
    }

    packaging {
        resources {
            excludes += listOf("META-INF/*.version", "META-INF/*.kotlin_module", "DebugProbesKt.bin", "kotlin/**")
        }
    }

    // The template APK must stay byte-for-byte as built (it is stored, not compressed again).
    androidResources {
        noCompress += "apk"
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.12.00"))
    implementation("androidx.compose.material3:material3:1.5.0-alpha08")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.activity:activity-compose:1.12.0")
    implementation("androidx.core:core-ktx:1.17.0")
    // Signs the generated APKs (v1 + v2 + v3), the same library the Android SDK's apksigner uses.
    implementation("com.android.tools.build:apksig:9.0.1")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
}

//========== TEMPLATE APK ==========
// Copies the WebView app's template APK into this app's assets, together with
// template.json: the placeholder values to replace and, for every launcher icon
// file, its path inside the APK. Resource paths are shortened by the build
// (res/Ab.png), so the icons are found by comparing file hashes with the icons
// generated for the template (app/build/generated/templateIcons).

abstract class PrepareTemplate : DefaultTask() {
    @get:InputFile
    abstract val templateApk: RegularFileProperty

    @get:InputDirectory
    abstract val templateIcons: DirectoryProperty

    @get:InputFile
    abstract val templateIconSource: RegularFileProperty

    @get:Input
    abstract val placeholders: MapProperty<String, String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun prepare() {
        System.setProperty("java.awt.headless", "true")
        val out = File(outputDir.get().asFile, "template")
        out.deleteRecursively()
        out.mkdirs()
        val apk = templateApk.get().asFile
        apk.copyTo(File(out, "template.apk"))
        templateIconSource.get().asFile.copyTo(File(out, "default_icon.png"))

        fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

        // Hash of every PNG inside the APK -> its entry path.
        val entriesByHash = ZipFile(apk).use { zip ->
            zip.entries().asSequence().filter { it.name.endsWith(".png") }
                .associate { sha256(zip.getInputStream(it).readBytes()) to it.name }
        }
        val icons = templateIcons.get().asFile.walkTopDown().filter { it.isFile && it.name.endsWith(".png") }.map { file ->
            val entry = entriesByHash[sha256(file.readBytes())]
                ?: throw GradleException("Template icon ${file.name} (${file.parentFile.name}) not found in $apk")
            val kind = when (file.nameWithoutExtension) {
                "ic_launcher" -> "legacy"
                "ic_launcher_foreground" -> "foreground"
                "ic_launcher_background" -> "background"
                else -> throw GradleException("Unexpected template icon ${file.name}")
            }
            mapOf("entry" to entry, "kind" to kind, "size" to ImageIO.read(file).width)
        }.toList()

        val json = placeholders.get() + mapOf("configAsset" to "assets/webview.json", "icons" to icons)
        File(out, "template.json").writeText(JsonOutput.prettyPrint(JsonOutput.toJson(json)) + "\n")
    }
}

val appProject = project(":app")
val prepareTemplate = tasks.register<PrepareTemplate>("prepareTemplate") {
    dependsOn(":app:assembleTemplate")
    templateApk.set(appProject.layout.buildDirectory.file("outputs/apk/template/app-template.apk"))
    templateIcons.set(appProject.layout.buildDirectory.dir("generated/templateIcons"))
    templateIconSource.set(appProject.layout.projectDirectory.file("template/icon.png"))
    placeholders.set(
        mapOf(
            "appName" to providers.gradleProperty("templateAppName").get(),
            "applicationId" to providers.gradleProperty("templateApplicationId").get(),
            "versionName" to providers.gradleProperty("templateVersionName").get(),
            "url" to providers.gradleProperty("templateUrl").get(),
        )
    )
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(prepareTemplate, PrepareTemplate::outputDir)
    }
}

// ApkWriterTest builds an app from the real template APK, like the phone does.
tasks.withType<Test>().configureEach {
    dependsOn(":app:assembleTemplate")
    systemProperty("template.apk", appProject.layout.buildDirectory.file("outputs/apk/template/app-template.apk").get().asFile.path)
    for (name in listOf("appName", "applicationId", "versionName")) {
        systemProperty("template.$name", providers.gradleProperty("template${name.replaceFirstChar(Char::uppercase)}").get())
    }
}
