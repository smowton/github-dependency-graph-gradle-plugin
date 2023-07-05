/*
 * This Java source file was generated by the Gradle 'init' task.
 */
package org.gradle.github.dependencygraph

import org.gradle.api.Plugin
import org.gradle.api.invocation.Gradle
import org.gradle.api.provider.Provider
import org.gradle.github.dependencygraph.internal.DependencyExtractor
import org.gradle.github.dependencygraph.internal.DependencyExtractorBuildService
import org.gradle.github.dependencygraph.internal.util.EnvironmentVariableLoader
import org.gradle.github.dependencygraph.internal.util.PluginCompanionUtils
import org.gradle.github.dependencygraph.internal.util.service
import org.gradle.internal.build.event.BuildEventListenerRegistryInternal
import org.gradle.util.GradleVersion
import java.io.File
import java.nio.file.Path
import java.nio.file.Paths

/**
 * A plugin that collects all resolved dependencies in a Gradle build and exports it using the GitHub API format.
 */
class GitHubDependencyExtractorPlugin : Plugin<Gradle> {
    private companion object : PluginCompanionUtils() {
        const val ENV_DEPENDENCY_GRAPH_JOB_ID = "GITHUB_DEPENDENCY_GRAPH_JOB_ID"
        const val ENV_DEPENDENCY_GRAPH_JOB_CORRELATOR = "GITHUB_DEPENDENCY_GRAPH_JOB_CORRELATOR"
        const val ENV_DEPENDENCY_GRAPH_REPORT_DIR = "GITHUB_DEPENDENCY_GRAPH_REPORT_DIR"
        const val ENV_GITHUB_REF = "GITHUB_REF"
        const val ENV_GITHUB_SHA = "GITHUB_SHA"

        /**
         * Environment variable should be set to the workspace directory that the Git repository is checked out in.
         * This is used to determine relative path to build files referenced in the dependency graph.
         */
        const val ENV_GITHUB_WORKSPACE = "GITHUB_WORKSPACE"
    }

    internal lateinit var dependencyExtractorProvider: Provider<out DependencyExtractor>

    override fun apply(gradle: Gradle) {
        val gradleVersion = GradleVersion.current()
        // Create the adapter based upon the version of Gradle
        val applicatorStrategy = when {
            gradleVersion < GradleVersion.version("8.0") -> PluginApplicatorStrategy.LegacyPluginApplicatorStrategy
            else -> PluginApplicatorStrategy.DefaultPluginApplicatorStrategy
        }

        // Create the service
        dependencyExtractorProvider = applicatorStrategy.createExtractorService(gradle)

        gradle.rootProject { project ->
            dependencyExtractorProvider
                .get()
                .rootProjectBuildDirectory = project.buildDir
        }

        // Register the service to listen for Build Events
        applicatorStrategy.registerExtractorListener(gradle, dependencyExtractorProvider)

        // Register the shutdown hook that should execute at the completion of the Gradle build.
        applicatorStrategy.registerExtractorServiceShutdown(gradle, dependencyExtractorProvider)
    }

    /**
     * Adapters for creating the [DependencyExtractor] and installing it into [Gradle] based upon the Gradle version.
     */
    private interface PluginApplicatorStrategy {

        fun createExtractorService(
            gradle: Gradle
        ): Provider<out DependencyExtractor>

        fun registerExtractorListener(
            gradle: Gradle,
            extractorServiceProvider: Provider<out DependencyExtractor>
        )

        fun registerExtractorServiceShutdown(
            gradle: Gradle,
            extractorServiceProvider: Provider<out DependencyExtractor>
        )

        object LegacyPluginApplicatorStrategy : PluginApplicatorStrategy, EnvironmentVariableLoader.Legacy {

