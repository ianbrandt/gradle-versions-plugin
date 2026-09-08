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
 * older, never newer. The baked verdict is the ceiling: the revision filter, the
 * configuration-level selection rules and the `force`/`eachDependency` effects that only the
 * producing build applies are already folded into it, and none of them can be replayed here. Every
 * including build's `dependencyUpdates` task applies its own rules to the rows beneath it, so an
 * outer build's `rejectVersionIf` is applied to a row merged in from an included build, not only to
 * the rows resolved there.
 *
 * The report's [revision] is applied to every candidate the walk reaches below the ceiling, as
 * [VersionStability] over the version string, since a record has no metadata for the status.
 *
 * A row moved below its verdict shows a version that satisfied both builds' rules and that no
 * resolution proved usable, unlike the version accepted in the producing build, which was resolved
 * there: the candidates come from a repository listing, and a version can pass version selection
 * and still fail variant selection.
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

  /** The report's pre-release check, the built-in markers plus the convention added in its build. */
  private val isPreRelease: (String) -> Boolean = VersionStability.withConvention(preReleaseVersionIf)

  /** Whether a candidate is exempt from the report's built-in checks; nothing is unless configured. */
  private val isExempt: (ComponentSelectionWithCurrent) -> Boolean =
    if (exemptFromBuiltInChecksIf == null) {
      { false }
    } else {
      { current -> exemptFromBuiltInChecksIf.reject(current) }
    }

  /**
   * Whether any rule is configured on this report. The action is executed once here rather than
   * once per pass, so a rule with a side effect runs it as often as it would in the build that
   * resolved with it.
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
        // reading a build script, would otherwise leave every row unjudged with nothing printed.
        reportUnapplied(e)
        false
      }
    }

  /**
   * Warns that every row is left as its producer reported it, printing the first line of the throw
   * alone, as a skipped configuration from the same throw is, so a multi-line cause is not printed
   * in full.
   */
  private fun reportUnapplied(e: Exception) {
    logger.warn(
      "Every dependency is left as the build that resolved it reported it: applying this " +
        "report's component selection rules failed with " +
        e.message.orEmpty().lineSequence().first(),
    )
  }

  /**
   * Returns [statuses] with each resolved row's `latestVersion` capped to the newest candidate the
   * aggregating build's rules accept, reading the candidates from the partial for the row's
   * `projectPath`. A row absent from its partial's candidates (a `none`-version row, a file
   * dependency, an offline run, or a v1/v2 partial from an older release) is left at its baked
   * verdict.
   */
  fun judge(
    statuses: List<PartialStatus>,
    candidatesByProjectPath: Map<String, List<String>>,
  ): List<PartialStatus> {
    if (!hasRules && !rejectPreReleases) {
      return statuses
    }
    val versionsByProjectPath = candidatesByProjectPath.mapValues { (_, candidates) -> versionsByModule(candidates) }
    return try {
      statuses.map { status -> judgeRow(status, versionsByProjectPath) }
    } catch (e: Exception) {
      // A rule body throws where registering it did not: a closure serialized into the
      // configuration cache without the build script it reads hits the missing method only once a
      // candidate reaches it. Every row falls back rather than the one that threw, so the report is
      // one build's answer throughout rather than a mix of judged and unjudged rows.
      reportUnapplied(e)
      statuses
    }
  }

  private fun judgeRow(
    status: PartialStatus,
    versionsByProjectPath: Map<String, Map<String, List<String>>>,
  ): PartialStatus {
    if (status.unresolved != null) {
      return status
    }
    val moduleVersions =
      status.projectPath
        ?.let { versionsByProjectPath[it] }
        ?.get("${status.group}:${status.name}")
        .orEmpty()
    val ceilingIndex = moduleVersions.indexOf(status.latestVersion)
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

    // Kept for the ceiling candidate alone (the first iterated below): `selectorVersion` in the
    // synthesized UnresolvedInfo is always the ceiling, so the reason reported has to be why that
    // version was rejected rather than why some older candidate further down the walk was.
    var ceilingReason: String? = null
    for (index in ceilingIndex until moduleVersions.size) {
      val version = moduleVersions[index]
      val shim = RecordedComponentSelection(status.group, status.name, version)
      // Applied at the ceiling as well as below it, and ahead of the rules, as the producer
      // applies it ahead of a build's own rules. The version a producer baked was accepted under
      // that build's check, which an included build may have switched off or extended with a
      // convention, so every merged row is checked again here.
      if (rejectsPreRelease(current, shim)) {
        if (index == ceilingIndex) {
          ceilingReason = PRE_RELEASE_REASON
        }
        continue
      }
      // An exemption that decided on the metadata absent from the record judged the record rather
      // than the candidate, so the row is left as the build that resolved it reported it, as it is
      // for a rule that rejects the same way.
      if (shim.unjudged) {
        return status
      }
      // Below the ceiling the report's revision is all a candidate can be checked against, since
      // no status is recorded. A guard rejection is never an unjudged one: only the version string
      // is read, and every record has one.
      if (index > ceilingIndex && !accepted(status, version)) {
        continue
      }
      for (rule in rules) {
        if (shim.rejected || shim.unjudged) break
        shim.applyRule(rule)
      }
      // A rule that rejected on the metadata absent from the record judged the record rather than
      // the candidate, and nothing distinguishes the candidates below it from that same answer, so
      // the row is left as the build that resolved it reported it. Continuing the walk would report
      // a version this build's rules had already rejected above.
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

  /** Returns [status] as a row no version satisfied, reporting why its verdict was rejected. */
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
   * Returns whether the report's `rejectPreReleases` leaves this candidate out, read
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
    if (!selection.isPreRelease()) {
      return false
    }
    // Read through the record rather than called, so an exemption that reads the metadata absent
    // from the record marks the candidate unjudged instead of answering. The caller leaves such a
    // row alone; leaving it out here on an answer the predicate could not give would drop an
    // upgrade the producer reported.
    val exempt = shim.evaluate { isExempt(selection) }
    return !shim.unjudged && !exempt
  }

  /**
   * Returns whether the report's revision accepts [version] for [status], exempting the version
   * already declared in the build so that a row is never held back from the release it is on.
   * https://github.com/ben-manes/gradle-versions-plugin/issues/475
   */
  private fun accepted(
    status: PartialStatus,
    version: String,
  ): Boolean = version == status.declaredVersion || VersionStability.accepts(revision, version)

  /** Rebuilds the constraint the four serialized strings captured; `branch` is not serialized. */
  private fun ConstraintInfo.toVersionConstraint(): VersionConstraint =
    DefaultImmutableVersionConstraint(preferred, required, strict, rejected, "")

  /**
   * Returns the recorded candidates of one project as the versions of each module, newest first.
   * Partitioned once for the whole report rather than per row, which would rescan and resort every
   * candidate the project recorded for each of its rows.
   *
   * Sorted rather than read as recorded: a candidate is recorded in the order its repository lists
   * it, so a module found in more than one repository is recorded newest-first per repository
   * rather than newest-first overall, and the verdict can trail candidates older than itself. The
   * walk in [judgeRow] reads position as age, so the order has to match.
   */
  private fun versionsByModule(candidates: List<String>): Map<String, List<String>> {
    val byModule = mutableMapOf<String, MutableList<String>>()
    for (candidate in candidates) {
      val group = candidate.indexOf(':')
      if (group < 0) continue
      val name = candidate.indexOf(':', group + 1)
      if (name < 0) continue
      byModule
        .getOrPut(candidate.substring(0, name)) { mutableListOf() }
        .add(candidate.substring(name + 1))
    }
    val newestFirst = VersionMapping.versionComparator().reversed()
    return byModule.mapValues { (_, versions) -> versions.sortedWith(newestFirst) }
  }

  private companion object {
    /** The reason a producer prints for the same rejection, so a row reads alike either way. */
    const val PRE_RELEASE_REASON = "Pre-release rejected by rejectPreReleases"
  }
}
