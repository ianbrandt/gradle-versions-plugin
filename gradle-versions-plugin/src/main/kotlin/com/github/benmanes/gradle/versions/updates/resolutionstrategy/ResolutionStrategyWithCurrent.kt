package com.github.benmanes.gradle.versions.updates.resolutionstrategy

import com.github.benmanes.gradle.versions.updates.Coordinate
import com.github.benmanes.gradle.versions.updates.VersionStability
import groovy.lang.Closure
import org.gradle.api.Action
import org.gradle.api.artifacts.ComponentSelectionRules
import org.gradle.api.artifacts.DependencyResolveDetails
import org.gradle.api.artifacts.DependencySubstitutions
import org.gradle.api.artifacts.ResolutionStrategy

class ResolutionStrategyWithCurrent private constructor(
  private val delegate: ResolutionStrategy?,
  private val componentSelectionRules: ComponentSelectionRules,
  private val currentCoordinates: Map<Coordinate.Key, Coordinate>,
  private val onDeprecatedBoundRead: () -> Unit,
  /** The pre-release check a rule reads, the built-in markers plus the convention added in the build. */
  private val isPreRelease: (String) -> Boolean = VersionStability::isPreRelease,
) {
  /** Retained so the arity released before the deprecation warning was added still links. */
  constructor(
    delegate: ResolutionStrategy,
    currentCoordinates: Map<Coordinate.Key, Coordinate>,
  ) : this(delegate, delegate.componentSelection, currentCoordinates, {})

  internal constructor(
    delegate: ResolutionStrategy,
    currentCoordinates: Map<Coordinate.Key, Coordinate>,
    onDeprecatedBoundRead: () -> Unit,
  ) : this(delegate, delegate.componentSelection, currentCoordinates, onDeprecatedBoundRead)

  /**
   * Harvests the rules a build's `resolutionStrategy` action registers rather than running them
   * against a live resolution, so the judge can replay them over recorded candidates at the report.
   * The seven behavior methods below become no-ops, as they shape a build's own resolution rather
   * than the report the judge replays rules over.
   */
  internal constructor(
    componentSelectionRules: CollectingComponentSelectionRules,
    currentCoordinates: Map<Coordinate.Key, Coordinate>,
    onDeprecatedBoundRead: () -> Unit = {},
  ) : this(null, componentSelectionRules, currentCoordinates, onDeprecatedBoundRead)

  fun failOnVersionConflict(): ResolutionStrategyWithCurrent {
    delegate?.failOnVersionConflict()
    return this
  }

  fun activateDependencyLocking(): ResolutionStrategyWithCurrent {
    delegate?.activateDependencyLocking()
    return this
  }

  fun force(vararg moduleVersionSelectorNotations: Any?): ResolutionStrategyWithCurrent {
    delegate?.force(moduleVersionSelectorNotations)
    return this
  }

  fun setForcedModules(vararg moduleVersionSelectorNotations: Any?): ResolutionStrategyWithCurrent {
    delegate?.setForcedModules(moduleVersionSelectorNotations)
    return this
  }

  fun eachDependency(rule: Action<in DependencyResolveDetails>): ResolutionStrategyWithCurrent {
    delegate?.eachDependency(rule)
    return this
  }

  fun dependencySubstitution(action: Action<in DependencySubstitutions>): ResolutionStrategyWithCurrent {
    delegate?.dependencySubstitution(action)
    return this
  }

  fun componentSelection(action: Action<in ComponentSelectionRulesWithCurrent>): ResolutionStrategyWithCurrent {
    action.execute(getComponentSelectionNonDelegate())
    return this
  }

  fun componentSelection(closure: Closure<*>): ResolutionStrategyWithCurrent {
    return componentSelection {
      closure.delegate = it
      closure.call(it)
    }
  }

  private fun getComponentSelectionNonDelegate(): ComponentSelectionRulesWithCurrent {
    return ComponentSelectionRulesWithCurrent(
      componentSelectionRules,
      currentCoordinates,
      onDeprecatedBoundRead,
      isPreRelease,
    )
  }
}
