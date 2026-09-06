package com.github.benmanes.gradle.versions.updates

import com.github.benmanes.gradle.versions.updates.resolutionstrategy.CollectingComponentSelectionRules
import com.github.benmanes.gradle.versions.updates.resolutionstrategy.ComponentFilter
import com.github.benmanes.gradle.versions.updates.resolutionstrategy.ComponentSelectionWithCurrent
import com.github.benmanes.gradle.versions.updates.resolutionstrategy.RecordedComponentSelection
import com.github.benmanes.gradle.versions.updates.resolutionstrategy.ResolutionStrategyWithCurrent
import org.gradle.api.Action
import org.gradle.api.artifacts.VersionConstraint
import org.gradle.api.internal.artifacts.dependencies.DefaultImmutableVersionConstraint
import org.gradle.api.logging.Logger
import org.gradle.api.specs.Spec

/**
 * Replays the aggregating build's own component-selection rules over each row's recorded
 * candidates, starting at the row's baked verdict (the first-accept walk's answer) and walking
 * older, never newer. The baked verdict is the ceiling: it carries the revision filter,
 * configuration-level selection rules and `force`/`eachDependency` effects that only the producing
 * build could apply, none of which the judge can replay. Every including build's `dependencyUpdates`
 * task judges the rows beneath it by its own rules, so an outer build's `rejectVersionIf` governs a
 * row merged in from an included build, not only the rows it resolved itself.
 *
 * The report's own [revision] holds every candidate the walk reaches below the ceiling, as
 * [VersionStability] over the version string rather than the status a record has no metadata to
 * carry.
 *
 * A row moved below its verdict offers a version that satisfied both builds' rules but that no
 * resolution proved usable, unlike the version the producing build accepted and resolved: the
 * candidates are a repository listing, and a version can pass version selection and still fail
 * variant selection.
 */
