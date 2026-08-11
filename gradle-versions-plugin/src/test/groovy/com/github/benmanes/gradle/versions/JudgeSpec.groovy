package com.github.benmanes.gradle.versions

import com.github.benmanes.gradle.versions.updates.Judge
import com.github.benmanes.gradle.versions.updates.PartialStatus
import com.github.benmanes.gradle.versions.updates.resolutionstrategy.RecordedComponentSelection
import com.github.benmanes.gradle.versions.updates.resolutionstrategy.ResolutionStrategyWithCurrent
import org.gradle.api.Action
import spock.lang.Issue
import spock.lang.Specification

/**
 * A specification for the judge that replays the aggregating build's own component-selection
 * rules over each row's recorded candidates, starting at the row's baked verdict.
 * https://github.com/ben-manes/gradle-versions-plugin/issues/1058
 */
@Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/1058')
final class JudgeSpec extends Specification {
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
    def judged = Judge.INSTANCE.judge([status], candidates, rejecting('2.0'))

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
    def judged = Judge.INSTANCE.judge([status], candidates, rejectAll)

    then:
    judged.size() == 1
    judged[0].latestVersion == 'none'
    judged[0].unresolved != null
    judged[0].unresolved.selectorGroup == 'com.example'
    judged[0].unresolved.selectorName == 'widget'
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
    def judged = Judge.INSTANCE.judge([core, coreExt], candidates, rejectCoreOnly)

    then: 'only the targeted module is capped; the one that merely shares its prefix is untouched'
    judged.find { it.name == 'core' }.latestVersion == '1.0'
    judged.find { it.name == 'core-ext' }.latestVersion == '2.0'
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
    Judge.INSTANCE.judge([status], candidates, strategy)

    then:
    order == ['first', 'second']
  }
}
