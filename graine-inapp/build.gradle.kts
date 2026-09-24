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

afterEvaluate {
    publishing {
        publications {
            register<MavenPublication>("release") {
                from(components["release"])
                groupId = "ai.graine"
                artifactId = "inapp-android"
                version = project.version.toString()
                pom {
                    name.set("Graine in-app agent for Android")
                    description.set("An AI agent inside your Android app: it sees the screen, acts in the app, shows components and talks.")
                    url.set("https://www.graine.ai/docs/in-app/android")
                    licenses { license { name.set("MIT"); url.set("https://opensource.org/licenses/MIT") } }
                    developers { developer { id.set("graineai"); name.set("Graine.AI"); url.set("https://www.graine.ai") } }
                    scm { url.set("https://www.graine.ai/docs/in-app/android") }
                }
            }
        }
        repositories {
            // A local staging repository. Uploading it to Maven Central is a release step for the owner.
            maven { name = "staging"; url = uri(layout.buildDirectory.dir("staging-repo")) }
        }
    }
}

tasks.matching { it.name.startsWith("publish") }.configureEach { dependsOn(verifyPackage) }

dependencies {
    // Deliberately nothing at runtime beyond the platform: no OkHttp, no
    // coroutines, no AndroidX. An SDK that drags a dependency tree into a
    // bank's app is an SDK that fails a security review before anyone runs it.
    testImplementation("junit:junit:4.13.2")
    // org.json is part of Android, but stubbed in JVM unit tests; the real one here.
    testImplementation("org.json:json:20240303")
}
