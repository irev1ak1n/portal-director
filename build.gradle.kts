plugins {
    java
}

group = "dev.portaldirector"
version = "1.8.0"
description = "Mass Nether portal formations and TNT minecart waves for content creators."

// Pinned versions for reproducible builds.
val paperApiVersion = "26.3.build.231-stable"
val mockBukkitVersion = "4.119.0"
val junitVersion = "6.1.3"
val sqliteVersion = "3.49.1.0"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:$paperApiVersion")

    testImplementation("io.papermc.paper:paper-api:$paperApiVersion")
    testImplementation("org.mockbukkit.mockbukkit:mockbukkit-v26.3:$mockBukkitVersion")
    testImplementation(platform("org.junit:junit-bom:$junitVersion"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    // The same SQLite driver that Paper bundles, so the database tests run here exactly as they do on a server.
    testRuntimeOnly("org.xerial:sqlite-jdbc:$sqliteVersion")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(25)
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Xlint:-processing", "-Xlint:-serial"))
}

tasks.processResources {
    val props = mapOf("version" to project.version, "description" to project.description)
    inputs.properties(props)
    filteringCharset = "UTF-8"
    filesMatching("plugin.yml") {
        expand(props)
    }
}

tasks.test {
    useJUnitPlatform {
        excludeTags("benchmark")
    }
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = false
    }
}

tasks.register<Test>("benchmark") {
    description = "Times the pure parts of the plugin. Not part of the normal test run."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform {
        includeTags("benchmark")
    }
    systemProperty("bench.label", (project.findProperty("benchLabel") ?: "run").toString())
    testLogging {
        showStandardStreams = true
    }
    outputs.upToDateWhen { false }
}

tasks.jar {
    archiveBaseName.set("PortalDirector")
    manifest {
        attributes("Implementation-Title" to "PortalDirector", "Implementation-Version" to project.version)
    }
}
