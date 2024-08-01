package org.gradle.forceresolve

import org.gradle.api.Project
import org.gradle.dependencygraph.util.JacksonJsonSerializer
import org.gradle.dependencygraph.util.PluginParameters
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

class ExportConfigurationArtifacts {

    companion object {
        val uniqueFileNumber = AtomicInteger(1)
    }

    data class ArtifactDescriptor(val group: String, val name: String, val version: String, val classifier: String?, val extension: String?)
    data class ArtifactDescriptorsOrException(val artifacts: List<ArtifactDescriptor>?, val exception: String?)

    fun export(project: Project) {
        val report = project.configurations.filter { it.isCanBeResolved && it.name.endsWith("Classpath") }.associate {
            try {
                val artifacts = it.resolvedConfiguration.lenientConfiguration.artifacts.map { it2 ->
                    ArtifactDescriptor(
                        it2.moduleVersion.id.group,
                        it2.moduleVersion.id.name,
                        it2.moduleVersion.id.version,
                        it2.classifier,
                        it2.extension
                    )
                }
                it.name to ArtifactDescriptorsOrException(artifacts, null)
            } catch (e: Exception) {
                it.name to ArtifactDescriptorsOrException(null, e.toString())
            }
        }
        if (report.isNotEmpty()) {
            val pluginParams = PluginParameters()
            val outputDir = pluginParams.loadOptional("DEPENDENCY_GRAPH_REPORT_DIR") ?: "."
            val fileNamePrefix = pluginParams.load("GITHUB_DEPENDENCY_GRAPH_JOB_CORRELATOR")
            val fileNumber = uniqueFileNumber.getAndIncrement()
            val outputFile = File(outputDir, "$fileNamePrefix-configurations-$fileNumber.json")
            try {
                File(outputDir).mkdirs()
            } catch (e: Exception) { }
            val reportJson = JacksonJsonSerializer.serializeToJson(report)
            outputFile.writeText(reportJson)
        }
    }
}