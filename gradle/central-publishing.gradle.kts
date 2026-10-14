/**
 * HOW A GRAINE LIBRARY REACHES MAVEN CENTRAL. The Android SDK and the Kotlin
 * Multiplatform SDK both publish through this one file, so every ai.graine
 * artifact carries the same metadata, javadoc jars and signatures, and a build
 * leaves as one bundle however many modules it has.
 *
 * A library sets these on its project, then applies this file:
 *   extra["graine.pomName"]          the name Maven Central shows
 *   extra["graine.pomDescription"]   one sentence
 *   extra["graine.docsUrl"]          its guide on www.graine.ai
 *   extra["graine.scmUrl"]           optional: its public repository, written out in full
 *   extra["graine.androidComponent"], extra["graine.artifactId"]
 *                                    an Android library: the AGP component to publish, under
 *                                    that artifactId. A Kotlin Multiplatform library already
 *                                    has its publications (the root module and one per
 *                                    target), named after its project.
 *
 *   ./gradlew publishToMavenLocal    into ~/.m2, for an app built on this machine
 *   ./gradlew mavenCentralBundle     every module's publications, signed, staged and zipped
 *                                    as build/central/central-bundle.zip; uploaded by
 *                                    sdk/scripts/publish-maven-central.sh
 *
 * Signing reads an ASCII-armoured secret key from Gradle properties — the
 * publisher's own ~/.gradle/gradle.properties, or ORG_GRADLE_PROJECT_<name> in
 * the environment — never from a repository:
 *   signingInMemoryKey, signingInMemoryKeyPassword, signingInMemoryKeyId (optional)
 * Without a key everything still builds and publishes locally, unsigned; only
 * the Central bundle refuses, because Central rejects an unsigned file.
 */
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.publish.maven.tasks.AbstractPublishToMaven
import org.gradle.plugins.signing.Sign
import org.gradle.plugins.signing.SigningExtension

apply(plugin = "maven-publish")
apply(plugin = "signing")

fun setting(name: String): String? = if (extra.has("graine.$name")) extra["graine.$name"]?.toString() else null
fun required(name: String): String =
    setting(name) ?: throw GradleException("${project.path}: set extra[\"graine.$name\"] before applying central-publishing.gradle.kts")

group = "ai.graine"
val pomName = required("pomName")
val pomDescription = required("pomDescription")
val docsUrl = required("docsUrl")
val scmUrl = setting("scmUrl")
val publishing = extensions.getByType<PublishingExtension>()

// One staging repository for the whole build, so one bundle carries every module.
val stagingDir: File = rootProject.layout.buildDirectory.dir("central-staging").get().asFile
publishing.repositories.maven { name = "centralStaging"; url = uri(stagingDir) }

// An Android library's publication comes from AGP's component, which exists
// once AGP has configured its variants.
setting("androidComponent")?.let { component ->
    val artifact = required("artifactId")
    afterEvaluate {
        publishing.publications.register<MavenPublication>("release") {
            from(components[component])
            artifactId = artifact
        }
    }
}

// Central requires a javadoc jar beside every jar, aar or klib, and accepts one
// that points to the documentation; the API reference lives on the docs site.
val javadocReadme = tasks.register("centralJavadocReadme") {
    val readme = layout.buildDirectory.file("central-javadoc/README.md")
    outputs.file(readme)
    doLast { readme.get().asFile.writeText("# $pomName\n\nThe API reference and the integration guide: $docsUrl\n") }
}

publishing.publications.withType<MavenPublication>().configureEach {
    val publication = name
    // One jar per publication: a jar shared by several would be signed by
    // several tasks into the same .asc.
    val javadocJar = tasks.register<Jar>("${publication}CentralJavadocJar") {
        archiveClassifier.set("javadoc")
        archiveBaseName.set("${project.name}-$publication")
        from(javadocReadme)
    }
    artifact(javadocJar)
    pom {
        name.set(pomName)
        description.set(pomDescription)
        url.set(docsUrl)
        inceptionYear.set("2026")
        licenses { license { name.set("MIT"); url.set("https://opensource.org/licenses/MIT"); distribution.set("repo") } }
        organization { name.set("Graine AI"); url.set("https://www.graine.ai") }
        developers {
            developer {
                id.set("graineai")
                name.set("Graine AI")
                email.set("support@graine.ai")
                organization.set("Graine AI")
                organizationUrl.set("https://www.graine.ai")
            }
        }
        // Central requires the element, not that the repository be public.
        // The URL is the library's own, written out in full where it is set: the
        // leak guard allows only our exact public repositories, never a template.
        scm {
            if (scmUrl != null) {
                url.set(scmUrl)
                connection.set("scm:git:$scmUrl.git")
                developerConnection.set("scm:git:" + scmUrl.replaceFirst("https://", "ssh://git@") + ".git")
            } else {
                url.set(docsUrl)
            }
        }
        issueManagement { system.set("Graine support"); url.set("https://www.graine.ai/support") }
    }
}

