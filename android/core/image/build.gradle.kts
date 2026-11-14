// :core:image (ADR 0006, 0008, 0009, ADR 0019): the image core. LibRaw + JNI (src/main/cpp, built by
// image-native.gradle), decoders, caches, culling model, assisted-culling measurements (ML Kit faces).
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.hfps.hoard.core.image"
    compileSdk = rootProject.extra["compileSdkVersion"] as Int
    defaultConfig {
        minSdk = rootProject.extra["minSdkVersion"] as Int
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    testOptions.unitTests.all {
        it.testLogging { events("passed", "skipped", "failed"); showStandardStreams = true }
    }
    testOptions.unitTests.isReturnDefaultValues = true
}

kotlin { jvmToolchain(21) }

apply(from = "image-native.gradle")

dependencies {
    implementation("androidx.core:core-ktx:${rootProject.extra["androidxCoreVersion"]}")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:${rootProject.extra["coroutinesVersion"]}")
    implementation("androidx.exifinterface:exifinterface:${rootProject.extra["exifInterfaceVersion"]}")
    // SmbFileSource reads RAW files straight from an smbj File.
    implementation("com.hierynomus:smbj:${rootProject.extra["smbjVersion"]}")
    // Assisted culling (ADR 0009): face + eye detection with the bundled model (no Play services).
    implementation("com.google.mlkit:face-detection:${rootProject.extra["mlkitFaceDetectionVersion"]}")

    testImplementation("junit:junit:${rootProject.extra["junitVersion"]}")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:${rootProject.extra["coroutinesVersion"]}")
    testImplementation("org.json:json:20250517")
    androidTestImplementation("androidx.test.ext:junit:${rootProject.extra["androidxJunitVersion"]}")
    androidTestImplementation(libs.androidx.test.runner)
}
