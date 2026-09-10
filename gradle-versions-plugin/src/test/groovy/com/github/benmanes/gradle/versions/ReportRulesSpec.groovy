package com.github.benmanes.gradle.versions

import com.github.benmanes.gradle.versions.updates.ReportRules
import com.github.benmanes.gradle.versions.updates.PartialStatus
import com.github.benmanes.gradle.versions.updates.resolutionstrategy.ComponentFilter
import com.github.benmanes.gradle.versions.updates.resolutionstrategy.RecordedComponentSelection
import com.github.benmanes.gradle.versions.updates.resolutionstrategy.ResolutionStrategyWithCurrent
import org.gradle.api.Action
import org.gradle.api.logging.Logging
import org.gradle.api.specs.Spec
import spock.lang.Issue
import spock.lang.Specification
import spock.lang.Unroll

/**
 * A specification for ReportRules, which replays the aggregating build's own component-selection
 * rules over each row's recorded candidates, starting at the row's baked verdict.
 * https://github.com/ben-manes/gradle-versions-plugin/issues/1058
 */
@Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/1058')
final class ReportRulesSpec extends Specification {
  private static final def LOGGER = Logging.getLogger(ReportRulesSpec)

  private static List<PartialStatus> applyRules(
    List<PartialStatus> statuses, Map<String, List<String>> candidates, Action strategy,
    String revision = 'milestone', boolean rejectPreReleases = false,
    Spec<String> preReleaseVersionIf = null, ComponentFilter exemptFromBuiltInChecksIf = null) {
    return new ReportRules(strategy, LOGGER, revision, rejectPreReleases, preReleaseVersionIf,
      exemptFromBuiltInChecksIf).applyTo(statuses, candidates)
  }

  private static PartialStatus statusOf(
    String group, String name, String declaredVersion, String latestVersion, String projectPath = ':') {
    return new PartialStatus(group, name, declaredVersion, null, latestVersion, null, null, false, [], projectPath)
  }

