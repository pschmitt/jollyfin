import javax.inject.Inject
import org.gradle.api.file.FileSystemOperations

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose.compiler)
    alias(libs.plugins.kotlin.parcelize)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "dev.pschmitt.jellyfin.core"
    compileSdk {
        version = release(Versions.COMPILE_SDK) { minorApiLevel = Versions.COMPILE_SDK_MINOR }
    }
    buildToolsVersion = Versions.BUILD_TOOLS

    defaultConfig {
        minSdk = Versions.MIN_SDK
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        named("release") { isMinifyEnabled = false }
        register("staging") { initWith(getByName("release")) }
    }

    flavorDimensions += "variant"
    productFlavors { register("libre") }

    compileOptions {
        sourceCompatibility = Versions.JAVA
        targetCompatibility = Versions.JAVA
    }

    buildFeatures { compose = true }
}

// Bundles `cli/jollyfin-cli` as an asset so LocalControlServer's `GET /cli` route can serve it
// verbatim (see LocalControlServer.serveCliScript) - copied at build time instead of hand-
// duplicating the script into a second file that could drift out of sync. Registered as a
// generated asset directory via the variant API (rather than writing straight into
// src/main/assets and hoping every consumer - merge, lint-vital, packaging - happens to depend on
// it) so AGP wires the task dependency correctly for all of them on its own.
abstract class CopyJollyfinCliAsset
@Inject
constructor(private val fileSystemOperations: FileSystemOperations) : DefaultTask() {
    @get:InputFile abstract val cliScript: RegularFileProperty

    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @TaskAction
    fun run() {
        fileSystemOperations.copy {
            from(cliScript)
            into(outputDir)
        }
    }
}

val copyJollyfinCliAsset =
    tasks.register<CopyJollyfinCliAsset>("copyJollyfinCliAsset") {
        cliScript.set(rootProject.file("cli/jollyfin-cli"))
        outputDir.set(layout.buildDirectory.dir("generated/jollyfinCliAssets"))
    }

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(
            copyJollyfinCliAsset,
            CopyJollyfinCliAsset::outputDir,
        )
    }
}

dependencies {
    implementation(projects.data)
    implementation(projects.player.core)
    implementation(projects.settings)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.compose.ui)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.core)
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)
    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.paging)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.work)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.jellyfin.core)
    implementation(libs.material)
    implementation(libs.okhttp)
    implementation(libs.androidx.security.crypto)
    implementation(libs.timber)
    implementation(libs.slf4j.api)
    implementation(libs.slf4j.android)

    testImplementation(libs.junit)

    androidTestImplementation(libs.androidx.work.testing)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    api(libs.nanohttpd)
    androidTestImplementation(libs.nanohttpd)
}
