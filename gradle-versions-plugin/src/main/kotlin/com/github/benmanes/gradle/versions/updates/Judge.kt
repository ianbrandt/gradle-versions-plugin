package com.github.benmanes.gradle.versions.updates

import com.github.benmanes.gradle.versions.updates.resolutionstrategy.CollectingComponentSelectionRules
import com.github.benmanes.gradle.versions.updates.resolutionstrategy.RecordedComponentSelection
import com.github.benmanes.gradle.versions.updates.resolutionstrategy.ResolutionStrategyWithCurrent
import org.gradle.api.Action
import org.gradle.api.artifacts.VersionConstraint
import org.gradle.api.internal.artifacts.dependencies.DefaultImmutableVersionConstraint

/**
 * Replays the aggregating build's own component-selection rules over each row's recorded
 * candidates, starting at the row's baked verdict (the first-accept walk's answer) and walking
 * older, never newer. The baked verdict is the ceiling: it carries the revision filter,
 * configuration-level selection rules and `force`/`eachDependency` effects that only the producing
 * build could apply, none of which the judge can replay. Every including build's `dependencyUpdates`
 * task judges the rows beneath it by its own rules, so an outer build's `rejectVersionIf` governs a
 * row merged in from an included build, not only the rows it resolved itself.
 */
internal object Judge {
  /**
   * Returns [statuses] with each resolved row's `latestVersion` capped to the newest candidate the
   * aggregating build's own rules accept, reading the candidates from the partial the row's own
   * `projectPath` names. A row absent from its partial's candidates (a `none`-version row, a file
   * dependency, an offline run, or a v1/v2 partial from an older release) keeps its baked verdict.
   */
  fun judge(
    statuses: List<PartialStatus>,
    candidatesByProjectPath: Map<String, List<String>>,
    resolutionStrategy: Action<in ResolutionStrategyWithCurrent>?,
  ): List<PartialStatus> {
    if (resolutionStrategy == null) {
      return statuses
    }
    val collector = CollectingComponentSelectionRules()
    val currentHolder = mutableMapOf<Coordinate.Key, Coordinate>()
    try {
      resolutionStrategy.execute(ResolutionStrategyWithCurrent(collector, currentHolder))
    } catch (_: Exception) {
      // Each producer already applied this same resolutionStrategy per configuration and, on the
      // same throw, caught it there and recorded a skipped configuration (see Aggregation.kt); the
      // judge would otherwise fail the whole aggregating task on top of that already-reported skip.
      return statuses
    }
    if (collector.isEmpty) {
      return statuses
    }
    return statuses.map { status -> judgeRow(status, candidatesByProjectPath, collector, currentHolder) }
  }

  private fun judgeRow(
    status: PartialStatus,
    candidatesByProjectPath: Map<String, List<String>>,
    collector: CollectingComponentSelectionRules,
    currentHolder: MutableMap<Coordinate.Key, Coordinate>,
  ): PartialStatus {
    if (status.unresolved != null) {
      return status
    }
    val candidates = status.projectPath?.let { candidatesByProjectPath[it] }.orEmpty()
    val prefix = "${status.group}:${status.name}:"
    val moduleCandidates = candidates.filter { it.startsWith(prefix) }
    val ceilingIndex = moduleCandidates.indexOf("$prefix${status.latestVersion}")
    if (ceilingIndex < 0) {
      return status
    }

    val rowKey = Coordinate.Key(status.group, status.name)
    currentHolder.clear()
    currentHolder[rowKey] =
      Coordinate(
        status.group, status.name, status.declaredVersion, status.userReason,
        status.constraint?.toVersionConstraint(),
        status.platformConstraints.map { it.toVersionConstraint() },
      )
    val rules = collector.rulesFor(status.group, status.name)

    for (index in ceilingIndex until moduleCandidates.size) {
      val version = moduleCandidates[index].substring(prefix.length)
      val shim = RecordedComponentSelection(status.group, status.name, version)
      for (rule in rules) {
        if (shim.rejected) break
        shim.applyRule(rule)
      }
      if (!shim.rejected) {
        return if (version == status.latestVersion) status else status.copy(latestVersion = version)
      }
    }
    return status.copy(
      latestVersion = "none",
      unresolved =
        UnresolvedInfo(
          status.group,
          status.name,
          status.latestVersion,
          "Rejected by the aggregating build's component selection rules",
          status.declaredVersion,
          status.userReason,
        ),
    )
  }

  /** Rebuilds the constraint the four serialized strings captured; `branch` is not serialized. */
  private fun ConstraintInfo.toVersionConstraint(): VersionConstraint =
    DefaultImmutableVersionConstraint(preferred, required, strict, rejected, "")
}
