import com.android.build.api.variant.ApplicationVariant
import java.util.Properties

plugins {
    id("com.android.application")
    kotlin("android")
    kotlin("plugin.serialization") version "2.2.21"
    kotlin("plugin.compose") version "2.2.21"
}

android {
    compileSdk = 36

    defaultConfig {
        applicationId = "com.aikeyboard.app"
        minSdk = 29
        targetSdk = 36
        // Phase 12 §8.2: semver reset for the AI Keyboard fork. HeliBoard's
        // upstream sequence (3901 / "3.9") is intentionally abandoned;
        // com.aikeyboard.app is a fresh package id in F-Droid's index.
        // versionCode increments monotonically from 1 onwards.
        // CAVEAT for early testers: bumping versionCode from 3901 → 1 is a
        // downgrade from Android's perspective; uninstall any prior
        // dev-build before installing v0.1.0. See README install section.
        versionCode = 1
        versionName = "0.1.0"
        ndk {
            abiFilters.clear()
            abiFilters.addAll(listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64"))
        }
        proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
    }

    // Phase 12 §8.1: release signing config. Reads keystore.path,
    // keystore.password, key.alias, key.password from `keystore.properties`
    // at repo root (gitignored — never commit; see BUILD.md). If the
    // properties file is absent and `-PforceReleaseSigning` was passed
    // (CI / release contexts) the build hard-fails; otherwise it falls
    // back to debug signing with a Gradle warning so local development
    // works without keystore setup.
    signingConfigs {
        create("release") {
            val keystorePropsFile = rootProject.file("keystore.properties")
            val forceReleaseSigning = project.hasProperty("forceReleaseSigning")
            if (keystorePropsFile.exists()) {
                val props = Properties().apply { keystorePropsFile.inputStream().use { load(it) } }
                storeFile = rootProject.file(props.getProperty("keystore.path"))
                storePassword = props.getProperty("keystore.password")
                keyAlias = props.getProperty("key.alias")
                keyPassword = props.getProperty("key.password")
            } else if (forceReleaseSigning) {
                throw GradleException(
                    "keystore.properties not found and -PforceReleaseSigning was passed. " +
                        "Release signing is required in this context. See BUILD.md."
                )
            } else {
                logger.warn(
                    "keystore.properties not found; release builds will be debug-signed. " +
                        "Pass -PforceReleaseSigning in CI/release contexts to hard-fail instead. " +
                        "See BUILD.md for setup instructions."
                )
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = false
            isDebuggable = false
            isJniDebuggable = false
            // Phase 12 §8.1: signed with the release keystore if
            // keystore.properties exists at repo root; else debug-signed
            // (with the Gradle warning logged above).
            signingConfig = if (rootProject.file("keystore.properties").exists())
                signingConfigs.getByName("release")
            else
                signingConfigs.getByName("debug")
        }
        create("nouserlib") { // same as release, but does not allow the user to provide a library
            isMinifyEnabled = true
            isShrinkResources = false
            isDebuggable = false
            isJniDebuggable = false
        }
        debug {
            // "normal" debug has minify for smaller APK to fit the GitHub 25 MB limit when zipped
            // and for better performance in case users want to install a debug APK
            isMinifyEnabled = true
            isJniDebuggable = false
            applicationIdSuffix = ".debug"
        }
        create("runTests") { // build variant for running tests on CI that skips tests known to fail
            isMinifyEnabled = false
            isJniDebuggable = false
        }
        create("debugNoMinify") { // for faster builds in IDE
            isDebuggable = true
            isMinifyEnabled = false
            isJniDebuggable = false
            signingConfig = signingConfigs.getByName("debug")
            applicationIdSuffix = ".debug"
        }
        base.archivesBaseName = "ai-keyboard-" + defaultConfig.versionName
        // got a little too big for GitHub after some dependency upgrades, so we remove the largest dictionary
        androidComponents.onVariants { variant: ApplicationVariant ->
            if (variant.buildType == "debug") {
                variant.androidResources.ignoreAssetsPatterns = listOf("main_ro.dict")
                variant.proguardFiles = emptyList()
                //noinspection ProguardAndroidTxtUsage we intentionally use the "normal" file here
                variant.proguardFiles.add(project.layout.buildDirectory.file(getDefaultProguardFile("proguard-android.txt").absolutePath))
                variant.proguardFiles.add(project.layout.buildDirectory.file(project.buildFile.parent + "/proguard-rules.pro"))
            }
        }
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
        compose = true
    }

    externalNativeBuild {
        ndkBuild {
            path = File("src/main/jni/Android.mk")
        }
    }
    ndkVersion = "28.0.13004108"

    packaging {
        jniLibs {
            // shrinks APK by 3 MB, zipped size unchanged
            useLegacyPackaging = true
        }
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = JavaVersion.VERSION_17.toString()
    }

    // see https://github.com/Helium314/HeliBoard/issues/477
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    namespace = "com.aikeyboard.app.latin"
    lint {
        abortOnError = true
        baseline = file("lint-baseline.xml")
    }

    flavorDimensions += "distribution"
    productFlavors {
        create("fdroid") {
            dimension = "distribution"
            buildConfigField("boolean", "ENABLE_A11Y", "true")
        }
        create("play") {
            dimension = "distribution"
            buildConfigField("boolean", "ENABLE_A11Y", "false")
            applicationIdSuffix = ".play"
            versionNameSuffix = "-play"
        }
    }
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    // androidx
    implementation("androidx.core:core-ktx:1.16.0") // 1.17 requires SDK 36
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    implementation("androidx.autofill:autofill:1.3.0")
    implementation("androidx.viewpager2:viewpager2:1.1.0")
    // androidx.security:security-crypto: kept solely for the one-time migration in
    // SecureStorage from Phase 2's EncryptedSharedPreferences to the Tink AEAD blob.
    // Phase 12 polish should remove this once we're confident no users have unmigrated state.
    implementation("androidx.security:security-crypto:1.1.0")

    // AI Keyboard: Tink AEAD primitives, used by SecureStorage to encrypt the on-disk
    // blob that holds personas + API keys. Master keyset wrapped by Android Keystore.
    implementation("com.google.crypto.tink:tink-android:1.21.0")

    // AI Keyboard: Ktor HTTPS client (OkHttp engine) for streaming Anthropic Messages
    // and Gemini streamGenerateContent. Phase 3a wires the singleton + request builders;
    // Phase 3b implements actual response streaming.
    implementation("io.ktor:ktor-client-okhttp:3.4.3")
    implementation("io.ktor:ktor-client-content-negotiation:3.4.3")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.4.3")

    // kotlin
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")

    // compose
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
    // newer than 2025.11.01 contains androidx.compose.material:material-android:1.10.0, which requires minSdk 23
    // maybe it's possible to use tools:overrideLibrary="androidx.compose.material" as it's not used explicitly, but probably this is just going to crash
    implementation(platform("androidx.compose:compose-bom:2025.11.01"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.navigation:navigation-compose:2.9.6")
    // Phase 9a: PickMultipleVisualMedia + rememberLauncherForActivityResult come from
    // activity-compose (currently transitive via navigation-compose). Pinning it as a
    // direct dependency avoids silent breakage if a future navigation-compose bump
    // drops the transitive. BOM-managed (no version pin).
    implementation("androidx.activity:activity-compose")
    implementation("sh.calvin.reorderable:reorderable:2.4.3") // for easier re-ordering, todo: check 3.0.0
    implementation("com.github.skydoves:colorpicker-compose:1.1.3") // for user-defined colors

    // test
    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.mockito:mockito-core:5.17.0")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:runner:1.6.2")
    testImplementation("androidx.test:core:1.6.1")
}
