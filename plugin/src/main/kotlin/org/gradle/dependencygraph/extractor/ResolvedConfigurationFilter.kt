package org.gradle.dependencygraph.extractor

class ResolvedConfigurationFilter(projectFilter: String?, configurationFilter: String?) {
    private val projectRegex = projectFilter?.toRegex()
    private val configurationRegex = configurationFilter?.toRegex()

    fun include(projectPath: String, configurationName: String): Boolean {
        if (projectRegex != null && !projectRegex.matches(projectPath)) {
            return false
        }
        if (configurationRegex != null && !configurationRegex.matches(configurationName)) {
            return false
        }
        return true
    }
}