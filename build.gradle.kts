plugins {
    `java-library`
}

group = providers.gradleProperty("group").get()
version = providers.gradleProperty("version").get()

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
    withSourcesJar()
}

repositories {
    mavenCentral()
    maven {
        url = uri("https://josm.openstreetmap.de/repository/releases/")
    }
}

sourceSets {
    create("tools") {
        java.srcDir("src/tools/java")
        compileClasspath += sourceSets["main"].output + configurations["compileClasspath"]
        runtimeClasspath += output + compileClasspath + configurations["runtimeClasspath"]
    }
}

dependencies {
    compileOnly("org.openstreetmap.josm:josm:${providers.gradleProperty("josmVersion").get()}")
    testImplementation("org.openstreetmap.josm:josm:${providers.gradleProperty("josmVersion").get()}")
    "toolsImplementation"("org.openstreetmap.josm:josm:${providers.gradleProperty("josmVersion").get()}")

    testImplementation(platform("org.junit:junit-bom:5.12.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(17)
}

tasks.test {
    useJUnitPlatform()
}

tasks.register<JavaExec>("extractJosmTmsCache") {
    group = "tools"
    description = "Extracts selected tiles from a JOSM TMS_BLOCK_v2 cache directory"
    classpath = sourceSets["tools"].runtimeClasspath
    mainClass.set("org.openstreetmap.josm.plugins.wayheatmaptracer.tools.JosmTileCacheExtractor")
}

tasks.register<JavaExec>("v022Replay") {
    group = "verification"
    description = "Runs strict offline Format-15 production replay from a manifest"
    classpath = sourceSets["tools"].runtimeClasspath
    mainClass.set("org.openstreetmap.josm.plugins.wayheatmaptracer.v022.V022ReplayTool")
}

tasks.register<JavaExec>("v022GenerateFixtures") {
    group = "verification"
    description = "Generates the deterministic public v0.22 analytic-fixture manifest"
    classpath = sourceSets["tools"].runtimeClasspath
    mainClass.set("org.openstreetmap.josm.plugins.wayheatmaptracer.v022.V022FixtureDescriptionTool")
    args("--output", layout.buildDirectory.file("v022/synthetic/manifest.json").get().asFile.absolutePath)
    outputs.file(layout.buildDirectory.file("v022/synthetic/manifest.json"))
}

tasks.jar {
    archiveBaseName.set("wayheatmaptracer")
    archiveVersion.set("")
    manifest {
        attributes(
            "Manifest-Version" to "1.0",
            "Plugin-Class" to providers.gradleProperty("pluginClass").get(),
            "Plugin-Description" to providers.gradleProperty("pluginDescription").get(),
            "Plugin-Version" to providers.gradleProperty("version").get(),
            "Plugin-Mainversion" to providers.gradleProperty("josmVersion").get(),
            "Implementation-Title" to "WayHeatmapTracer",
            "Implementation-Version" to providers.gradleProperty("version").get()
        )
    }
}

tasks.register<JavaExec>("v022ProbabilisticBenchmark") {
    group = "verification"
    description = "Runs the local v0.22 probabilistic benchmark from an explicit manifest"
    classpath = sourceSets["tools"].runtimeClasspath
    mainClass.set("org.openstreetmap.josm.plugins.wayheatmaptracer.v022.V022ProbabilisticBenchmarkTool")
    val manifest = providers.gradleProperty("v022BenchmarkManifest")
    doFirst {
        if (!manifest.isPresent || manifest.get().isBlank()) {
            throw GradleException("v022ProbabilisticBenchmark requires -Pv022BenchmarkManifest=<local path>")
        }
    }
    args("--manifest", manifest.orNull ?: "")
}
