import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// The sample's publishable key is never committed: it comes from local.properties
// (graine.publishableKey=pk_live_…, git-ignored) or GRAINE_PUBLISHABLE_KEY.
val publishableKey: String = run {
    val props = Properties()
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { props.load(it) }
    props.getProperty("graine.publishableKey") ?: System.getenv("GRAINE_PUBLISHABLE_KEY") ?: ""
}

android {
    namespace = "ai.graine.sample"
    compileSdk = 36
    defaultConfig {
        applicationId = "ai.graine.sample"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "0.6.1"
        buildConfigField("String", "GRAINE_PUBLISHABLE_KEY", "\"$publishableKey\"")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { buildConfig = true }
}

dependencies {
    implementation(project(":graine-inapp"))
}