  private static Action<ResolutionStrategyWithCurrent> rejecting(String rejectedVersion) {
    return { ResolutionStrategyWithCurrent strategy ->
      strategy.componentSelection { rules ->
        rules.all { selection ->
          if (selection.candidate.version == rejectedVersion) {
            selection.reject('rejected by the test rule')
          }
        }
      }
    } as Action<ResolutionStrategyWithCurrent>
  }

  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/1058')
  def 'A candidate list that omits the walk verdict leaves the row undecided'() {
    given: 'a rule that would reject the baked verdict if it ever ran, but the row has no recorded candidates'
    def status = statusOf('com.example', 'widget', '1.0', '2.0')
    def candidates = [':': []]

    when:
    def applied = applyRules([status], candidates, rejecting('2.0'))

    then: 'the baked verdict survives untouched, as the membership guard never ran the rule'
    applied == [status]
  }

  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/1058')
  def 'A predicate reading metadata or the descriptor is answered null at the report'() {
    given:
    def selection = new RecordedComponentSelection('com.example', 'widget', '2.0')

    expect:
    selection.metadata == null
    selection.getDescriptor(String) == null
  }

  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/1058')
  def 'Reports a row unresolved when every candidate is rejected'() {
    given: 'a rule that rejects every candidate the row recorded'
    def status = statusOf('com.example', 'widget', '1.0', '3.0')
    def candidates = [':': ['com.example:widget:3.0', 'com.example:widget:2.0', 'com.example:widget:1.0']]
    def rejectAll = { ResolutionStrategyWithCurrent strategy ->
      strategy.componentSelection { rules ->
        rules.all { selection -> selection.reject('rejected by the test rule') }
      }
    } as Action<ResolutionStrategyWithCurrent>

    when:
    def applied = applyRules([status], candidates, rejectAll)

    then:
    applied.size() == 1
    applied[0].latestVersion == 'none'
    applied[0].unresolved != null
    applied[0].unresolved.selectorGroup == 'com.example'
    applied[0].unresolved.selectorName == 'widget'
    applied[0].unresolved.failureText == 'rejected by the test rule'
  }

  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/1058')
  def 'Falls back to the fixed text when the rejecting rule gave no reason'() {
    given: 'a rule that rejects every candidate with an empty reason'
    def status = statusOf('com.example', 'widget', '1.0', '2.0')
    def candidates = [':': ['com.example:widget:2.0', 'com.example:widget:1.0']]
    def rejectSilently = { ResolutionStrategyWithCurrent strategy ->
      strategy.componentSelection { rules ->
        rules.all { selection -> selection.reject('') }
      }
    } as Action<ResolutionStrategyWithCurrent>

    when:
    def applied = applyRules([status], candidates, rejectSilently)

    then:
    applied[0].unresolved.failureText == 'Rejected by the aggregating build\'s component selection rules'
  }

  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/1058')
  def 'The first rule to reject a candidate supplies the reason, not a later rule that never ran'() {
    given: 'two rules over the same candidate; the second never runs once the first has rejected'
    def status = statusOf('com.example', 'widget', '1.0', '2.0')
    def candidates = [':': ['com.example:widget:2.0', 'com.example:widget:1.0']]
    def strategy = { ResolutionStrategyWithCurrent rs ->
      rs.componentSelection { rules ->
        rules.all { selection -> selection.reject('first reason') }
        rules.all { selection -> selection.reject('second reason') }
      }
    } as Action<ResolutionStrategyWithCurrent>

    when:
    def applied = applyRules([status], candidates, strategy)

    then:
    applied[0].unresolved.failureText == 'first reason'
  }

  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/1058')
  def 'The reason reported is for the ceiling candidate rather than a lower one also rejected'() {
    given: 'a rule that rejects three candidates, each with its own reason'
    def status = statusOf('com.example', 'widget', '1.0', '3.0')
    def candidates =
      [':': ['com.example:widget:3.0', 'com.example:widget:2.0', 'com.example:widget:1.0']]
    def strategy = { ResolutionStrategyWithCurrent rs ->
      rs.componentSelection { rules ->
        rules.all { selection ->
          selection.reject("rejected ${selection.candidate.version}".toString())
        }
      }
    } as Action<ResolutionStrategyWithCurrent>

    when:
    def applied = applyRules([status], candidates, strategy)

    then: 'the ceiling (3.0, the row\'s own baked verdict) names the reason, not 2.0 or 1.0'
    applied[0].unresolved.failureText == 'rejected 3.0'
  }

  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/1058')
  def 'Matches recorded candidates by module, not by prefix'() {
    given: 'two modules whose names share a prefix, only one of which the rule targets'
    def core = statusOf('com.example', 'core', '1.0', '2.0')
    def coreExt = statusOf('com.example', 'core-ext', '1.0', '2.0')
    def candidates = [
      ':': [
        'com.example:core:2.0', 'com.example:core:1.0',
        'com.example:core-ext:2.0', 'com.example:core-ext:1.0',
      ],
    ]
    def rejectCoreOnly = { ResolutionStrategyWithCurrent strategy ->
      strategy.componentSelection { rules ->
        rules.withModule('com.example:core') { selection ->
          if (selection.candidate.version == '2.0') {
            selection.reject('rejected by the test rule')
          }
        }
      }
    } as Action<ResolutionStrategyWithCurrent>

    when:
    def applied = applyRules([core, coreExt], candidates, rejectCoreOnly)

    then: 'only the targeted module is capped; the one that merely shares its prefix is untouched'
    applied.find { it.name == 'core' }.latestVersion == '1.0'
    applied.find { it.name == 'core-ext' }.latestVersion == '2.0'
  }

