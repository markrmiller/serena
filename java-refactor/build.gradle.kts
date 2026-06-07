plugins {
    application
}

group = "io.serena"
version = "0.1.0"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

application {
    mainClass.set("io.serena.javarefactor.protocol.Main")
}

dependencies {
    // JUnit 5 for the sidecar's own JVM unit tests (e.g. XML-parser hardening). Resolved from the mavenCentral
    // repository configured in settings.gradle.kts; not bundled into the runtime jar.
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.3")
}

tasks.test {
    useJUnitPlatform()
}

tasks.jar {
    archiveBaseName.set("serena-java-refactor")
    // Reproducible output: identical source + JDK yields byte-identical jars, so the bundled wheel resource can be
    // diffed against a fresh build to detect stale bytecode.
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    manifest {
        attributes("Main-Class" to application.mainClass.get())
    }
}

// Copies the freshly built sidecar jar into the Python package resources so the wheel bundles current bytecode, then
// refreshes the committed source->jar fingerprint so the bundled jar can be verified stale-free without a JDK.
// Used by the packaging build hook and as a developer/release convenience: `gradle -p java-refactor syncResourceJar`.
tasks.register<Copy>("syncResourceJar") {
    dependsOn(tasks.jar)
    from(tasks.jar.flatMap { it.archiveFile })
    into(project.file("../src/serena/resources/java-refactor"))
    rename { "serena-java-refactor.jar" }
    doLast {
        val python = listOf("python3", "python").firstOrNull { exe ->
            try {
                ProcessBuilder(exe, "--version").redirectErrorStream(true).start().waitFor() == 0
            } catch (e: Exception) {
                false
            }
        } ?: throw GradleException(
            "python3/python is required to refresh the sidecar source fingerprint; run "
                + "`python -m serena.java_refactor._sidecar_fingerprint` manually after building the jar."
        )
        // Run the module as a FILE (not `-m`) so the heavy `serena.java_refactor` package __init__ (sensai/LSP imports)
        // is bypassed — the fingerprint script depends only on the Python standard library.
        exec {
            workingDir = project.file("..")
            commandLine(python, project.file("../src/serena/java_refactor/_sidecar_fingerprint.py").absolutePath)
        }
    }
}
