import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.JavaExec

// Invoke together with the unchanged single-train-cost init; append only these new helpers.
gradle.projectsEvaluated {
    val project = rootProject
    val observation = project.extensions.getByType<SourceSetContainer>().getByName("observationIdentify")
    observation.java.include(
        "org/openstreetmap/josm/plugins/wayheatmaptracer/service/tracing/probabilistic/BSingleTrainComparatorRegion.java",
        "org/openstreetmap/josm/plugins/wayheatmaptracer/service/tracing/probabilistic/BSingleTrainComparatorTool.java",
        "org/openstreetmap/josm/plugins/wayheatmaptracer/service/tracing/probabilistic/BSingleTrainComparatorManifest.java"
    )
    project.tasks.register<JavaExec>("v022SingleTrainAcceptedComparator") {
        dependsOn(project.tasks.named("observationIdentifyClasses"))
        classpath = observation.runtimeClasspath
        mainClass.set("org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic.BSingleTrainComparatorTool")
        workingDir = project.projectDir
        doFirst {
            val digest = project.providers.gradleProperty("singleTrainComparatorFreezeSha256").orNull
                ?: throw GradleException("Independent reviewed comparator freeze digest required")
            val output = project.providers.gradleProperty("singleTrainComparatorOutput").orNull
                ?: throw GradleException("New fixed comparator output directory required")
            if (!digest.matches(Regex("[0-9a-f]{64}"))) throw GradleException("Invalid external comparator digest")
            args = listOf("--output", output, "--freeze-sha256", digest)
        }
    }
}
