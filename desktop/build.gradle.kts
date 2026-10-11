import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import java.security.MessageDigest

plugins {
    kotlin("jvm") version "2.2.20"
    kotlin("plugin.serialization") version "2.2.20"
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.20"
    id("org.jetbrains.compose") version "1.9.1"
}

group = "dev.focusduo"
version = "0.1.0"

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("io.ktor:ktor-client-core:3.3.1")
    implementation("io.ktor:ktor-client-cio:3.3.1")
    implementation("io.ktor:ktor-client-websockets:3.3.1")
    implementation("io.ktor:ktor-client-content-negotiation:3.3.1")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.3.1")
    testImplementation("io.ktor:ktor-client-mock:3.3.1")
    testImplementation("io.ktor:ktor-server-cio:3.3.1")
    testImplementation("io.ktor:ktor-server-websockets:3.3.1")
    testImplementation(kotlin("test-junit5"))
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.13.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.13.3")
}

tasks.test {
    useJUnitPlatform()
    exclude("**/LiveBackendAuthTest.class")
    exclude("**/LiveBackendRoomTest.class")
}

// Explicit, opt-in check: creates a temporary-named account on the selected real backend.
tasks.register<Test>("backendAuthSmoke") {
    description = "Checks register/login/me/current/logout against a running FocusDuo backend (no UI)."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform()
    include("**/LiveBackendAuthTest.class")
    systemProperty("focusduo.smoke.baseUrl", System.getProperty("focusduo.smoke.baseUrl") ?: "http://127.0.0.1:8090")
    outputs.upToDateWhen { false }
}

// Requires one running development backend; exercises two independent real repositories and WS.
tasks.register<Test>("backendRoomSmoke") {
    description = "Checks two Kotlin clients, rooms/tasks/rounds/history and WebSocket against the Java backend."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform()
    include("**/LiveBackendRoomTest.class")
    systemProperty("focusduo.smoke.baseUrl", System.getProperty("focusduo.smoke.baseUrl") ?: "http://127.0.0.1:8090")
    outputs.upToDateWhen { false }
}

// Explicit local test utility for real Compose UI checks; never runs with application or test tasks.
tasks.register<JavaExec>("contractUiServer") {
    description = "Starts a synthetic loopback-only REST/WS UI fixture server on 127.0.0.1:18090 (not Java backend)."
    group = "verification"
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("dev.focusduo.qa.ContractUiServer")
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) })
}

// Developer utility for reproducing the checked-in third-party notices.
val writeRuntimeLicenseInputs by tasks.registering {
    group = "verification"
    description = "Writes resolved runtime artifact metadata for the third-party notice generator."
    val outputFile = layout.buildDirectory.file("license-input/runtime-artifacts.json")
    inputs.files(configurations.runtimeClasspath)
    outputs.file(outputFile)
    doLast {
        val artifacts = configurations.runtimeClasspath.get().resolvedConfiguration.resolvedArtifacts
            .map { artifact ->
                val id = artifact.moduleVersion.id
                mapOf("group" to id.group, "name" to id.name, "version" to id.version, "file" to artifact.file.absolutePath)
            }.sortedBy { "${it["group"]}:${it["name"]}:${it["version"]}:${it["file"]}" }
        outputFile.get().asFile.apply {
            parentFile.mkdirs()
            writeText(JsonOutput.prettyPrint(JsonOutput.toJson(artifacts)) + "\n")
        }
    }
}

val verifyRuntimeLicenses by tasks.registering {
    group = "verification"
    description = "Checks that the packaged notice inventory matches every resolved runtime dependency."
    val licensesDir = layout.projectDirectory.dir("src/main/distribution-resources/common/licenses")
    inputs.files(configurations.runtimeClasspath)
    inputs.dir(licensesDir)
    doLast {
        val manifestFile = licensesDir.file("manifest.json").asFile
        check(manifestFile.isFile) { "Missing third-party license manifest: $manifestFile" }
        val manifest = JsonSlurper().parse(manifestFile) as Map<*, *>
        val expected = manifest["artifacts"] as? List<*>
            ?: error("The third-party license manifest must contain artifacts.")
        fun key(group: Any?, name: Any?, version: Any?, jar: Any?, sha256: Any?) =
            "$group:$name:$version:$jar:$sha256"
        val expectedKeys = expected.map { raw ->
            val artifact = raw as Map<*, *>
            val notices = artifact["notices"] as? List<*>
                ?: error("Missing notices list for ${artifact["name"]}")
            check(notices.isNotEmpty()) { "No packaged notices for ${artifact["name"]}" }
            notices.forEach { notice ->
                val root = licensesDir.asFile.toPath().toAbsolutePath().normalize()
                val path = root.resolve(notice.toString()).normalize()
                check(path.startsWith(root) && path.toFile().isFile) { "Missing packaged notice: $notice" }
            }
            key(artifact["group"], artifact["name"], artifact["version"], artifact["jar"], artifact["sha256"])
        }.toSet()
        val actualKeys = configurations.runtimeClasspath.get().resolvedConfiguration.resolvedArtifacts.map { artifact ->
            val id = artifact.moduleVersion.id
            val sha256 = MessageDigest.getInstance("SHA-256").digest(artifact.file.readBytes())
                .joinToString("") { "%02x".format(it) }
            key(id.group, id.name, id.version, artifact.file.name, sha256)
        }.toSet()
        check(expectedKeys == actualKeys) {
            "Runtime dependencies changed. Regenerate third-party notices before packaging. " +
                "Missing from manifest: ${actualKeys - expectedKeys}; stale manifest entries: ${expectedKeys - actualKeys}"
        }
        logger.lifecycle("Verified packaged notices for ${actualKeys.size} runtime artifacts.")
    }
}

tasks.check { dependsOn(verifyRuntimeLicenses) }
tasks.matching { it.name in setOf("createDistributable", "packageMsi") }.configureEach {
    dependsOn(verifyRuntimeLicenses)
}

compose.desktop {
    application {
        mainClass = "dev.focusduo.MainKt"
        // Packaging must use JDK 21, even when a different JDK started Gradle.
        // package-windows.ps1 pins this to its validated Windows x64 JDK.
        javaHome = providers.gradleProperty("focusduo.javaHome").orElse(providers.provider {
            javaToolchains.launcherFor {
                languageVersion.set(JavaLanguageVersion.of(21))
            }.get().metadata.installationPath.asFile.absolutePath
        }).get()

        nativeDistributions {
            targetFormats(TargetFormat.Msi)
            packageName = "FocusDuo"
            packageVersion = "0.1.0"
            // jpackage/WiX emits an en-US MSI: keep metadata within its code page.
            description = "FocusDuo shared focus sessions for two users"
            vendor = "KDvibers"
            includeAllModules = true
            appResourcesRootDir.set(layout.projectDirectory.dir("src/main/distribution-resources"))

            windows {
                iconFile.set(project.file("src/main/resources/icons/focusduo.ico"))
                shortcut = true
                menu = true
                menuGroup = "FocusDuo"
                console = false
                perUserInstall = true
                dirChooser = true
                // Product identity: keep unchanged when packageVersion is increased.
                upgradeUuid = "10aee174-af1c-4dc4-9705-bec80b54639c"
            }
        }
    }
}

tasks.wrapper {
    gradleVersion = "8.13"
    distributionType = Wrapper.DistributionType.BIN
    distributionSha256Sum = "20f1b1176237254a6fc204d8434196fa11a4cfb387567519c61556e8710aed78"
}
