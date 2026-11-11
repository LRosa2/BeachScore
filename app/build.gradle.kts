import java.net.URI
import java.security.MessageDigest
import java.util.zip.ZipInputStream

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

android {
    namespace = "ch.beachscore.app"
    // Latest installed platform; current AndroidX/Compose requires compiling against 37.
    // Runtime behaviour follows targetSdk 36.
    compileSdk = 37

    defaultConfig {
        applicationId = "ch.beachscore.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 3
        versionName = "3.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            // Vosk ships native code for four ABIs. Real phones are arm (64-bit, and old 32-bit
            // ones); x86_64 is kept for the emulator. 32-bit x86 is dropped to save APK size.
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        // Local JVM tests use JUnit 6 (Jupiter).
        unitTests.all { it.useJUnitPlatform() }
    }

    sourceSets {
        // In-memory fakes shared by the JVM tests and the on-device Compose test.
        getByName("test").kotlin.directories += "src/sharedTest/java"
        getByName("androidTest").kotlin.directories += "src/sharedTest/java"
    }
}

room {
    // Exported schemas are committed, so later migrations can be tested against them.
    schemaDirectory("$projectDir/schemas")
}

// --- Vosk speech models ------------------------------------------------------------------------
//
// The small German and English models (~170 MB unpacked) are not committed. This task downloads
// the pinned zips once into /.vosk-cache (git-ignored, survives `clean`), checks their SHA-256,
// and unpacks them as generated assets: assets/vosk/<lang>/... plus a manifest of files and
// sizes that the app uses to copy a model to internal storage with progress on first use.

data class VoskModel(val lang: String, val name: String, val sha256: String)

val voskModels = listOf(
    VoskModel("de", "vosk-model-small-de-0.15", "b7e53c90b1f0a38456f4cd62b366ecd58803cd97cd42b06438e2c131713d5e43"),
    VoskModel("en", "vosk-model-small-en-us-0.15", "30f26242c4eb449f948e42cb302dd7a686cb29a3423a8367f99ff41780942498"),
)

abstract class PrepareVoskModels : DefaultTask() {
    /** "lang|name|sha256" per model. */
    @get:Input
    abstract val models: ListProperty<String>

    @get:Internal
    abstract val cacheDir: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun prepare() {
        val out = outputDirectory.get().asFile
        out.deleteRecursively()
        val cache = cacheDir.get().asFile.apply { mkdirs() }
        for (spec in models.get()) {
            val (lang, name, sha) = spec.split('|')
            val zip = cache.resolve("$name.zip")
            if (!zip.exists() || sha256(zip) != sha) {
                logger.lifecycle("Downloading Vosk model $name")
                val tmp = cache.resolve("$name.zip.part")
                URI("https://alphacephei.com/vosk/models/$name.zip").toURL().openStream().use { input ->
                    tmp.outputStream().use { input.copyTo(it) }
                }
                val actual = sha256(tmp)
                check(actual == sha) { "SHA-256 mismatch for $name: $actual" }
                tmp.renameTo(zip.also { it.delete() })
            }
            val target = out.resolve("vosk/$lang")
            val manifest = StringBuilder("$name\n")
            ZipInputStream(zip.inputStream().buffered()).use { zin ->
                while (true) {
                    val entry = zin.nextEntry ?: break
                    val relative = entry.name.substringAfter('/', "")
                    if (entry.isDirectory || relative.isEmpty()) continue
                    val file = target.resolve(relative)
                    file.parentFile.mkdirs()
                    val size = file.outputStream().use { zin.copyTo(it) }
                    manifest.append(relative).append('\t').append(size).append('\n')
                }
            }
            target.resolve("manifest.txt").writeText(manifest.toString())
        }
    }

    private fun sha256(file: java.io.File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

val prepareVoskModels = tasks.register<PrepareVoskModels>("prepareVoskModels") {
    models.set(voskModels.map { "${it.lang}|${it.name}|${it.sha256}" })
    cacheDir.set(rootProject.layout.projectDirectory.dir(".vosk-cache"))
    outputDirectory.set(layout.buildDirectory.dir("generated/voskAssets"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(prepareVoskModels, PrepareVoskModels::outputDirectory)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.vosk.android)
    implementation(libs.androidx.room.runtime)
    ksp(libs.androidx.room.compiler)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.junit4)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.room.testing)
}
