// :core:designsystem (ADR 0019): the hfps identity in Compose, from hfps-kit's android templates (Reveal's
// :core:designsystem is the reference). Tokens for the five palettes are generated (scripts/theme/generate.sh), icons
// too (scripts/icons/generate_icons.py). Leaf module: no project dependencies.
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("io.github.takahirom.roborazzi")
}

android {
    namespace = "dev.hfps.hoard.designsystem"
    compileSdk = rootProject.extra["compileSdkVersion"] as Int
    defaultConfig { minSdk = rootProject.extra["minSdkVersion"] as Int }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    buildFeatures { compose = true }
    testOptions.unitTests.isIncludeAndroidResources = true // Robolectric screenshots need the resources
}

kotlin { jvmToolchain(21) }

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation("androidx.core:core-ktx:${rootProject.extra["androidxCoreVersion"]}")
    api(libs.androidx.compose.ui)
    api(libs.androidx.compose.foundation)
    api(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
