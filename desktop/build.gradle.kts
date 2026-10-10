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
    implementation("io.ktor:ktor-client-content-negotiation:3.3.1")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.3.1")
    testImplementation("io.ktor:ktor-client-mock:3.3.1")
    testImplementation(kotlin("test-junit5"))
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.13.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.13.3")
}

tasks.test {
    useJUnitPlatform()
    exclude("**/LiveBackendAuthTest.class")
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

compose.desktop {
    application {
        mainClass = "dev.focusduo.MainKt"
    }
}

tasks.wrapper {
    gradleVersion = "8.13"
    distributionType = Wrapper.DistributionType.BIN
    distributionSha256Sum = "20f1b1176237254a6fc204d8434196fa11a4cfb387567519c61556e8710aed78"
}
