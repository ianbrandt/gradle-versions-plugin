package com.github.benmanes.gradle.versions.updates

import com.github.benmanes.gradle.versions.updates.resolutionstrategy.CollectingComponentSelectionRules
import com.github.benmanes.gradle.versions.updates.resolutionstrategy.RecordedComponentSelection
import com.github.benmanes.gradle.versions.updates.resolutionstrategy.ResolutionStrategyWithCurrent
import org.gradle.api.Action
import org.gradle.api.artifacts.VersionConstraint
import org.gradle.api.internal.artifacts.dependencies.DefaultImmutableVersionConstraint
import org.gradle.api.logging.Logger

/**
 * Replays the aggregating build's own component-selection rules over each row's recorded
 * candidates, starting at the row's baked verdict (the first-accept walk's answer) and walking
 * older, never newer. The baked verdict is the ceiling: it carries the revision filter,
 * configuration-level selection rules and `force`/`eachDependency` effects that only the producing
 * build could apply, none of which the judge can replay. Every including build's `dependencyUpdates`
 * task judges the rows beneath it by its own rules, so an outer build's `rejectVersionIf` governs a
 * row merged in from an included build, not only the rows it resolved itself.
 */
internal class Judge(
  resolutionStrategy: Action<in ResolutionStrategyWithCurrent>?,
  logger: Logger,
) {
  private val collector = CollectingComponentSelectionRules()
  private val currentHolder = mutableMapOf<Coordinate.Key, Coordinate>()

  /**
   * Whether the report has rules of its own to apply. The action is executed once here rather than
   * once per pass, so a rule with a side effect of its own has it as often as the build that
   * resolved with it would.
   */
  private val applicable: Boolean =
    if (resolutionStrategy == null) {
      false
    } else {
      try {
        resolutionStrategy.execute(ResolutionStrategyWithCurrent(collector, currentHolder))
        !collector.isEmpty
      } catch (e: Exception) {
        // Each producer already applied this same resolutionStrategy per configuration and, on the
        // same throw, caught it there and recorded a skipped configuration (see Aggregation.kt), so
        // failing the whole task on top of that already-reported skip would help nobody. Reported
        // rather than swallowed: a strategy that throws only here, such as a serialized closure
        // reading a build script, would otherwise leave every row unjudged with nothing said.
        // Named by its first line alone, as the skipped configurations the same throw produces are,
        // so a multi-line cause does not spill the detail that the report holds back.
        logger.warn(
          "The report kept each dependency as the build that resolved it reported it: applying " +
            "its own component selection rules failed with " +
            e.message.orEmpty().lineSequence().first(),
        )
        false
      }
    }

  /**
   * Returns [statuses] with each resolved row's `latestVersion` capped to the newest candidate the
   * aggregating build's own rules accept, reading the candidates from the partial the row's own
   * `projectPath` names. A row absent from its partial's candidates (a `none`-version row, a file
   * dependency, an offline run, or a v1/v2 partial from an older release) keeps its baked verdict.
   */
  fun judge(
    statuses: List<PartialStatus>,
    candidatesByProjectPath: Map<String, List<String>>,
  ): List<PartialStatus> {
    if (!applicable) {
      return statuses
    }
    return statuses.map { status -> judgeRow(status, candidatesByProjectPath) }
  }

  private fun judgeRow(
    status: PartialStatus,
    candidatesByProjectPath: Map<String, List<String>>,
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

    // Named for the ceiling candidate alone (the first iterated below): `selectorVersion` in the
    // synthesized UnresolvedInfo is always the ceiling, so the reason reported must answer why
    // that named version was rejected, not why some older candidate further down the walk was.
    var ceilingReason: String? = null
    for (index in ceilingIndex until moduleCandidates.size) {
      val version = moduleCandidates[index].substring(prefix.length)
      val shim = RecordedComponentSelection(status.group, status.name, version)
      for (rule in rules) {
        if (shim.rejected || shim.unjudged) break
        shim.applyRule(rule)
      }
      // A rule that rejected on the metadata the record does not carry judged the record rather
      // than the candidate, and nothing distinguishes the candidates below it from that same
      // answer, so the row is left as the build that resolved it reported it. Continuing the walk
      // would offer a version this build's own rules had already rejected above.
      if (shim.unjudged) {
        return status
      }
      if (!shim.rejected) {
        return if (version == status.latestVersion) status else status.copy(latestVersion = version)
      }
      if (index == ceilingIndex) {
        ceilingReason = shim.reason
      }
    }
    return status.copy(
      latestVersion = "none",
      unresolved =
        UnresolvedInfo(
          status.group,
          status.name,
          status.latestVersion,
          ceilingReason.takeUnless { it.isNullOrEmpty() }
            ?: "Rejected by the aggregating build's component selection rules",
          status.declaredVersion,
          status.userReason,
        ),
    )
  }

  /** Rebuilds the constraint the four serialized strings captured; `branch` is not serialized. */
  private fun ConstraintInfo.toVersionConstraint(): VersionConstraint =
    DefaultImmutableVersionConstraint(preferred, required, strict, rejected, "")
}