  @Unroll
  def 'Matches a module notation written as #label'() {
    given: 'a rule targeting one module, its notation spelled the way a build script may spell it'
    def core = statusOf('com.example', 'core', '1.0', '2.0')
    def candidates = [':': ['com.example:core:2.0', 'com.example:core:1.0']]
    def rejectCore = { ResolutionStrategyWithCurrent strategy ->
      strategy.componentSelection { rules ->
        rules.withModule(notation) { selection ->
          if (selection.candidate.version == '2.0') {
            selection.reject('rejected by the test rule')
          }
        }
      }
    } as Action<ResolutionStrategyWithCurrent>

    when:
    def applied = applyRules([core], candidates, rejectCore)

    then: 'an interpolated notation is a GString rather than a String, and Gradle accepts either'
    applied[0].latestVersion == '1.0'

    where:
    label       | notation
    'a String'  | 'com.example:core'
    'a GString' | "${'com.example'}:core"
  }

  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/1058')
  def 'Ignores a rejection that the rule made after reading the absent metadata'() {
    given: 'a rule rejecting every candidate because the record answers its metadata with null'
    def status = statusOf('com.example', 'widget', '1.0', '3.0')
    def candidates = [':': ['com.example:widget:3.0', 'com.example:widget:2.0']]
    def rejectOnNullMetadata = { ResolutionStrategyWithCurrent strategy ->
      strategy.componentSelection { rules ->
        rules.all { selection ->
          if (selection.metadata == null) {
            selection.reject('rejected by the test rule')
          }
        }
      }
    } as Action<ResolutionStrategyWithCurrent>

    when:
    def applied = applyRules([status], candidates, rejectOnNullMetadata)

    then: 'the row keeps the verdict its own build reached with the metadata the report cannot read'
    applied[0].latestVersion == '3.0'
    applied[0].unresolved == null
  }

  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/1058')
  def 'Shows no candidate below one the rule rejected on the absent metadata'() {
    given: 'a rule rejecting the verdict by version, and everything under it by its null metadata'
    def status = statusOf('com.example', 'widget', '1.0', '3.0')
    def candidates =
      [':': ['com.example:widget:3.0', 'com.example:widget:2.0', 'com.example:widget:1.0']]
    // Groovy short circuits, so the verdict is rejected without the metadata ever being read.
    def strategy = { ResolutionStrategyWithCurrent rs ->
      rs.componentSelection { rules ->
        rules.all { selection ->
          if (selection.candidate.version == '3.0' || selection.metadata == null) {
            selection.reject('rejected by the test rule')
          }
        }
      }
    } as Action<ResolutionStrategyWithCurrent>

    when:
    def applied = applyRules([status], candidates, strategy)

    then: 'the walk stops rather than offering 2.0, which the rule rejected as surely as 3.0'
    applied[0].latestVersion == '3.0'
    applied[0].unresolved == null
  }

  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/1058')
  def 'Honors a later rule that rejects on the version after an earlier one read the metadata'() {
    given: 'a metadata reading rule that accepts, followed by one rejecting the verdict by version'
    def status = statusOf('com.example', 'widget', '1.0', '3.0')
    def candidates = [':': ['com.example:widget:3.0', 'com.example:widget:2.0']]
    def strategy = { ResolutionStrategyWithCurrent rs ->
      rs.componentSelection { rules ->
        rules.all { selection -> selection.metadata }
        rules.all { selection ->
          if (selection.candidate.version == '3.0') {
            selection.reject('rejected by the test rule')
          }
        }
      }
    } as Action<ResolutionStrategyWithCurrent>

    when:
    def applied = applyRules([status], candidates, strategy)

    then: 'the metadata read by the earlier rule does not excuse the later rule from being applied'
    applied[0].latestVersion == '2.0'
  }

  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/1058')
  def 'A v1 partial with no candidates list leaves every row at its baked verdict'() {
    given: 'a partial from an older release, whose projectPath has no entry in the candidates map at all'
    def status = statusOf('com.example', 'widget', '1.0', '2.0')
    def candidates = [:]
    def rejectAll = { ResolutionStrategyWithCurrent strategy ->
      strategy.componentSelection { rules ->
        rules.all { selection -> selection.reject('rejected by the test rule') }
      }
    } as Action<ResolutionStrategyWithCurrent>

    when:
    def applied = applyRules([status], candidates, rejectAll)

    then: 'the missing candidate list is read as "row not recorded", not as every candidate rejected'
    applied == [status]
  }

  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/1058')
  def "Candidates recorded by one project never decide another project's row"() {
    given: 'two projects declaring the same module; only one recorded a candidate list at all'
    def rowInA = statusOf('com.example', 'widget', '1.0', '2.0', ':a')
    def rowInB = statusOf('com.example', 'widget', '1.0', '2.0', ':b')
    def candidates = [
      ':a': ['com.example:widget:2.0', 'com.example:widget:1.0'],
      ':b': [],
    ]

    when:
    def applied = applyRules([rowInA, rowInB], candidates, rejecting('2.0'))

    then: "project :a's row is walked down by its own recorded candidates"
    applied.find { it.projectPath == ':a' }.latestVersion == '1.0'

    and: "project :b's row, which recorded none, is untouched by :a's candidates"
    applied.find { it.projectPath == ':b' }.latestVersion == '2.0'
  }

  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/1058')
  def 'The user action executes exactly once, regardless of how many rows are applied'() {
    given: 'a strategy that counts its own executions, applied over three rows'
    def rows = [
      statusOf('com.example', 'a', '1.0', '2.0'),
      statusOf('com.example', 'b', '1.0', '2.0'),
      statusOf('com.example', 'c', '1.0', '2.0'),
    ]
    def candidates = [':': []]
    def executions = 0
    def strategy = { ResolutionStrategyWithCurrent strategy ->
      executions++
      strategy.componentSelection { rules -> rules.all { selection -> } }
    } as Action<ResolutionStrategyWithCurrent>

    when:
    applyRules(rows, candidates, strategy)

    then:
    executions == 1
  }

  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/550')
  def 'Shows no integration candidate below the ceiling under a milestone revision'() {
    given: 'a listing whose snapshot sits between the rejected ceiling and the release below it'
    def status = statusOf('com.example', 'widget', '1.0', '3.0')
    def candidates = [
      ':': ['com.example:widget:3.0', 'com.example:widget:2.5-SNAPSHOT', 'com.example:widget:2.0'],
    ]

    when:
    def applied = applyRules([status], candidates, rejecting('3.0'), 'milestone')

    then: 'the walk steps over the snapshot that a milestone report may not offer'
    applied[0].latestVersion == '2.0'
  }

  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/798')
  def 'Shows no snapshot candidate below the ceiling under a release revision'() {
    given: 'a snapshot and a jre qualified release below the ceiling the rule rejected'
    def status = statusOf('com.example', 'widget', '1.0', '3.0')
    def candidates = [
      ':': ['com.example:widget:3.0', 'com.example:widget:2.5-SNAPSHOT', 'com.example:widget:2.0.jre11'],
    ]

    when:
    def applied = applyRules([status], candidates, rejecting('3.0'), 'release')

    then: 'the snapshot is stepped over and the jre qualified version is offered instead'
    applied[0].latestVersion == '2.0.jre11'
  }

  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/475')
  def "The row's own version is offerable below the ceiling however unstable it reads"() {
    given: 'a build already on a snapshot, under a revision that rejects every other snapshot'
    def status = statusOf('com.example', 'widget', '1.0-SNAPSHOT', '3.0')
    def candidates = [
      ':': ['com.example:widget:3.0', 'com.example:widget:2.0-SNAPSHOT', 'com.example:widget:1.0-SNAPSHOT'],
    ]

    when:
    def applied = applyRules([status], candidates, rejecting('3.0'), 'release')

    then: 'the newer snapshot is stepped over, but the version the build declares is exempt'
    applied[0].latestVersion == '1.0-SNAPSHOT'
  }

  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/550')
  def 'Leaves the ceiling as its own build baked it, whatever its version string reads as'() {
    given: 'a snapshot ceiling the revision would reject below it, and a rule that rejects nothing'
    def status = statusOf('com.example', 'widget', '1.0', '2.0-SNAPSHOT')
    def candidates = [':': ['com.example:widget:2.0-SNAPSHOT', 'com.example:widget:1.5']]

    when:
    def applied = applyRules([status], candidates, rejecting('9.9'), 'release')

    then: 'the revision guard applies below the ceiling only, so the baked verdict is untouched'
    applied[0].latestVersion == '2.0-SNAPSHOT'
    applied[0].unresolved == null
  }

  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/550')
  def 'Rechecks the ceiling itself once the report supplies a rule of its own'() {
    given: 'a snapshot ceiling a report rule rejects, and a release below it'
    def status = statusOf('com.example', 'widget', '1.0', '2.0-SNAPSHOT')
    def candidates = [':': ['com.example:widget:2.0-SNAPSHOT', 'com.example:widget:1.5']]

    when:
    def applied = applyRules([status], candidates, rejecting('2.0-SNAPSHOT'), 'release')

    then: 'the rule reaches the ceiling as well as the candidates below it, and the row drops to the release'
    applied[0].latestVersion == '1.5'
    applied[0].unresolved == null
  }

  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/550')
  def "Reports the rule that rejected every candidate starting at the ceiling"() {
    given: 'a listing of snapshots only, all rejected by a rule of the report\'s own'
    def status = statusOf('com.example', 'widget', '1.0', '2.0-SNAPSHOT')
    def candidates = [':': ['com.example:widget:2.0-SNAPSHOT', 'com.example:widget:1.5-SNAPSHOT']]
    def rejectAll = { ResolutionStrategyWithCurrent strategy ->
      strategy.componentSelection { rules ->
        rules.all { selection -> selection.reject('rejected by the test rule') }
      }
    } as Action<ResolutionStrategyWithCurrent>

    when:
    def applied = applyRules([status], candidates, rejectAll, 'release')

    then: 'the exhausted walk names the ceiling\'s own rejection reason'
    applied[0].latestVersion == 'none'
    applied[0].unresolved.selectorVersion == '2.0-SNAPSHOT'
    applied[0].unresolved.failureText == 'rejected by the test rule'
  }

  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/1058')
  def 'Replays rules in registration order'() {
    given: 'two rules that each record their own invocation, in the order the build registered them'
    def status = statusOf('com.example', 'widget', '1.0', '2.0')
    def candidates = [':': ['com.example:widget:2.0', 'com.example:widget:1.0']]
    def order = []
    def strategy = { ResolutionStrategyWithCurrent rs ->
      rs.componentSelection { rules ->
        rules.all { order << 'first' }
        rules.all { order << 'second' }
      }
    } as Action<ResolutionStrategyWithCurrent>

    when:
    applyRules([status], candidates, strategy)

    then:
    order == ['first', 'second']
  }

  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/1058')
  def 'Walks by version order rather than the order the repositories were recorded in'() {
    given: 'a listing recorded per repository, so the verdict trails candidates older than it'
    def status = statusOf('com.example', 'widget', '1.0', '3.0-Beta1')
    def candidates =
      [':': ['com.example:widget:2.0', 'com.example:widget:1.0', 'com.example:widget:3.0-Beta1']]

    when:
    def applied = applyRules([status], candidates, rejecting('3.0-Beta1'))

    then: 'the walk steps to the newest candidate below the verdict, not to the end of the list'
    applied[0].latestVersion == '2.0'
    applied[0].unresolved == null
  }