internal class Judge(
  resolutionStrategy: Action<in ResolutionStrategyWithCurrent>?,
  private val logger: Logger,
  private val revision: String,
  private val rejectPreReleases: Boolean,
  preReleaseVersionIf: Spec<String>?,
  exemptFromBuiltInChecksIf: ComponentFilter?,
) {
  private val collector = CollectingComponentSelectionRules()
  private val currentHolder = mutableMapOf<Coordinate.Key, Coordinate>()

  /** The report's own pre-release check, the built-in markers plus the convention set in its build. */
  private val isPreRelease: (String) -> Boolean = VersionStability.withConvention(preReleaseVersionIf)

  /** Whether a candidate is exempt from the report's built-in checks; nothing is unless configured. */
  private val isExempt: (ComponentSelectionWithCurrent) -> Boolean =
    if (exemptFromBuiltInChecksIf == null) {
      { false }
    } else {
      { current -> exemptFromBuiltInChecksIf.reject(current) }
    }

  /**
   * Whether the report has rules of its own to apply. The action is executed once here rather than
   * once per pass, so a rule with a side effect of its own has it as often as the build that
   * resolved with it would.
   */
  private val hasRules: Boolean =
    if (resolutionStrategy == null) {
      false
    } else {
      try {
        resolutionStrategy.execute(
          ResolutionStrategyWithCurrent(collector, currentHolder, deprecatedBoundWarning(logger)),
        )
        !collector.isEmpty
      } catch (e: Exception) {
        // Each producer already applied this same resolutionStrategy per configuration and, on the
        // same throw, caught it there and recorded a skipped configuration (see Aggregation.kt), so
        // failing the whole task on top of that already-reported skip would help nobody. Reported
        // rather than swallowed: a strategy that throws only here, such as a serialized closure
        // reading a build script, would otherwise leave every row unjudged with nothing said.
        reportUnapplied(e)
        false
      }
    }

  /**
   * Warns that the report holds what its producers reported, naming the throw by its first line
   * alone, as the skipped configurations the same throw produces are, so a multi-line cause does
   * not spill the detail that the report holds back.
   */
  private fun reportUnapplied(e: Exception) {
    logger.warn(
      "The report kept each dependency as the build that resolved it reported it: applying " +
        "its own component selection rules failed with " +
        e.message.orEmpty().lineSequence().first(),
    )
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
    if (!hasRules && !rejectPreReleases) {
      return statuses
    }
    return try {
      statuses.map { status -> judgeRow(status, candidatesByProjectPath) }
    } catch (e: Exception) {
      // A rule body throws where registering it did not: a closure the configuration cache carried
      // without the build script it reads reaches the missing method only once a candidate is
      // offered to it. Every row falls back rather than the one that threw, so what is reported is
      // one build's answer throughout rather than a mix of judged and unjudged rows.
      reportUnapplied(e)
      statuses
    }
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
    // Sorted rather than taken as recorded: a candidate is recorded as the repository it came from
    // offers it, so a module found in more than one repository is recorded newest-first per
    // repository rather than newest-first overall, and the verdict can trail candidates older than
    // itself. The walk below reads position as age, so it has to be given an order that says so.
    val moduleCandidates =
      candidates
        .filter { it.startsWith(prefix) }
        .sortedWith(compareByDescending(VersionMapping.versionComparator()) { it.substring(prefix.length) })
    val ceilingIndex = moduleCandidates.indexOf("$prefix${status.latestVersion}")
    if (ceilingIndex < 0) {
      return status
    }

    val rowKey = Coordinate.Key(status.group, status.name)
    val current =
      Coordinate(
        status.group,
        status.name,
        status.declaredVersion,
        status.userReason,
        status.constraint?.toVersionConstraint(),
        status.platformConstraints.map { it.toVersionConstraint() },
      )
    currentHolder.clear()
    currentHolder[rowKey] = current
    val rules = collector.rulesFor(status.group, status.name)

    // Named for the ceiling candidate alone (the first iterated below): `selectorVersion` in the
    // synthesized UnresolvedInfo is always the ceiling, so the reason reported must answer why
    // that named version was rejected, not why some older candidate further down the walk was.
    var ceilingReason: String? = null
    for (index in ceilingIndex until moduleCandidates.size) {
      val version = moduleCandidates[index].substring(prefix.length)
      val shim = RecordedComponentSelection(status.group, status.name, version)
      // Applied at the ceiling as well as below it, and ahead of the rules, as the producer applies
      // it ahead of a build's own rules. The version a producer baked was accepted under that
      // build's own check, which an included build may have turned off or given a different
      // convention, so the report holds every row it merges to its own.
      if (rejectsPreRelease(current, shim)) {
        if (index == ceilingIndex) {
          ceilingReason = PRE_RELEASE_REASON
        }
        continue
      }
      // Below the ceiling the report's own revision is all there is to hold a candidate to, as the
      // record carries no status. A guard rejection is never an unjudged one: only the version
      // string is read, which every record carries.
      if (index > ceilingIndex && !accepted(status, version)) {
        continue
      }
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
    return unresolvedRow(status, ceilingReason)
  }

  /** Returns [status] as a row no version satisfied, named for why its verdict was rejected. */
  private fun unresolvedRow(
    status: PartialStatus,
    ceilingReason: String?,
  ): PartialStatus =
    status.copy(
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

  /**
   * Returns whether the report's own `rejectPreReleases` leaves this candidate out, read
   * through the same wrapper a rule reads it through so that `isPreRelease` and an
   * `exemptFromBuiltInChecksIf` predicate answer here as they do at a producer.
   */
  private fun rejectsPreRelease(
    current: Coordinate,
    shim: RecordedComponentSelection,
  ): Boolean {
    if (!rejectPreReleases) {
      return false
    }
    val selection =
      ComponentSelectionWithCurrent(
        shim,
        current.version,
        current.versionConstraint,
        current.platformVersionConstraints,
        current.onScriptClasspath,
        {},
        isPreRelease,
      )
    return selection.isPreRelease() && !isExempt(selection)
  }

  /**
   * Returns whether the report's own revision accepts [version] for [status], exempting the version
   * the build already declares so that a row is never held back from the release it is already on.
   * https://github.com/ben-manes/gradle-versions-plugin/issues/475
   */
  private fun accepted(
    status: PartialStatus,
    version: String,
  ): Boolean = version == status.declaredVersion || VersionStability.accepts(revision, version)

  /** Rebuilds the constraint the four serialized strings captured; `branch` is not serialized. */
  private fun ConstraintInfo.toVersionConstraint(): VersionConstraint =
    DefaultImmutableVersionConstraint(preferred, required, strict, rejected, "")

  private companion object {
    /** The reason a producer gives for the same rejection, so a row reads alike either way. */
    const val PRE_RELEASE_REASON = "Pre-release rejected by rejectPreReleases"
  }
}
