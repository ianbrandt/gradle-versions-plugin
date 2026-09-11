package com.github.benmanes.gradle.versions.updates

import org.gradle.api.artifacts.VersionConstraint
import org.gradle.api.artifacts.component.ModuleComponentSelector
import org.gradle.api.artifacts.result.UnresolvedDependencyResult

/**
 * The version status of a dependency.
 *
 * The `latestVersion` is set if the dependency was successfully resolved, otherwise the
 * `unresolved` contains the exception that caused the resolution to fail.
 */
class DependencyStatus {
  val coordinate: Coordinate
  val latestVersion: String
  val unresolved: UnresolvedDependencyResult?
  val projectUrl: String?
  val contributed: Boolean
  val configurations: List<String>

  /** The newest candidate the pre-release check left out, null when it left none out. */
  val preReleaseVersion: String?

  @JvmOverloads
  constructor(
    coordinate: Coordinate,
    latestVersion: String,
    projectUrl: String?,
    contributed: Boolean = false,
    configurations: List<String> = emptyList(),
    preReleaseVersion: String? = null,
  ) {
    this.coordinate = coordinate
    this.latestVersion = latestVersion
    this.projectUrl = projectUrl
    this.unresolved = null
    this.contributed = contributed
    this.configurations = configurations
    this.preReleaseVersion = preReleaseVersion
  }

  constructor(
    coordinate: Coordinate,
    unresolved: UnresolvedDependencyResult?,
    contributed: Boolean = false,
    configurations: List<String> = emptyList(),
  ) {
    this.coordinate = coordinate
    this.unresolved = unresolved
    latestVersion = "none"
    projectUrl = null
    this.contributed = contributed
    this.configurations = configurations
    this.preReleaseVersion = null
  }

  fun getLatestCoordinate(): Coordinate {
    return Coordinate(
      coordinate.groupId,
      coordinate.artifactId,
      latestVersion,
      coordinate.userReason,
    )
  }

  /** Returns the serializable projection of this status. */
  fun toPartialStatus(): PartialStatus {
    val info =
      unresolved?.let { dependency ->
        val selector = dependency.attempted as ModuleComponentSelector
        val failure = dependency.failure
        val reason =
          generateSequence(failure) { it.cause }
            .take(MAX_FAILURE_CAUSES)
            .map { it.message ?: it.toString() }
            .joinToString(separator = "; ")
        UnresolvedInfo(
          selector.group,
          selector.module,
          selector.version,
          reason,
          coordinate.version,
          coordinate.userReason,
        )
      }
    return PartialStatus(
      coordinate.groupId,
      coordinate.artifactId,
      coordinate.version,
      coordinate.userReason,
      latestVersion,
      projectUrl,
      info,
      contributed,
      configurations,
      platformProjects = coordinate.platformProjects,
      constrainedBy = coordinate.constrainedBy,
      constraint = coordinate.versionConstraint?.toConstraintInfo(),
      platformConstraints = coordinate.platformVersionConstraints.map { it.toConstraintInfo() },
      onScriptClasspath = coordinate.onScriptClasspath,
      preReleaseVersion = preReleaseVersion,
    )
  }

  private companion object {
    /** Guards against a cause chain that cycles back on itself. */
    const val MAX_FAILURE_CAUSES = 20
  }
}

/** Returns the constraint's four getters verbatim, as a value that survives the project boundary. */
private fun VersionConstraint.toConstraintInfo(): ConstraintInfo =
  ConstraintInfo(requiredVersion, strictVersion, preferredVersion, rejectedVersions.toList())
