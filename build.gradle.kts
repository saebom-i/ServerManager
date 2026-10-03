plugins {
    kotlin("jvm") version "2.2.21"
    kotlin("plugin.spring") version "2.2.21"
    id("org.springframework.boot") version "3.5.16"
    id("io.spring.dependency-management") version "1.1.7"
}
group = "local.servermanager"
version = file("VERSION").readText().trim()
repositories { mavenCentral() }
kotlin { jvmToolchain(21) }
dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("com.yubico:webauthn-server-core:2.10.0")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation(kotlin("reflect"))
    implementation("org.tomlj:tomlj:1.1.1")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation(kotlin("test"))
    testImplementation("org.springframework.security:spring-security-test")
}
tasks.test { useJUnitPlatform() }

tasks.processResources { inputs.file("VERSION"); doLast { destinationDir.resolve("app-version.txt").writeText(project.version.toString()) } }