            override fun createExtractorService(
                gradle: Gradle
            ): Provider<out DependencyExtractor> {
                val providerFactory = gradle.providerFactory

                val gitWorkspaceEnvVar = gradle.loadEnvironmentVariable(ENV_GITHUB_WORKSPACE)

                val gitWorkspaceDirectory = Paths.get(gitWorkspaceEnvVar)
                // Create a constant value that the provider will always return.
                // IE. Memoize the value
                val constantDependencyExtractor = object : DependencyExtractor() {
                    override val dependencyGraphJobCorrelator: String
                        get() = gradle.loadEnvironmentVariable(ENV_DEPENDENCY_GRAPH_JOB_CORRELATOR)
                    override val dependencyGraphJobId: String
                        get() = gradle.loadEnvironmentVariable(ENV_DEPENDENCY_GRAPH_JOB_ID)
                    override val dependencyGraphReportDir: String
                        get() = gradle.loadEnvironmentVariable(ENV_DEPENDENCY_GRAPH_REPORT_DIR, "")
                    override val gitSha: String
                        get() = gradle.loadEnvironmentVariable(ENV_GITHUB_SHA)
                    override val gitRef: String
                        get() = gradle.loadEnvironmentVariable(ENV_GITHUB_REF)
                    override val gitWorkspaceDirectory: Path
                        get() = gitWorkspaceDirectory
                }
                return providerFactory.provider { constantDependencyExtractor }
            }

            override fun registerExtractorListener(
                gradle: Gradle,
                extractorServiceProvider: Provider<out DependencyExtractor>
            ) {
                gradle
                    .buildOperationListenerManager
                    .addListener(extractorServiceProvider.get())
            }

            override fun registerExtractorServiceShutdown(
                gradle: Gradle,
                extractorServiceProvider: Provider<out DependencyExtractor>
            ) {
                gradle.buildFinished {
                    extractorServiceProvider.get().close()
                    gradle
                        .buildOperationListenerManager
                        .removeListener(extractorServiceProvider.get())
                }
            }
        }

        object DefaultPluginApplicatorStrategy : PluginApplicatorStrategy, EnvironmentVariableLoader.Default {
            private const val SERVICE_NAME = "gitHubDependencyExtractorService"

            override fun createExtractorService(
                gradle: Gradle
            ): Provider<out DependencyExtractor> {
                val objectFactory = gradle.objectFactory
                val gitWorkspaceEnvVar =
                    gradle
                        .loadEnvironmentVariable(ENV_GITHUB_WORKSPACE)
                        .map { File(it) }

                val gitWorkspaceDirectory =
                    gitWorkspaceEnvVar.flatMap {
                        objectFactory.directoryProperty().apply {
                            set(it)
                        }
                    }

                return gradle.sharedServices.registerIfAbsent(
                    SERVICE_NAME,
                    DependencyExtractorBuildService::class.java
                ) { spec ->
                    spec.parameters {
                        it.dependencyGraphJobCorrelator.convention(gradle.loadEnvironmentVariable(ENV_DEPENDENCY_GRAPH_JOB_CORRELATOR))
                        it.dependencyGraphJobId.convention(gradle.loadEnvironmentVariable(ENV_DEPENDENCY_GRAPH_JOB_ID))
                        it.dependencyGraphReportDir.convention(gradle.loadEnvironmentVariable(ENV_DEPENDENCY_GRAPH_REPORT_DIR, ""))
                        it.gitSha.convention(gradle.loadEnvironmentVariable(ENV_GITHUB_SHA))
                        it.gitRef.convention(gradle.loadEnvironmentVariable(ENV_GITHUB_REF))
                        it.gitWorkspaceDirectory.convention(gitWorkspaceDirectory)
                    }
                }
            }

            override fun registerExtractorListener(
                gradle: Gradle,
                extractorServiceProvider: Provider<out DependencyExtractor>
            ) {
                gradle.service<BuildEventListenerRegistryInternal>()
                    .onOperationCompletion(extractorServiceProvider)
            }

            override fun registerExtractorServiceShutdown(
                gradle: Gradle,
                extractorServiceProvider: Provider<out DependencyExtractor>
            ) {
                // No-op as DependencyExtractorService is Auto-Closable
            }
        }
    }
}
