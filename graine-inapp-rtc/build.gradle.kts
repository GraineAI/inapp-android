plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    `maven-publish`
}

// One version line with the SDK it plugs into: an app adds both at the same version.
version = providers.gradleProperty("GRAINE_VERSION").get()

android {
    namespace = "ai.graine.inapp.rtc"
    compileSdk = 36

    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
    publishing {
        // Maven Central requires sources; verifyPackage reads them before anything is published.
        singleVariant("release") { withSourcesJar() }
    }
}

/**
 * THE SAME GATE THE SDK PASSES BEFORE A PUBLIC REGISTRY (graine-inapp's
 * verifyPackage): the leak rules, over what this module actually ships — its
 * AAR and its sources jar. The WebRTC library is a dependency, not shipped
 * inside, so nothing of it is read here.
 */
val verifyPackage by tasks.registering(Exec::class) {
    description = "Refuses to publish if the AAR or the sources jar carries anything internal."
    group = "publishing"
    dependsOn("bundleReleaseAar", "releaseSourcesJar")
    val guard = file("../../scripts/verify-native-package.mjs")
    val aar = layout.buildDirectory.file("outputs/aar/graine-inapp-rtc-release.aar")
    onlyIf { guard.exists() }
    doFirst {
        val sources = tasks.named<AbstractArchiveTask>("releaseSourcesJar").get().archiveFile.get().asFile
        commandLine("node", guard.path, aar.get().asFile.path, sources.path)
    }
}

// The publication, its POM, signing and the Maven Central bundle.
apply(from = "publishing.gradle.kts")

tasks.matching { it.name.startsWith("publish") }.configureEach { dependsOn(verifyPackage) }

dependencies {
    implementation(project(":graine-inapp"))
    // WebRTC: a maintained, symbol-prefixed build of Google's libwebrtc (its classes live in
    // livekit.org.webrtc, so it never collides with another copy of WebRTC in the same app).
    // The stripped build: no software video codecs, which an audio-only call never loads.
    // Pinned: a new build is a decision, made with the size and the device checks in sdk/NATIVE-CALLS.md.
    implementation("io.github.webrtc-sdk:android-prefixed-stripped:150.7871.01")
    testImplementation("junit:junit:4.13.2")
}
