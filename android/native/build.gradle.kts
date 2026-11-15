// :native (ADR 0019): Hoard 2, the Kotlin + Jetpack Compose app, and since 2.0.0 the only app: application id
// dev.hfps.hoard (debug and release), signed with Hoard 1's keystore (skv group "hoard"), so a Hoard 1.x install
// upgrades in place and its first launch imports Hoard 1's database (CapacitorImporter, :core:data).
//
// Debug builds carry the same id, signed with the debug key: they install over a Hoard 1 debug build on an emulator,
// which is the upgrade test (docs/testing/hoard-2.0.0.md). Never install a debug build on a phone with the real app
// (different signature: Android refuses it, and uninstalling first deletes the data).

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("io.github.takahirom.roborazzi")
}

android {
    // Android 13+'s per-app language page lists Hoard's languages (res/resources.properties names the default).
    androidResources.generateLocaleConfig = true
    namespace = "dev.hfps.hoard.app"
    compileSdk = rootProject.extra["compileSdkVersion"] as Int
    ndkVersion = rootProject.extra["ndkVersion"] as String

    defaultConfig {
        applicationId = "dev.hfps.hoard"
        minSdk = rootProject.extra["minSdkVersion"] as Int
        targetSdk = rootProject.extra["targetSdkVersion"] as Int
        // Above every Hoard 1 release (1.11.0 is 20) and Hoard Next's betas (101), so 2.0.0 upgrades 1.x in place.
        versionCode = 202
        versionName = "2.0.2"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Release signing reads the keystore from the environment, never from the repo: `bun run android:release` runs
    // Gradle under `skv run hoard`, which exports HOARD_KEYSTORE (a temp copy of the .jks), HOARD_KEYSTORE_PASSWORD,
    // HOARD_KEY_ALIAS and HOARD_KEY_PASSWORD. The same keystore as Hoard 1 (ADR 0019): the upgrade in place needs it.
    // Without them the release APK is built unsigned (CI, contributors) with a warning.
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
        release {
            if (releaseSigning) signingConfig = signingConfigs.getByName("release")
            // R8 shrinks smbj's Bouncy Castle dependency (several MB); keep rules in proguard-rules.pro and the
            // core modules' consumer-rules.pro.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    // The contour model of ML Kit's face detection is not used (contour mode is off, ADR 0009): not packaged.
    androidResources.ignoreAssetsPattern = "!.svn:!.git:!.ds_store:!*.scc:.*:!CVS:!thumbs.db:!picasa.ini:!*~:!contours.tfl"
    compileOptions {
        // java.time (ISO dates of rows and listings) on minSdk 24.
        isCoreLibraryDesugaringEnabled = true
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

if (gradle.startParameter.taskNames.any { it.contains("release", ignoreCase = true) }) {
    val keystore = System.getenv("HOARD_KEYSTORE")
    if (keystore == null || !file(keystore).exists()) {
        logger.warn("Hoard: HOARD_KEYSTORE / HOARD_KEYSTORE_PASSWORD / HOARD_KEY_ALIAS not set: the release APK will be UNSIGNED. Use `bun run android:release` (skv run hoard) for a signed build.")
    }
}

/** `bun run android:release`: the release APK as hoard-<version>.apk (unsigned: hoard-<version>-unsigned.apk). */
tasks.register<Copy>("hoardReleaseApk") {
    dependsOn("assembleRelease")
    val version = android.defaultConfig.versionName
    from(layout.buildDirectory.dir("outputs/apk/release"))
    include("*.apk")
    into(layout.buildDirectory.dir("outputs/hoard"))
    rename { name -> if (name.contains("unsigned")) "hoard-$version-unsigned.apk" else "hoard-$version.apk" }
    doLast { logger.lifecycle("Hoard: ${destinationDir.listFiles()?.joinToString { it.path }}") }
}

// ML Kit face detection (ADR 0009) ships its native library for four ABIs (~33 MB uncompressed in a universal APK).
// Keep it for the ABIs libhoardimage is built for; on 32-bit and x86 devices face detection reports itself
// unavailable (analysis still runs, without faces).
android.packaging.jniLibs.excludes += listOf("lib/armeabi-v7a/libface_detector_v2_jni.so", "lib/x86/libface_detector_v2_jni.so")


dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
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
    // Syntax highlighting of the text viewer (phase 2d): per-language grammars, maintained, Apache-2.0.
    implementation(libs.highlights)
    implementation("androidx.core:core-ktx:${rootProject.extra["androidxCoreVersion"]}")
    // WebViewerActivity (:core:smb) is an AppCompatActivity: its theme comes from here.
    implementation("androidx.appcompat:appcompat:${rootProject.extra["androidxAppCompatVersion"]}")

    testImplementation("junit:junit:${rootProject.extra["junitVersion"]}")
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.truth)
    // Real org.json for the plain JVM tests (android.jar only has stubs).
    testImplementation("org.json:json:20250517")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:${rootProject.extra["coroutinesVersion"]}")
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.truth)
}
