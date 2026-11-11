import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

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
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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

    buildTypes {
        release {
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
    implementation(libs.material)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.datastore)
    implementation(libs.androidx.work.runtime.ktx)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.zxing.core) // QR code for TOTP setup (Apache-2.0)
    implementation(libs.aboutlibraries.compose.m3) // open-source license screen (Apache-2.0)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
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