val signingKey = providers.gradleProperty("signingInMemoryKey").orNull
if (signingKey != null) {
    configure<SigningExtension> {
        val password = providers.gradleProperty("signingInMemoryKeyPassword").orNull ?: ""
        val keyId = providers.gradleProperty("signingInMemoryKeyId").orNull
        if (keyId != null) useInMemoryPgpKeys(keyId, signingKey, password) else useInMemoryPgpKeys(signingKey, password)
        // Every publication, including one registered later (the Android one, above).
        sign(publishing.publications)
    }
    // A multiplatform build's publications reference each other's files; every
    // publish waits for every signature, or Gradle stops on an undeclared dependency.
    tasks.withType<AbstractPublishToMaven>().configureEach { dependsOn(tasks.withType<Sign>()) }
}

// Stage from clean, once per build: a file left by an earlier run (another
// version, an unsigned build) must never reach a bundle.
val cleanStaging = if ("cleanCentralStaging" in rootProject.tasks.names) rootProject.tasks.named("cleanCentralStaging")
    else rootProject.tasks.register<Delete>("cleanCentralStaging") { delete(stagingDir) }
tasks.matching { it.name.endsWith("ToCentralStagingRepository") }.configureEach { dependsOn(cleanStaging) }

if ("mavenCentralBundle" !in rootProject.tasks.names) {
    val bundleDir = rootProject.layout.buildDirectory.dir("central")
    val zip = rootProject.tasks.register<Zip>("zipMavenCentralBundle") {
        description = "Zips every staged publication of this build in the Maven layout the Central Portal takes."
        from(stagingDir) { exclude("**/maven-metadata.xml*") }
        destinationDirectory.set(bundleDir)
        archiveFileName.set("central-bundle.zip")
        doFirst { checkStaged(stagingDir) }
    }
    // The bundle as uploaded, read by the leak guard every SDK package passes
    // (sdk/scripts/verify-native-package.mjs). The guard lives in the monorepo;
    // an exported checkout was guarded when it was exported.
    rootProject.tasks.register<Exec>("mavenCentralBundle") {
        description = "Builds the signed Maven Central bundle of every module and refuses it if anything internal is inside."
        group = "publishing"
        dependsOn(zip)
        val guard = rootProject.file("../scripts/verify-native-package.mjs")
        onlyIf { guard.exists() }
        doFirst { commandLine("node", guard.path, zip.get().archiveFile.get().asFile.path) }
    }
}
val stageThisProject = tasks.named("publishAllPublicationsToCentralStagingRepository")
rootProject.tasks.named("zipMavenCentralBundle") { dependsOn(stageThisProject) }

/**
 * What Central validates, checked before anything is uploaded: in every
 * artifact folder a POM, a sources and a javadoc jar beside a jar, aar or klib,
 * and a signature, an MD5 and a SHA-1 beside every file; one version for the build.
 */
fun checkStaged(root: File) {
    val problems = mutableListOf<String>()
    val versions = mutableSetOf<String>()
    val checksum = Regex("""\.(asc|md5|sha1|sha256|sha512)$""")
    val folders = root.walkTopDown().filter { d -> d.isDirectory && d.listFiles()!!.any { it.name.endsWith(".pom") } }.toList()
    if (folders.isEmpty()) problems += "nothing is staged"
    for (dir in folders) {
        val version = dir.name
        val artifact = dir.parentFile.name
        versions += version
        val files = dir.listFiles()!!.map { it.name }.toSet()
        val base = "$artifact-$version"
        if ("$base.pom" !in files) problems += "${dir.relativeTo(root)}: no $base.pom"
        val primaries = files.filter { !checksum.containsMatchIn(it) }
        if (primaries.any { Regex("""\.(jar|aar|klib)$""").containsMatchIn(it) && !it.endsWith("-sources.jar") && !it.endsWith("-javadoc.jar") }) {
            for (needed in listOf("$base-sources.jar", "$base-javadoc.jar")) if (needed !in files) problems += "${dir.relativeTo(root)}: no $needed"
        }
        for (f in primaries) for (ext in listOf("asc", "md5", "sha1")) {
            if ("$f.$ext" !in files) problems += "${dir.relativeTo(root)}/$f: no .$ext"
        }
    }
    if (versions.size > 1) problems += "more than one version staged: ${versions.sorted().joinToString()}"
    if (problems.isNotEmpty()) {
        val unsigned = problems.any { it.endsWith("no .asc") }
        throw GradleException(
            "The bundle is not ready for Central:\n  " + problems.joinToString("\n  ") +
                (if (unsigned) "\nCentral rejects unsigned files: set signingInMemoryKey (and signingInMemoryKeyPassword) in ~/.gradle/gradle.properties." else ""),
        )
    }
}
