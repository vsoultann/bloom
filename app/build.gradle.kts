import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "app.bloom"
    compileSdk { version = release(37) { minorApiLevel = 2 } }

    defaultConfig {
        // -PbloomId=app.bloom.shots builds a side-by-side copy (used for demo-mode screenshots)
        applicationId = (project.findProperty("bloomId") as String?) ?: "app.bloom"
        minSdk = 31          // Material You dynamic colour + RenderEffect blur
        targetSdk = 37
        versionCode = 9
        versionName = "0.3.6"
        ndk { abiFilters += "arm64-v8a" }
    }

    signingConfigs {
        getByName("debug") // default debug keystore
        // Release key lives outside the repo: ~/.bloom-signing/keystore.properties
        // (storeFile, storePassword, keyAlias, keyPassword). Without it, release builds use the debug key.
        val props = Properties()
        val f = file(System.getProperty("user.home") + "/.bloom-signing/keystore.properties")
        if (f.exists()) {
            f.inputStream().use { props.load(it) }
            create("release") {
                storeFile = file(props.getProperty("storeFile"))
                storePassword = props.getProperty("storePassword")
                keyAlias = props.getProperty("keyAlias")
                keyPassword = props.getProperty("keyPassword")
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
    buildFeatures { compose = true }
    packaging { jniLibs { useLegacyPackaging = false } }
}

// Build the Rust core and drop it into jniLibs before packaging.
// On an arm64 Android host (Termux) cargo builds natively; elsewhere it cross-compiles
// with cargo-ndk (`cargo install cargo-ndk`, `rustup target add aarch64-linux-android`).
val onAndroidHost = System.getProperty("java.vm.vendor").orEmpty().contains("Android") ||
    (System.getenv("PREFIX") ?: "").contains("com.termux")
val cargoBuild = tasks.register<Exec>("cargoBuild") {
    workingDir = rootProject.file("core")
    if (onAndroidHost) commandLine("cargo", "build", "--release")
    else commandLine("cargo", "ndk", "-t", "arm64-v8a", "build", "--release")
    doLast {
        val out = rootProject.file(if (onAndroidHost) "core/target/release/libbloomcore.so" else "core/target/aarch64-linux-android/release/libbloomcore.so")
        val dest = file("src/main/jniLibs/arm64-v8a")
        dest.mkdirs()
        out.copyTo(dest.resolve("libbloomcore.so"), overwrite = true)
    }
}
tasks.named("preBuild") { dependsOn(cargoBuild) }

dependencies {
    val bom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(bom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended:1.7.8")
    implementation("androidx.graphics:graphics-shapes:1.1.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.core:core-ktx:1.19.1")
    // installs the Compose libraries' baseline profiles so release builds start and scroll without JIT jank
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("dev.chrisbanes.haze:haze:2.1.0")
    implementation("dev.chrisbanes.haze:haze-blur:2.1.0")
    // images: avatars, server icons, attachments; animated GIF/WebP for GIFs and emoji
    implementation("io.coil-kt.coil3:coil-compose:3.6.3")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.6.3")
    implementation("io.coil-kt.coil3:coil-gif:3.6.3")
    implementation("io.coil-kt.coil3:coil-video:3.6.3")
    // video + voice message playback
    implementation("androidx.media3:media3-exoplayer:1.11.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.11.1") // Root videos are HLS streams
    implementation("androidx.media3:media3-transformer:1.11.1") // "Save video": HLS -> MP4
    implementation("androidx.media3:media3-ui-compose:1.11.1")
    // voice/video calls (prebuilt libwebrtc, Apache-2.0)
    implementation("io.getstream:stream-webrtc-android:1.3.10")
}
