// :core:data (ADR 0019): the native app's database. Room, with the Capacitor app's schema (src/db/migrations.ts, v7)
// reproduced table for table, plus the one-time importer of the Capacitor database file (hoardSQLite.db).
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

android {
    namespace = "dev.hfps.hoard.data"
    compileSdk = rootProject.extra["compileSdkVersion"] as Int
    defaultConfig {
        minSdk = rootProject.extra["minSdkVersion"] as Int
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    // Capacitor databases made by the real JS migrations (scripts/legacy-db/fixtures.ts): JVM tests read them as
    // resources, the instrumented importer test as assets.
    sourceSets["test"].resources.srcDir("fixtures")
    sourceSets["androidTest"].assets.srcDir("fixtures")
    testOptions.unitTests.isIncludeAndroidResources = true
    testOptions.unitTests.all {
        it.testLogging { events("passed", "skipped", "failed"); showStandardStreams = true }
    }
}

kotlin { jvmToolchain(21) }

ksp {
    // Room's schema history: every future migration of the native database is checked against these files.
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.generateKotlin", "true")
}

dependencies {
    api(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    api("org.jetbrains.kotlinx:kotlinx-coroutines-android:${rootProject.extra["coroutinesVersion"]}")

    testImplementation("junit:junit:${rootProject.extra["junitVersion"]}")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:${rootProject.extra["coroutinesVersion"]}")
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.truth)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.truth)
    androidTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:${rootProject.extra["coroutinesVersion"]}")
}
