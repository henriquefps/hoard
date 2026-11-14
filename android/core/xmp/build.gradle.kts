// :core:xmp (ADR 0011, ADR 0019): XMP sidecars read, merged and written; pure Kotlin over the JDK's XML APIs.
// Sidecars.kt (sidecar I/O over the transfer engine's TransferFs) lives in :core:smb, next to TransferFs.
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.hfps.hoard.core.xmp"
    compileSdk = rootProject.extra["compileSdkVersion"] as Int
    defaultConfig { minSdk = rootProject.extra["minSdkVersion"] as Int }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    testOptions.unitTests.isReturnDefaultValues = true
}

kotlin { jvmToolchain(21) }

dependencies {
    testImplementation("junit:junit:${rootProject.extra["junitVersion"]}")
}
