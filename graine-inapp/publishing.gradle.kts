/**
 * THE ANDROID SDK ON MAVEN CENTRAL — what it is called and where its guide is.
 * The publication, its javadoc jar, the signatures and the Central bundle are
 * the shared convention every Graine library publishes through:
 * central-publishing.gradle.kts, in sdk/gradle in the monorepo and in gradle/
 * in the exported repository.
 *
 *   ./gradlew :graine-inapp:publishToMavenLocal    into ~/.m2, for an app built on this machine
 *   ./gradlew mavenCentralBundle                   the signed bundle, in build/central
 */
extra["graine.androidComponent"] = "release"
extra["graine.artifactId"] = "inapp-android"
extra["graine.pomName"] = "Graine in-app agent for Android"
extra["graine.pomDescription"] =
    "An AI agent inside your Android app: it sees the screen, acts in the app, shows components, talks, and takes calls."
extra["graine.docsUrl"] = "https://www.graine.ai/docs/in-app/android"
extra["graine.scmUrl"] = "https://github.com/GraineAI/inapp-android"

apply(
    from = listOf(rootProject.file("gradle/central-publishing.gradle.kts"), rootProject.file("../gradle/central-publishing.gradle.kts"))
        .firstOrNull { it.isFile }
        ?: throw GradleException("central-publishing.gradle.kts is in neither gradle/ nor ../gradle/"),
)
