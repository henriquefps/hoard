// :core:server (ADR 0021): the hoard-server client of Hoard 2's Gallery. Pairing (ADR 0013) and device identity
// (ADR 0017), the API key in the Android Keystore, the items feed with culling records (ADR 0014, 0016) mirrored into
// a Room cache (`gallery.db`, apart from Hoard 1's schema in :core:data), the culling push queue (ADR 0016 "Client
// rules"), previews (ADR 0015, 0020) in a bounded disk cache and originals read by HTTP range for the image core.
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}

android {
    namespace = "dev.hfps.hoard.server"
    compileSdk = rootProject.extra["compileSdkVersion"] as Int
    defaultConfig {
        minSdk = rootProject.extra["minSdkVersion"] as Int
        consumerProguardFiles("consumer-rules.pro")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    // The fingerprint vectors the server and Reveal check (server/testdata/fingerprint-vectors.txt, ADR 0013 "Tests").
    sourceSets["test"].resources.srcDir("../../../server/testdata")
    testOptions.unitTests.isIncludeAndroidResources = true
    testOptions.unitTests.all {
        it.testLogging { events("passed", "skipped", "failed") }
        // The JDK's HttpURLConnection refuses PATCH by name (Android's takes it): ServerApi sets the method field.
        it.jvmArgs("--add-opens", "java.base/java.net=ALL-UNNAMED", "--add-opens", "java.base/sun.net.www.protocol.http=ALL-UNNAMED")
    }
}

kotlin { jvmToolchain(21) }

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.generateKotlin", "true")
}

dependencies {
    api(project(":core:image"))
    api(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    api(libs.kotlinx.serialization.json)
    api("org.jetbrains.kotlinx:kotlinx-coroutines-android:${rootProject.extra["coroutinesVersion"]}")
    api("androidx.work:work-runtime-ktx:${rootProject.extra["workVersion"]}")

    testImplementation("junit:junit:${rootProject.extra["junitVersion"]}")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:${rootProject.extra["coroutinesVersion"]}")
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.truth)
}
