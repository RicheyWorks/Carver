plugins {
    `java-library`
    alias(libs.plugins.jmh)   // measure phase: ./gradlew jmh (benchmarks in src/jmh/java)
}

group = "io.github.richeyworks"
version = "0.1.0"

java {
    withSourcesJar()
}

// Mirror the siblings: 17-target bytecode from whatever JDK runs Gradle (Gradle 9 needs 17+).
tasks.withType<JavaCompile>().configureEach {
    options.release = 17
}

dependencies {
    // Resolved to the live sibling sources via the composite build in settings.gradle.kts
    // (smokehouse api-exposes csrbt-core and superbeefsort, so all three ride along).
    api("io.github.richeyworks:smokehouse:0.1.0")

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
    // csrbt-core logs via log4j-api with no backend on the classpath; keep tests quiet.
    systemProperty("log4j2.loggerContextFactory",
            "org.apache.logging.log4j.simple.SimpleLoggerContextFactory")
    systemProperty("org.apache.logging.log4j.simplelog.StatusLogger.level", "OFF")
}

// The measure rig (mirrors the siblings): plan quality vs oracle-best access path.
// Run: ./gradlew jmh   (results at build/reports/jmh/results.json)
val jmhVer = libs.versions.jmh.asProvider().get()

jmh {
    jmhVersion = jmhVer
    fork = 1
    warmupIterations = 3
    iterations = 5
    resultFormat = "JSON"
    resultsFile = layout.buildDirectory.file("reports/jmh/results.json")
    // Store setup logs via log4j-api; keep benchmark stdout clean.
    jvmArgs.add("-Dlog4j2.loggerContextFactory="
            + "org.apache.logging.log4j.simple.SimpleLoggerContextFactory")
    jvmArgs.add("-Dorg.apache.logging.log4j.simplelog.StatusLogger.level=OFF")
}

// The jmh plugin doesn't hook the jmh source set into `build`/`check`, so a compile
// break in a benchmark would only surface at the next manual jmh run. Feed it in.
// (Mirrors csrbt-benchmarks, SuperBeefSort, and SmokeHouse.)
tasks.named("check") { dependsOn(tasks.named("compileJmhJava")) }
