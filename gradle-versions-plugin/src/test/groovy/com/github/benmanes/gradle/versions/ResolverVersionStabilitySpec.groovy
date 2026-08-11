package com.github.benmanes.gradle.versions

import com.github.benmanes.gradle.versions.updates.Resolver
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import spock.lang.Issue
import spock.lang.Specification

/**
 * A specification for the producer's {@code checkVersionStability} opt-in, which ANDs the string
 * stability predicate onto the existing metadata-status revision filter rather than replacing it.
 * https://github.com/ben-manes/gradle-versions-plugin/issues/550
 */
@Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/550')
final class ResolverVersionStabilitySpec extends Specification {
  @Rule final TemporaryFolder repoDir = new TemporaryFolder()
  private File repo

  def 'setup'() {
    repo = repoDir.newFolder('repository')
  }

  /** Writes a real pom for every version in {@code allVersions}, so a real candidate walk selects among them. */
  private void publishModule(String group, String artifact, List<String> allVersions) {
    def dir = new File(repo, "${group.replace('.', '/')}/${artifact}")
    dir.mkdirs()
    allVersions.each { version ->
      def versionDir = new File(dir, version)
      versionDir.mkdirs()
      new File(versionDir, "${artifact}-${version}.pom").text = """<?xml version="1.0" encoding="UTF-8"?>
        <project>
          <modelVersion>4.0.0</modelVersion>
          <groupId>${group}</groupId>
          <artifactId>${artifact}</artifactId>
          <version>${version}</version>
        </project>
      """.stripIndent()
    }
    def versionsXml = allVersions.collect { "<version>${it}</version>" }.join('\n      ')
    new File(dir, 'maven-metadata.xml').text = """<?xml version="1.0" encoding="UTF-8"?>
      <metadata>
        <groupId>${group}</groupId>
        <artifactId>${artifact}</artifactId>
        <versioning>
          <latest>${allVersions.first()}</latest>
          <release>${allVersions.first()}</release>
          <versions>
            ${versionsXml}
          </versions>
          <lastUpdated>20260810000000</lastUpdated>
        </versioning>
      </metadata>
    """.stripIndent()
  }

  private def project() {
    def project = ProjectBuilder.builder().withName('root').build()
    project.repositories {
      maven { url repo.toURI() }
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
  def 'At revision=release, opt-in OFF reports the pre-release candidate: today\'s behavior is unchanged'() {
    given: 'a listing whose newest candidate is a pre-release string a bare Maven pom reads as status "release"'
    publishModule('com.probe', 'unstable-ceiling', ['3.0-Beta1', '2.0', '1.0'])

    expect:
    latestVersionOf(project(), 'com.probe:unstable-ceiling:1.0', 'release', false) == '3.0-Beta1'
  }

  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/550')
  def 'At revision=release, opt-in ON reports the stable candidate instead'() {
    given:
    publishModule('com.probe', 'unstable-ceiling-opt-in', ['3.0-Beta1', '2.0', '1.0'])

    expect:
    latestVersionOf(project(), 'com.probe:unstable-ceiling-opt-in:1.0', 'release', true) == '2.0'
  }

  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/475')
  def 'The #475 snapshot-only exemption still holds under opt-in ON'() {
    given: 'the only published version is a snapshot the build already declares'
    publishModule('com.probe', 'snapshot-only-probe', ['1.0-SNAPSHOT'])

    expect: 'the declared version is exempt from the string check, so it is reported as the latest'
    latestVersionOf(project(), 'com.probe:snapshot-only-probe:1.0-SNAPSHOT', 'release', true) == '1.0-SNAPSHOT'
  }

  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/550')
  def 'A candidate literally versioned "none" is exempt from the string check under opt-in ON'() {
    given: 'an unversioned dependency constraint, which the producer queries with the literal version "none"'
    def proj = project()
    def configuration = proj.configurations.create('app')
    proj.dependencies.constraints.add('app', 'com.probe:unversioned-constraint')
    def resolver = new Resolver(proj, null, true)

    when:
    resolver.'resolve$io_github_ben_manes_gradle_versions_plugin'(
      configuration, 'release', false, false, true, { resolver.declaredKeys(configuration) })

    then: 'the "none" candidate is exempt from the string check rather than throwing while being judged'
    noExceptionThrown()
  }
}