  def "The report's pre-release check rejects the ceiling its producer accepted"() {
    given: 'a row whose producer baked a pre-release, the report checking pre-releases itself'
    def statuses = [statusOf('com.probe', 'unstable-ceiling', '1.0', '3.0-Beta1')]
    def candidates = [':': ['com.probe:unstable-ceiling:3.0-Beta1', 'com.probe:unstable-ceiling:2.0',
                            'com.probe:unstable-ceiling:1.0']]

    when:
    def applied = applyRules(statuses, candidates, null, 'milestone', true)

    then: 'the walk steps down to the newest candidate the check accepts'
    applied[0].latestVersion == '2.0'
    applied[0].unresolved == null
  }

  def "The report's own convention is part of the check it applies"() {
    given: 'versions no built-in marker covers, and a convention that names them'
    def statuses = [statusOf('com.example', 'prerelease-flagged', '1.0', '3.0-flagged')]
    def candidates = [':': ['com.example:prerelease-flagged:3.0-flagged',
                            'com.example:prerelease-flagged:2.0-flagged',
                            'com.example:prerelease-flagged:1.0']]

    when:
    def applied = applyRules(statuses, candidates, null, 'milestone', true,
      { String version -> version.endsWith('-flagged') } as Spec<String>)

    then: 'both flagged candidates are held, leaving the version the build already declares'
    applied[0].latestVersion == '1.0'
    applied[0].unresolved == null
  }

