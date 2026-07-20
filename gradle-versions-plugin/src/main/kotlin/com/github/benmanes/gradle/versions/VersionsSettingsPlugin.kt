package com.github.benmanes.gradle.versions

import org.gradle.api.Plugin
import org.gradle.api.initialization.Settings

/**
 * Applies the [VersionsPlugin] to every project of the build, but not of an included build.
 */
class VersionsSettingsPlugin : Plugin<Settings> {
  override fun apply(settings: Settings) {
    // Isolated projects isolates the action of gradle.lifecycle.beforeProject so that the state it
    // captures cannot be shared between the projects it configures. This action captures nothing,
    // so the older hook is equivalent and does not require Gradle 8.8. Capturing the settings or a
    // field here would no longer be safe.
    settings.gradle.beforeProject { project ->
      project.pluginManager.apply(VersionsPlugin::class.java)
    }
  }
}
