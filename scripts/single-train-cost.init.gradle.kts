import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.JavaExec

// New fixed diagnostic only. Earlier build.gradle.kts and all signed source sets stay unchanged.
gradle.projectsEvaluated {
    val project = rootProject
    val sourceSets = project.extensions.getByType<SourceSetContainer>()
    val observation = sourceSets.getByName("observationIdentify")
    observation.java.include(
        "org/openstreetmap/josm/plugins/wayheatmaptracer/service/tracing/probabilistic/BSingleTrainCostManifest.java",
        "org/openstreetmap/josm/plugins/wayheatmaptracer/service/tracing/probabilistic/BSingleTrainObjectiveAccounting.java",
        "org/openstreetmap/josm/plugins/wayheatmaptracer/service/tracing/probabilistic/BSingleTrainCostDiagnosticTool.java"
    )
    observation.resources.include("b-single-train-cost/witness.json")
    project.tasks.register<JavaExec>("v022SingleTrainCostDiagnostic") {
        dependsOn(project.tasks.named("observationIdentifyClasses"))
        classpath = observation.runtimeClasspath
        mainClass.set("org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic.BSingleTrainCostDiagnosticTool")
        workingDir = project.projectDir
        doFirst {
            val digest = project.providers.gradleProperty("singleTrainCostFreezeSha256").orNull
                ?: throw GradleException("Reviewed external cost freeze literal required")
            val output = project.providers.gradleProperty("singleTrainCostOutput").orNull
                ?: throw GradleException("New fixed diagnostic output directory required")
            if (!digest.matches(Regex("[0-9a-f]{64}"))) throw GradleException("Invalid external cost freeze digest")
            args = listOf("--output", output, "--freeze-sha256", digest)
        }
    }
}
