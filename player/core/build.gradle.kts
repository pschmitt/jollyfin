plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.parcelize)
}

android {
    namespace = "dev.pschmitt.jellyfin.player.core"
    compileSdk {
        version = release(Versions.COMPILE_SDK) { minorApiLevel = Versions.COMPILE_SDK_MINOR }
    }
    buildToolsVersion = Versions.BUILD_TOOLS

    defaultConfig { minSdk = Versions.MIN_SDK }

    buildTypes {
        named("release") { isMinifyEnabled = false }
        register("staging") { initWith(getByName("release")) }
    }

    compileOptions {
        sourceCompatibility = Versions.JAVA
        targetCompatibility = Versions.JAVA
    }
}

dependencies { implementation(libs.timber) }
