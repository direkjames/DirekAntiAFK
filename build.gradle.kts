// dkAFK - AFK detection for the dk plugin suite. Author: direk james
//
//   ./gradlew build   -> build/libs/dkAFK-<version>.jar (put on the server next to dkCore)
//
// Needs dkCore in your local Maven repo first: in the dkCore project run
//   ./gradlew publishToMavenLocal

plugins {
    java
}

group = "com.direk"
version = "2.0.0"

repositories {
    mavenLocal()
    mavenCentral()
    maven("https://repo.purpurmc.org/snapshots")
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.extendedclip.com/releases/") {
        name = "placeholderapi"
    }
}

dependencies {
    compileOnly("org.purpurmc.purpur:purpur-api:${property("purpurVersion")}")
    // Never shade dkCore: it is its own plugin on the server.
    compileOnly("com.direk:dkcore:${property("dkcoreVersion")}")
    compileOnly("me.clip:placeholderapi:2.12.3")

    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(25)
}

tasks.test {
    useJUnitPlatform()
}

tasks.processResources {
    val props = mapOf("version" to project.version.toString())
    inputs.properties(props)
    filesMatching("plugin.yml") {
        expand(props)
    }
}

tasks.jar {
    archiveFileName.set("dkAFK-${project.version}.jar")
}