  def "An exemption keeps a candidate the report's check would reject"() {
    given: 'the same pre-release ceiling, with the module exempted from the built-in checks'
    def statuses = [statusOf('com.probe', 'unstable-ceiling', '1.0', '3.0-Beta1')]
    def candidates = [':': ['com.probe:unstable-ceiling:3.0-Beta1', 'com.probe:unstable-ceiling:2.0']]

    when:
    def applied = applyRules(statuses, candidates, null, 'milestone', true, null,
      { current -> current.candidate.module == 'unstable-ceiling' } as ComponentFilter)

    then: 'the ceiling stands, as it does for a build that exempts the module at its producer'
    applied[0].latestVersion == '3.0-Beta1'
  }

  def 'An exemption answering on the absent metadata leaves the row as its producer reported it'() {
    given: 'an exemption that reads the metadata a recorded candidate never carries'
    def statuses = [statusOf('com.probe', 'unstable-ceiling', '1.0', '3.0-Beta1')]
    def candidates = [':': ['com.probe:unstable-ceiling:3.0-Beta1', 'com.probe:unstable-ceiling:2.0',
                            'com.probe:unstable-ceiling:1.0']]

    when:
    def applied = applyRules(statuses, candidates, null, 'milestone', true, null,
      { current -> current.metadata != null } as ComponentFilter)

    then: 'the predicate applied the record rather than the candidate, so the ceiling stands'
    applied[0].latestVersion == '3.0-Beta1'
    applied[0].unresolved == null
  }

  def 'A build already on a pre-release is still offered a newer one'() {
    given: 'the declared version is itself a pre-release'
    def statuses = [statusOf('com.probe', 'unstable-ceiling', '3.0-Beta1', '3.0-Beta1')]
    def candidates = [':': ['com.probe:unstable-ceiling:3.0-Beta1', 'com.probe:unstable-ceiling:2.0']]

    when:
    def applied = applyRules(statuses, candidates, null, 'milestone', true)

    then: 'the check reads both versions, so nothing is held back'
    applied[0].latestVersion == '3.0-Beta1'
  }
}
