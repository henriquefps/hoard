// :core:smb (ADR 0001, 0004, 0005, 0007, 0010, 0011, ADR 0019): everything native that is not the image core or XMP
// parsing: SMB over smbj, locations (HoardFs: SmbFs, PathFs, SafFs, volumes), the transfer engine, streaming and the
// StreamProvider, share in/out, Reveal hand-off, culling's native side, credentials (Android Keystore).
// No Capacitor here: the plugin facade (HoardSmbPlugin) stays in :app. ADR 0019 says why transfer and hand-off are
// not separate modules yet (they import locations and streams, which import them back).
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.hfps.hoard.core.smb"
    compileSdk = rootProject.extra["compileSdkVersion"] as Int
    defaultConfig {
        minSdk = rootProject.extra["minSdkVersion"] as Int
        consumerProguardFiles("consumer-rules.pro")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    // Findings that came with the code from :app (where lint never ran in CI): java.nio on minSdk 24, and the
    // WorkManager service type that :app's manifest declares (a library cannot see it). New findings still fail.
    lint { baseline = file("lint-baseline.xml") }
    testOptions {
        unitTests {
            // android.* stubs return defaults instead of throwing in JVM unit tests (pure logic only).
            isReturnDefaultValues = true
            all {
                // The opt-in GUID smoke test (SmbSmokeTest) reads its targets from these variables.
                it.environment("HOARD_SMOKE_HOSTS", System.getenv("HOARD_SMOKE_HOSTS") ?: "")
                it.environment("HOARD_SMOKE_SMB", System.getenv("HOARD_SMOKE_SMB") ?: "")
                // Opt-in read-only multi-share test (SmbMultiShareTest).
                listOf("HOARD_SMOKE_SHARES", "HOARD_SMOKE_USER", "HOARD_SMOKE_PASSWORD").forEach { name ->
                    it.environment(name, System.getenv(name) ?: "")
                }
                it.testLogging { events("passed", "skipped", "failed"); showStandardStreams = true }
            }
        }
    }
}

kotlin { jvmToolchain(21) }

dependencies {
    api(project(":core:image"))
    api(project(":core:xmp"))
    api("com.hierynomus:smbj:${rootProject.extra["smbjVersion"]}")
    implementation("org.slf4j:slf4j-nop:${rootProject.extra["slf4jVersion"]}")
    implementation("androidx.core:core-ktx:${rootProject.extra["androidxCoreVersion"]}")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-android:${rootProject.extra["coroutinesVersion"]}")
    api("androidx.work:work-runtime-ktx:${rootProject.extra["workVersion"]}")
    implementation("androidx.exifinterface:exifinterface:${rootProject.extra["exifInterfaceVersion"]}")
    implementation("com.google.mlkit:face-detection:${rootProject.extra["mlkitFaceDetectionVersion"]}")

    testImplementation("junit:junit:${rootProject.extra["junitVersion"]}")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:${rootProject.extra["coroutinesVersion"]}")
    // Real org.json for JVM tests (android.jar only has stubs).
    testImplementation("org.json:json:20250517")
}
