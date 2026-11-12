import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.util.Properties

/**
 * Version: semantic `major.minor.patch`; the version code is derived from it
 * (major × 10 000 + minor × 100 + patch), so it always grows with the name. See docs/distribution.md.
 */
val appVersion = "0.1.0"
val appVersionCode = appVersion.split('.').map(String::toInt).let { (major, minor, patch) ->
    require(minor < 100 && patch < 100) { "minor / patch must stay below 100" }
    major * 10_000 + minor * 100 + patch
}

/**
 * Optional release signing (Google Play upload key): read from `keystore.properties` in the
 * project root, which is never committed. Without it, the release build stays unsigned – that's
 * what F-Droid builds and signs itself.
 */
val keystoreProperties = Properties().apply {
    rootProject.file("keystore.properties").takeIf { it.isFile }?.inputStream()?.use { load(it) }
}

plugins {
    // AGP 9 has built-in Kotlin support, no separate kotlin-android plugin needed.
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    // Generates the list of all (also transitive) libraries with their license texts.
    alias(libs.plugins.aboutlibraries)
}

android {
    namespace = "de.lares_app"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "de.lares_app"
        minSdk = 26
        targetSdk = 37
        versionCode = appVersionCode
        versionName = appVersion

    }

    // All languages in every install: the in-app language switch and guardian messages in another
    // language need them (the alternative, Play Core language downloads, isn't FOSS).
    bundle {
        language {
            enableSplit = false
        }
    }

    androidResources {
        // Per-app language support; generates locales_config from values-xx folders.
        generateLocaleConfig = true
    }

    signingConfigs {
        if (keystoreProperties.isNotEmpty()) {
            create("upload") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("upload")
            optimization {
                // Whole-app optimization with the libraries' keep rules. (A partial `packageScope`
                // made R8 split Kotlin's package-private SequencesKt parts across packages →
                // IllegalAccessError at startup.)
                enable = true
            }
        }
        // Release-like build (optimized, not debuggable) for testing performance on a device.
        // Signed with the debug key so it installs without a store key; never published.
        create("localRelease") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
    lint {
        error += "MissingTranslation"
        warningsAsErrors = false
    }
    // Reproducible builds for F-Droid: no build-time dependency metadata blob.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(libs.androidx.room.runtime) // coroutine support is part of room-runtime since 2.7
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.datastore)
    implementation(libs.androidx.work.runtime.ktx)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.zxing.core) // QR code for TOTP setup (Apache-2.0)
    implementation(libs.aboutlibraries.core) // open-source license list, parsed from the generated JSON (Apache-2.0)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

/**
 * Puts the repository's LICENSE (GPL-3.0) into the app as `R.raw.lares_license`, so the full
 * license text can be shown offline. LICENSE stays the single source – no copy in the source tree.
 */
abstract class LicenseResTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val licenseFile: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun copy() {
        val raw = outputDir.get().dir("raw").asFile.apply { deleteRecursively(); mkdirs() }
        licenseFile.get().asFile.copyTo(raw.resolve("lares_license.txt"), overwrite = true)
    }
}

val licenseRes = tasks.register<LicenseResTask>("generateLicenseRes") {
    licenseFile.set(rootProject.layout.projectDirectory.file("LICENSE"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.res?.addGeneratedSourceDirectory(licenseRes, LicenseResTask::outputDir)
    }
}

aboutLibraries {
    // Offline and reproducible (F-Droid): only license texts known to the plugin, no network access.
    offlineMode = true
}
