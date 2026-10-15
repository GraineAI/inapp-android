/**
 * THE ANDROID SDK'S WEBRTC MODULE ON MAVEN CENTRAL — what it is called and
 * where its guide is. Published through the same convention as the SDK
 * (central-publishing.gradle.kts), so one bundle carries both.
 *
 *   ./gradlew :graine-inapp-rtc:publishToMavenLocal    into ~/.m2, beside the SDK
 *   ./gradlew mavenCentralBundle                       every module, signed, in build/central
 */
extra["graine.androidComponent"] = "release"
extra["graine.artifactId"] = "inapp-android-rtc"
extra["graine.pomName"] = "Graine in-app agent for Android: WebRTC calls"
extra["graine.pomDescription"] =
    "WebRTC for the Graine Android SDK's calls: add it beside ai.graine:inapp-android and calls carry their audio over WebRTC."
extra["graine.docsUrl"] = "https://www.graine.ai/docs/in-app/android"
extra["graine.scmUrl"] = "https://github.com/GraineAI/inapp-android"

apply(
    from = listOf(rootProject.file("gradle/central-publishing.gradle.kts"), rootProject.file("../gradle/central-publishing.gradle.kts"))
        .firstOrNull { it.isFile }
        ?: throw GradleException("central-publishing.gradle.kts is in neither gradle/ nor ../gradle/"),
)
