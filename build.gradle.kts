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

tasks.jar {
    archiveBaseName.set("wayheatmaptracer")
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
