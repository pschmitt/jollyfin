plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose.compiler)
    alias(libs.plugins.kotlin.parcelize)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.aboutlibraries)
    alias(libs.plugins.aboutlibraries.android)
}

val configuredVersionCode =
    providers.gradleProperty("versionCode").orNull?.toIntOrNull() ?: Versions.APP_CODE
val configuredVersionName =
    providers.gradleProperty("versionName").orNull ?: Versions.APP_NAME

android {
    namespace = "dev.pschmitt.jellyfin"
    compileSdk = Versions.COMPILE_SDK
    buildToolsVersion = Versions.BUILD_TOOLS

    defaultConfig {
        applicationId = "dev.pschmitt.jollyfin"
        minSdk = Versions.MIN_SDK
        targetSdk = Versions.TARGET_SDK

        versionCode = configuredVersionCode
        versionName = configuredVersionName

        val gitRevision = System.getenv("GIT_REVISION") ?: "unknown"
        buildConfigField("String", "GIT_REVISION", "\"$gitRevision\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        // Only overridden in CI (see .github/workflows/release-latest.yaml), which decodes a
        // persistent keystore from a secret and exports CI_KEYSTORE_PATH - local debug builds
        // keep using the regular auto-generated ~/.android/debug.keystore. Without this, every CI
        // run signs with a different ephemeral debug key, which breaks update checks for anyone
        // installing "latest" builds via Obtainium (signature mismatch on every release).
        named("debug") {
            System.getenv("CI_KEYSTORE_PATH")?.let { path ->
                storeFile = file(path)
                storePassword = System.getenv("CI_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("CI_KEY_ALIAS")
                keyPassword = System.getenv("CI_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        named("debug") { applicationIdSuffix = ".debug" }
        named("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Reuses the CI keystore override above so CI can also produce a signed, installable
            // release build - fastlane's `publish` lane overrides this via injected Gradle
            // properties for the real Play Store signing key, so this has no effect there.
            signingConfig = signingConfigs.getByName("debug")
        }
        register("staging") {
            initWith(getByName("release"))
            applicationIdSuffix = ".staging"
        }
    }

    flavorDimensions += "variant"
    productFlavors {
        register("libre") {
            dimension = "variant"
            isDefault = true
        }
    }

    splits {
        abi {
            // Detect app bundle and conditionally disable split abis
            // This is needed due to a "Multiple shrunk-resources files found in directory" error
            // present since AGP 8.9.0, for more info see:
            // https://issuetracker.google.com/issues/402800800
            val isBuildingBundle =
                gradle.startParameter.taskNames.any { it.lowercase().contains("bundle") }
            isEnable = !isBuildingBundle

            reset()
            include("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true

        sourceCompatibility = Versions.JAVA
        targetCompatibility = Versions.JAVA
    }

    buildFeatures {
        buildConfig = true
        viewBinding = true
        compose = true
    }

    dependenciesInfo {
        // Disables dependency metadata when building APKs.
        includeInApk = false
        // Disables dependency metadata when building Android App Bundles.
        includeInBundle = false
    }
}

dependencies {
    implementation(projects.core)
    implementation(projects.data)
    implementation(projects.player.core)
    implementation(projects.player.local)
    implementation(projects.setup)
    implementation(projects.modes.film)
    implementation(projects.settings)

    implementation(libs.aboutlibraries.core)
    implementation(libs.aboutlibraries.compose.m3)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.biometric)

    // Camera / QR scanning
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.zxing.core)

    // Compose
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.runtime)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material3.adaptive.navigation.suite)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)

    implementation(libs.androidx.core)
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.hilt.work)
    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.media3.session)
    implementation(libs.androidx.paging)
    implementation(libs.androidx.paging.compose)
    implementation(libs.androidx.window)
    implementation(libs.androidx.work)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.coil.network.cache.control)
    implementation(libs.coil.svg)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.jellyfin.core)
    ksp(libs.kotlin.metadata.jvm)
    compileOnly(libs.libmpv)
    implementation(libs.reorderable)
    implementation(libs.material)
    implementation(libs.media3.ffmpeg.decoder)
    implementation(libs.timber)

    coreLibraryDesugaring(libs.android.desugar.jdk)

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.navigation.compose)

    // Play Store screenshot capture (FINDROID-71) - see ci/jellyfin/README.md and
    // StoreScreenshotTest.kt. Not used by any other test in this module.
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.uiautomator)
    androidTestImplementation(libs.fastlane.screengrab)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
