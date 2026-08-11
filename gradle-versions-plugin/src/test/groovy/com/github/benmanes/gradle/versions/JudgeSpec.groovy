package com.github.benmanes.gradle.versions

import com.github.benmanes.gradle.versions.updates.Judge
import com.github.benmanes.gradle.versions.updates.PartialStatus
import com.github.benmanes.gradle.versions.updates.resolutionstrategy.RecordedComponentSelection
import com.github.benmanes.gradle.versions.updates.resolutionstrategy.ResolutionStrategyWithCurrent
import org.gradle.api.Action
import org.gradle.api.logging.Logging
import spock.lang.Issue
import spock.lang.Specification

/**
 * A specification for the judge that replays the aggregating build's own component-selection
 * rules over each row's recorded candidates, starting at the row's baked verdict.
 * https://github.com/ben-manes/gradle-versions-plugin/issues/1058
 */
@Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/1058')
final class JudgeSpec extends Specification {
  private static final def LOGGER = Logging.getLogger(JudgeSpec)

  private static List<PartialStatus> judge(
    List<PartialStatus> statuses, Map<String, List<String>> candidates, Action strategy) {
    return new Judge(strategy, LOGGER).judge(statuses, candidates)
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
  def 'A candidate list that omits the walk verdict leaves the row unjudged'() {
    given: 'a rule that would reject the baked verdict if it ever ran, but the row has no recorded candidates'
    def status = statusOf('com.example', 'widget', '1.0', '2.0')
    def candidates = [':': []]

    when:
    def judged = judge([status], candidates, rejecting('2.0'))

    then: 'the baked verdict survives untouched, as the membership guard never ran the rule'
    judged == [status]
  }

  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/1058')
  def 'A predicate reading metadata or the descriptor gets null at the judge'() {
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
    def judged = judge([status], candidates, rejectAll)

    then:
    judged.size() == 1
    judged[0].latestVersion == 'none'
    judged[0].unresolved != null
    judged[0].unresolved.selectorGroup == 'com.example'
    judged[0].unresolved.selectorName == 'widget'
    judged[0].unresolved.failureText == 'rejected by the test rule'
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
    def judged = judge([status], candidates, rejectSilently)

    then:
    judged[0].unresolved.failureText == 'Rejected by the aggregating build\'s component selection rules'
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
    def judged = judge([status], candidates, strategy)

    then:
    judged[0].unresolved.failureText == 'first reason'
  }

  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/1058')
  def 'The reason reported names the ceiling candidate, not a lower one the walk also rejected'() {
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
    def judged = judge([status], candidates, strategy)

    then: 'the ceiling (3.0, the row\'s own baked verdict) names the reason, not 2.0 or 1.0'
    judged[0].unresolved.failureText == 'rejected 3.0'
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
    def judged = judge([core, coreExt], candidates, rejectCoreOnly)

    then: 'only the targeted module is capped; the one that merely shares its prefix is untouched'
    judged.find { it.name == 'core' }.latestVersion == '1.0'
    judged.find { it.name == 'core-ext' }.latestVersion == '2.0'
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
    def judged = judge([status], candidates, rejectOnNullMetadata)

    then: 'the row keeps the verdict its own build reached with the metadata the judge cannot read'
    judged[0].latestVersion == '3.0'
    judged[0].unresolved == null
  }

  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/1058')
  def 'Offers no candidate below one the rule rejected on the absent metadata'() {
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
    def judged = judge([status], candidates, strategy)

    then: 'the walk stops rather than offering 2.0, which the rule rejected as surely as 3.0'
    judged[0].latestVersion == '3.0'
    judged[0].unresolved == null
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
    def judged = judge([status], candidates, strategy)

    then: 'the metadata read by the earlier rule does not excuse the later rule from being applied'
    judged[0].latestVersion == '2.0'
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
    def judged = judge([status], candidates, rejectAll)

    then: 'the missing candidate list is read as "row not recorded", not as every candidate rejected'
    judged == [status]
  }

  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/1058')
  def "Candidates recorded by one project never judge another project's row"() {
    given: 'two projects declaring the same module; only one recorded a candidate list at all'
    def rowInA = statusOf('com.example', 'widget', '1.0', '2.0', ':a')
    def rowInB = statusOf('com.example', 'widget', '1.0', '2.0', ':b')
    def candidates = [
      ':a': ['com.example:widget:2.0', 'com.example:widget:1.0'],
      ':b': [],
    ]

    when:
    def judged = judge([rowInA, rowInB], candidates, rejecting('2.0'))

    then: "project :a's row is walked down by its own recorded candidates"
    judged.find { it.projectPath == ':a' }.latestVersion == '1.0'

    and: "project :b's row, which recorded none, is untouched by :a's candidates"
    judged.find { it.projectPath == ':b' }.latestVersion == '2.0'
  }

  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/1058')
  def 'The user action executes exactly once, regardless of how many rows are judged'() {
    given: 'a strategy that counts its own executions, judged over three rows'
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
    judge(rows, candidates, strategy)

    then:
    executions == 1
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
    judge([status], candidates, strategy)

    then:
    order == ['first', 'second']
  }
}
