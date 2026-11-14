// :native (ADR 0019): Hoard 2, the Kotlin + Jetpack Compose app. Gallery and Files tabs over :core:* and :core:data.
// Until it reaches parity with the Capacitor :app it is installed NEXT to it: application id dev.hfps.hoard + ".next"
// in every build type. The swap PR drops the suffix in release so the owner's install upgrades in place.
//
// Emulator only: `-Phoard.native.sameId=true` builds a debug APK with the Capacitor app's id (dev.hfps.hoard), same
// debug signing, a higher versionCode: `adb install -r` upgrades the Capacitor debug app in place, exactly what the
// swap does, so the first-launch import runs on that app's real database (docs/testing/native-2a.md). Never install
// it on a phone that has the real app.

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("io.github.takahirom.roborazzi")
}

val sameId = providers.gradleProperty("hoard.native.sameId").orNull == "true"
if (sameId && gradle.startParameter.taskNames.any { it.contains("release", ignoreCase = true) }) {
    throw GradleException("hoard.native.sameId is for debug builds on an emulator only")
}

android {
    namespace = "dev.hfps.hoard.app"
    compileSdk = rootProject.extra["compileSdkVersion"] as Int
    ndkVersion = rootProject.extra["ndkVersion"] as String

    defaultConfig {
        applicationId = "dev.hfps.hoard"
        minSdk = rootProject.extra["minSdkVersion"] as Int
        targetSdk = rootProject.extra["targetSdkVersion"] as Int
        // Above every Capacitor release (1.x is 19 at 1.10.1), so the swap and the same-id test install over it.
        versionCode = 100
        versionName = "2.0.0-dev"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Release signing as in :app (skv group "hoard", ADR 0019 keeps one signing identity for the swap).
    val releaseKeystore = System.getenv("HOARD_KEYSTORE")
    val releaseSigning = releaseKeystore != null && file(releaseKeystore).exists() &&
        System.getenv("HOARD_KEYSTORE_PASSWORD") != null && System.getenv("HOARD_KEY_ALIAS") != null
    signingConfigs {
        if (releaseSigning) {
            create("release") {
                storeFile = file(releaseKeystore!!)
                storePassword = System.getenv("HOARD_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("HOARD_KEY_ALIAS")
                keyPassword = System.getenv("HOARD_KEY_PASSWORD") ?: System.getenv("HOARD_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            if (!sameId) applicationIdSuffix = ".next"
        }
        release {
            applicationIdSuffix = ".next"
            if (releaseSigning) signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources {
            excludes += listOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/*.md",
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
                "META-INF/versions/*/module-info.class",
                "module-info.class",
            )
        }
    }
    // The importer test on a device reads the same Capacitor fixtures as :core:data's JVM tests.
    sourceSets["androidTest"].assets.srcDir("../core/data/fixtures")
    testOptions.unitTests.isIncludeAndroidResources = true
    testOptions.unitTests.all {
        it.testLogging { events("passed", "skipped", "failed") }
    }
}

kotlin { jvmToolchain(21) }

apply(from = "../app/mlkit-packaging.gradle")

dependencies {
    implementation(project(":core:designsystem"))
    implementation(project(":core:data"))
    implementation(project(":core:smb"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.serialization.json)
    implementation("androidx.core:core-ktx:${rootProject.extra["androidxCoreVersion"]}")

    testImplementation("junit:junit:${rootProject.extra["junitVersion"]}")
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.truth)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.truth)
}
