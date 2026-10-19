plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    `maven-publish`
}

version = providers.gradleProperty("GRAINE_VERSION").get()

android {
    namespace = "ai.graine.inapp"
    compileSdk = 36

    defaultConfig {
        // 24 (Android 7): the first release whose system WebView reliably runs
        // getUserMedia and WebRTC, which the hosted engine needs.
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
        // The live WebRTC call on a device (src/androidTest/RtcLiveRunner.kt): an instrumentation of its own,
        // with no test library behind it, run by hand — each run is a real call.
        testInstrumentationRunner = "ai.graine.inapp.RtcLiveRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
        // GraineListener's empty callbacks become real Java default methods, so a
        // Java app overrides only the ones it wants — as a Kotlin app does.
        freeCompilerArgs += listOf("-Xjvm-default=all")
    }
    testOptions {
        // The test APK as an app of today's: edge to edge, and no older-app warning over the call screen's gallery.
        targetSdk = 36
        unitTests.isReturnDefaultValues = true
        unitTests.all {
            it.systemProperty("graine.version", project.version.toString())
            // The contract tests read these at run time. Declared, so a regenerated
            // fixture re-runs them instead of Gradle reporting the old result as current.
            // contract/ beside this build in the standalone repository, ../contract in the monorepo.
            listOf(rootProject.file("contract"), rootProject.file("../contract")).firstOrNull { d -> d.isDirectory }
                ?.let { d -> it.inputs.dir(d).withPathSensitivity(PathSensitivity.RELATIVE) }
        }
    }
    // The test APK reads the contract (CallScreenGalleryActivity draws design.json's designs). Never in the AAR.
    sourceSets.getByName("androidTest").assets.srcDirs(
        listOf(rootProject.file("contract"), rootProject.file("../contract")).filter { it.isDirectory },
    )
    publishing {
        // Maven Central requires sources; they ship with every comment in them,
        // which is why verifyPackage reads them before anything is published.
        singleVariant("release") { withSourcesJar() }
    }
}

/**
 * THE GATE BETWEEN THIS CODE AND A PUBLIC REGISTRY — the same rules the npm
 * package is held to (sdk/scripts/leak-rules.mjs), run over what actually
 * ships: the AAR and the sources jar. Every publish task depends on it, so it
 * is not a step someone is trusted to remember on release day.
 */
val verifyPackage by tasks.registering(Exec::class) {
    description = "Refuses to publish if the AAR or the sources jar carries anything internal."
    group = "publishing"
    dependsOn("bundleReleaseAar", "releaseSourcesJar")
    val guard = file("../../scripts/verify-native-package.mjs")
    val aar = layout.buildDirectory.file("outputs/aar/graine-inapp-release.aar")
    // The guard lives in the monorepo. A standalone checkout was guarded when it was
    // exported (sdk/scripts/publish-sdk-repo.sh), so there is nothing to run it with.
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
    // Deliberately nothing at runtime beyond the platform: no OkHttp, no
    // coroutines, no AndroidX. An SDK that drags a dependency tree into a
    // bank's app is an SDK that fails a security review before anyone runs it.
    //
    // Firebase Cloud Messaging only to COMPILE the SDK's messaging service
    // against (GraineMessagingService.kt): never in the AAR's dependencies. An
    // app that rings adds Firebase itself; one that does not never loads the
    // service (FirebasePush.kt checks for Firebase first). The React Native and
    // Flutter builds compile the same file against this same version.
    compileOnly("com.google.firebase:firebase-messaging:24.1.0")
    testImplementation("junit:junit:4.13.2")
    // org.json is part of Android, but stubbed in JVM unit tests; the real one here.
    testImplementation("org.json:json:20240303")
    // The live call on a device needs the WebRTC module; the SDK itself never does.
    androidTestImplementation(project(":graine-inapp-rtc"))
}
