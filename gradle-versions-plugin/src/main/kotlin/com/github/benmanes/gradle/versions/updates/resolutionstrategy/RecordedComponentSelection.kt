package com.github.benmanes.gradle.versions.updates.resolutionstrategy

import org.gradle.api.artifacts.ComponentMetadata
import org.gradle.api.artifacts.ComponentSelection
import org.gradle.api.artifacts.ModuleIdentifier
import org.gradle.api.artifacts.component.ModuleComponentIdentifier

/** A value [ModuleComponentIdentifier] over three strings, with no live Gradle component behind it. */
private class RecordedModuleComponentIdentifier(
  private val group: String,
  private val module: String,
  private val version: String,
) : ModuleComponentIdentifier, ModuleIdentifier {
  override fun getGroup(): String = group

  override fun getModule(): String = module

  override fun getVersion(): String = version

  override fun getModuleIdentifier(): ModuleIdentifier = this

  override fun getName(): String = module

  override fun getDisplayName(): String = "$group:$module:$version"

  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other !is RecordedModuleComponentIdentifier) return false
    return group == other.group && module == other.module && version == other.version
  }

  override fun hashCode(): Int {
    var result = group.hashCode()
    result = 31 * result + module.hashCode()
    result = 31 * result + version.hashCode()
    return result
  }
}

/**
 * A [ComponentSelection] backed by a recorded candidate rather than a live Gradle resolution, fed
 * to the aggregating build's own component-selection rules at the report.
 *
 * [getMetadata] and [getDescriptor] always answer null. The contract is nullable, and the plugin's
 * own revision filter already treats null as accept, so a predicate that reads either still keeps
 * the ceiling this build's own resolution accepted with real metadata, rather than a wrong answer;
 * only its reach onto a merged-in row degrades, and it degrades toward the producing build's own
 * verdict. Real metadata would cost a fetch per candidate, and is impossible for a merged-in row
 * regardless of cost, as the child's repositories are not the aggregator's to query. No real
 * predicate was observed reading either (the README, the suite, ~15 sampled predicates, ~25
 * consumer repos, 0 issues)—a bounded negative, not proof of zero usage.
 */
internal class RecordedComponentSelection(
  group: String,
  module: String,
  version: String,
) : ComponentSelection {
  private val identifier = RecordedModuleComponentIdentifier(group, module, version)

  var rejected: Boolean = false
    private set

  override fun getCandidate(): ModuleComponentIdentifier = identifier

  override fun getMetadata(): ComponentMetadata? = null

  override fun <T : Any?> getDescriptor(clazz: Class<T>): T? = null

  override fun reject(reason: String) {
    rejected = true
  }
}
