import java.util.Properties
import java.io.FileInputStream

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.dagger.hilt)
    alias(libs.plugins.jetbrains.kotlin.android)
    alias(libs.plugins.jetbrains.serialization)
    alias(libs.plugins.kotlinter)
    alias(libs.plugins.ksp)
}

val keystorePropertiesFile = rootProject.file("app/keystores/keystore.properties")
val keystoreProperties: Properties? = if (keystorePropertiesFile.exists()) {
    Properties().apply {
        load(FileInputStream(keystorePropertiesFile))
    }
} else null

// Release channel: `./gradlew -PwowRelease=true ...` builds the stable app (forever.launcher, "Forever Launcher"; app.wowforever until
// 1.4.4). Without it the build is the BETA channel (forever.launcher.beta, "Forever Launcher Beta"), installed next to the release.
val wowRelease = project.findProperty("wowRelease")?.toString() == "true"

android {
    namespace = "app.gamenative"
    compileSdk = 36

    // https://developer.android.com/ndk/downloads
    ndkVersion = "27.3.13750724"

    signingConfigs {
        create("release") {
            if (keystoreProperties != null) {
                storeFile = file(keystoreProperties["storeFile"].toString())
                storePassword = keystoreProperties["storePassword"].toString()
                keyAlias = keystoreProperties["keyAlias"].toString()
                keyPassword = keystoreProperties["keyPassword"].toString()
            }
        }
    }

    defaultConfig {
        applicationId = "com.bryanengle.classicera.launcher"

        minSdk = 26

        manifestPlaceholders["screenOrientation"] = "unspecified"

        versionCode = 1027
        // One number per change round (1.0.1, 1.0.2, ...): the APK version, the archive version and the notes use the same value.
        versionName = "0.1.2-classic-era-test"
        manifestPlaceholders["appLabel"] = "Classic Era Test"

        val iconValue = "@mipmap/ic_package_042"
        val iconRoundValue = "@mipmap/ic_package_042_round"
        manifestPlaceholders.putAll(
            mapOf(
                "icon" to iconValue,
                "roundIcon" to iconRoundValue,
            ),
        )

        ndk {
            //abiFilters.addAll(listOf("arm64-v8a", "armeabi-v7a"))
        }

        // Localization support - specify which languages to include
        // English and Hungarian resources: the WoW screens use tx(); the Quick Menu reads resources (values / values-hu) and
        // follows the in-app language (1.4.1). Library translations into other languages are left out.
        // The app UI languages (WowLanguage): English, Hungarian and the WoW Forever game languages.
        resourceConfigurations += listOf("en", "hu", "de", "fr", "es", "pt-rBR", "ru", "ko", "zh-rCN", "zh-rTW")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        proguardFiles(
            getDefaultProguardFile("proguard-android-optimize.txt"),
            "proguard-rules.pro",
        )
    }

    flavorDimensions += "androidApi"
    productFlavors {
        create("modern") {
            dimension = "androidApi"
            minSdk = 29
            targetSdk = 36
            ndk.abiFilters += listOf("arm64-v8a")
        }
    }

    buildTypes {
        debug {
            isDebuggable = true
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.getByName("debug")
        }
        create("profile") {
            initWith(getByName("release"))
            isDebuggable = false
            // R8 build 0.1.44 crashed instantly on device; keep code/resources unshrunk.
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release", "debug")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName(if (keystoreProperties != null) "release" else "debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // The bundled root file system and shared files are already xz/zstd compressed: store them as they are.
    androidResources {
        noCompress += listOf("txz", "tzst", "wcp")
    }

    packaging {
        // Deflate classes.dex inside the APK (about 5 MB smaller); ART still compiles it at install time.
        dex {
            useLegacyPackaging = true
        }
        resources {
            excludes += "/DebugProbesKt.bin"
            excludes += "/junit/runner/smalllogo.gif"
            excludes += "/junit/runner/logo.gif"
            excludes += "/META-INF/versions/9/OSGI-INF/MANIFEST.MF"
            // commons-codec phonetic rule tables (Beider-Morse etc.): only loaded by the Soundex/BeiderMorse encoders, which nothing calls
            excludes += "/org/apache/commons/codec/language/bm/**"
            excludes += "/org/apache/commons/codec/language/dmrules.txt"
        }
        jniLibs {
            // 'extractNativeLibs' was not enough to keep the jniLibs and
            // the libs went missing after adding on-demand feature delivery
            useLegacyPackaging = true
        }
    }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all {
                it.maxHeapSize = "4g"
                it.testLogging { events("started", "failed") }
            }
        }
    }

    lint {
        // Locale files ship full AndroidX appcompat (abc_*) translations that aren't in the
        // default locale. These extra translations are harmless and pre-existing; without this
        // the release-only lintVital pass fails on 150+ ExtraTranslation errors.
        disable += "ExtraTranslation"
    }

    // Configure Assets to be used in different variants
    sourceSets {
        getByName("modern") {
            assets {
                srcDirs("src/modern/assets", "src/main/assets")
            }
        }
    }

    kotlinter {
        ignoreFormatFailures  = false
    }

}

dependencies {
    // Not used by the code, but it pins newer androidx drawerlayout/transition/coordinatorlayout for other libraries.
    implementation(libs.material)

    implementation(libs.okhttp)

    // Winlator
    implementation(libs.bundles.winlator)
    implementation(libs.zstd.jni) { artifact { type = "aar" } }
    implementation(libs.xz)

    // Jetpack Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.bundles.compose)
    debugImplementation(libs.androidx.ui.tooling)

    // Support
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.datastore.preferences)
    implementation(libs.jetbrains.kotlinx.json)
    implementation(libs.kotlin.coroutines)

    // Hilt
    implementation(libs.bundles.hilt)

    // KSP (Hilt)
    ksp(libs.bundles.ksp)


    // Memory Leak Detection
    // debugImplementation("com.squareup.leakcanary:leakcanary-android:3.0-alpha-8")

    // Testing
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.runner)
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.test.manifest)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.mockito.core)
    testImplementation(libs.mockito.kotlin)
    testImplementation(libs.mockk)
    testImplementation(libs.androidx.ui.test.junit4)
    testImplementation(libs.zstd.jni)
    testImplementation(libs.orgJson)
    testImplementation(libs.mockwebserver)
    testImplementation(libs.mockwebserver3) // addon provider tests (mockwebserver3 API)
}
