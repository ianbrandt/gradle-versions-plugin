package com.github.benmanes.gradle.versions

import com.github.benmanes.gradle.versions.updates.Resolver
import org.gradle.testfixtures.ProjectBuilder
import spock.lang.Issue
import spock.lang.Specification

/**
 * A specification pinning that the producer's {@code checkVersionStability} opt-in ANDs the string
 * predicate onto the existing metadata-status check rather than replacing it: an Ivy module whose
 * published {@code status} is {@code "integration"} is rejected under a milestone revision even
 * though its version string alone reads stable.
 * https://github.com/ben-manes/gradle-versions-plugin/issues/550
 */
@Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/550')
final class ResolverIvyMetadataSpec extends Specification {
  private String ivyRepoUrl = getClass().getResource('/ivy/').toURI()

  private def project() {
    def project = ProjectBuilder.builder().withName('root').build()
    project.repositories {
      ivy { url ivyRepoUrl }
    }
    return project
  }

  /** Resolves {@code coordinate} the way the producer does, threading the opt-in through. */
  private String latestVersionOf(project, String coordinate, String revision, boolean checkVersionStability) {
    def configuration = project.configurations.create('app')
    project.dependencies.add('app', coordinate)
    def resolver = new Resolver(project, null, false)
    def statuses = resolver.'resolve$io_github_ben_manes_gradle_versions_plugin'(
      configuration, revision, false, false, checkVersionStability, { resolver.declaredKeys(configuration) })
    return statuses.first().latestVersion
  }

  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/550')
  def 'Under opt-in ON, an Ivy status="integration" version is rejected at revision=milestone despite a stable-looking string'() {
    given: 'ivy-status:2.0 is published with status="integration" though its own string reads stable'
    def proj = project()

    expect: 'the string half alone would accept 2.0 under milestone, but the metadata conjunct still rejects it'
    latestVersionOf(proj, 'com.probe:ivy-status:1.0', 'milestone', true) == '1.0'
  }
}
