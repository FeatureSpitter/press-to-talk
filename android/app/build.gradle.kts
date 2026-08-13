// Explicit import: inside android {}, `java` resolves to Gradle's java extension
// and shadows the package name.
import java.util.Properties

plugins {
    // No kotlin-android plugin: AGP 9 has built-in Kotlin support and rejects it.
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

/**
 * The bundled flavor's ~375 MB of model files are staged into
 * src/bundled/assets/models/ by scripts/stage-bundled.sh - they are gitignored
 * and never committed.
 *
 * This only checks they are there, so a forgotten staging step fails with an
 * instruction instead of silently shipping a self-contained APK containing no
 * model at all.
 */
val verifyBundledAssets by tasks.registering {
    description = "Fails early if the bundled flavor's model assets have not been staged."
    val assetsDir = layout.projectDirectory.dir("src/bundled/assets/models")
    doFirst {
        val vad = assetsDir.file("silero_vad.onnx").asFile
        val encoders = assetsDir.asFile.walkTopDown()
            .filter { it.name.endsWith("-encoder.int8.onnx") }
            .toList()
        check(vad.isFile && encoders.isNotEmpty()) {
            "The bundled flavor has no staged model. Run: scripts/stage-bundled.sh"
        }
        logger.lifecycle("Bundled model: ${encoders.first().parentFile.name}")
    }
}

tasks.matching { it.name.matches(Regex("merge.*Bundled.*Assets")) }
    .configureEach { dependsOn(verifyBundledAssets) }

android {
    namespace = "com.presstotalk.mobile"
    // 37 is forced by Compose BOM 2026.08.00 (androidx.compose.* 1.12.0 require it).
    // targetSdk stays at 36 - compiling against newer APIs is independent of
    // opting in to Android 17 runtime behavior.
    compileSdk = 37

    defaultConfig {
        applicationId = "com.presstotalk.mobile"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        // sherpa-onnx ships 4 ABIs; the Pixel 8 Pro (and every phone we care about) is arm64.
        ndk { abiFilters += "arm64-v8a" }
    }

    flavorDimensions += "model"
    productFlavors {
        // Lean build: no bundled model. Push one with scripts/push-model.sh.
        // Separate applicationId so it can sit alongside the bundled build.
        create("dev") {
            dimension = "model"
            applicationIdSuffix = ".dev"
            versionNameSuffix = "-dev"
        }
        // Self-contained build for sharing. Assets are staged from .models/ by
        // stageBundledAssets rather than checked in.
        create("bundled") {
            dimension = "model"
        }
    }


    // ONNX models must stay uncompressed so the first-launch copy out of assets
    // is a straight byte copy with a reliable available() size.
    androidResources {
        noCompress += "onnx"
    }

    // Signing details come from local.properties, which is not committed.
    // Without them the release build still works, signed with the debug key -
    // fine for testing, but such an APK cannot be upgraded in place by anyone
    // who installed a properly signed one.
    val signingProps = rootProject.file("local.properties").takeIf { it.isFile }?.let { file ->
        Properties().apply { file.inputStream().use { load(it) } }
    }
    val releaseStore = signingProps?.getProperty("pttStoreFile")?.let(::file)?.takeIf { it.isFile }

    signingConfigs {
        if (releaseStore != null) {
            create("release") {
                storeFile = releaseStore
                storePassword = signingProps.getProperty("pttStorePassword")
                keyAlias = signingProps.getProperty("pttKeyAlias")
                keyPassword = signingProps.getProperty("pttKeyPassword")
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            // R8 would roughly double build time and mangle stack traces, to save
            // a rounding error against a 375 MB model.
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

// jvmTarget is inherited from android.compileOptions.targetCompatibility above.

dependencies {
    // sherpa-onnx runtime. Not on Maven Central; fetched by scripts/fetch-deps.sh.
    implementation(fileTree("libs") { include("sherpa-onnx-*.aar") })

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.material3)
    // Deliberately NOT material-icons-extended: it adds ~40 MB for a handful of
    // icons. The few we need are vector drawables in res/drawable.
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
}
