package com.github.benmanes.gradle.versions

import static org.gradle.testkit.runner.TaskOutcome.SUCCESS

import groovy.json.JsonSlurper
import org.gradle.testkit.runner.GradleRunner
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import spock.lang.Issue
import spock.lang.Specification
import spock.lang.Unroll

/**
 * A specification for the {@code checkVersionStability} task property, and its
 * {@code -DcheckVersionStability} system property precedence, matching {@code revision}'s own.
 * https://github.com/ben-manes/gradle-versions-plugin/issues/550
 */
@Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/550')
final class CheckVersionStabilitySpec extends Specification {
  @Rule final TemporaryFolder testProjectDir = new TemporaryFolder()
  private File buildFile
  private List<File> pluginClasspath
  private String reportFolder
  private String classpathString
  private String mavenRepoUrl

  def 'setup'() {
    def pluginClasspathResource = getClass().classLoader.getResource("plugin-classpath.txt")
    if (pluginClasspathResource == null) {
      throw new IllegalStateException(
        "Did not find plugin classpath resource, run `testClasses` build task.")
    }

    pluginClasspath = pluginClasspathResource.readLines().collect { new File(it) }
    classpathString = pluginClasspath
      .collect { it.absolutePath.replace('\\', '\\\\') } // escape backslashes in Windows paths
      .collect { "'$it'" }
      .join(", ")
    reportFolder = "${testProjectDir.root.path.replaceAll("\\\\", '/')}/build/dependencyUpdates"
    mavenRepoUrl = getClass().getResource('/maven/').toURI()
  }

  private File writeBuildFile(String module = 'com.probe:unstable-ceiling') {
    def declared = module == 'com.example:snapshot-mixed' ? '1.0-SNAPSHOT' : '1.0'
    def file = testProjectDir.newFile('build.gradle')
    file <<
      """
        buildscript {
          dependencies {
            classpath files($classpathString)
          }
        }

        apply plugin: 'java'
        apply plugin: 'io.github.ben-manes.versions'

        repositories {
          maven {
            url '${mavenRepoUrl}'
          }
        }

        dependencies {
          implementation '${module}:${declared}'
        }

        tasks.named('dependencyUpdates').configure {
          outputFormatter = 'json'
          checkForGradleUpdate = false
        }
        """.stripIndent()
    return file
  }

  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/550')
  def '-DcheckVersionStability opts in with no task property set'() {
    given: 'a listing whose newest candidate is a pre-release string a bare Maven pom reads as status "release"'
    buildFile = writeBuildFile()

    when:
    def result = GradleRunner.create()
      .withProjectDir(testProjectDir.root)
      .withArguments('dependencyUpdates', '-Drevision=release', '-DcheckVersionStability')
      .withPluginClasspath()
      .build()
    def report = new JsonSlurper().parseText(new File(reportFolder, 'report.json').text)

    then: 'a bare flag with no value opts in, so the guard drops the pre-release ceiling to the stable candidate'
    result.task(':dependencyUpdates').outcome == SUCCESS
    report.outdated.dependencies*.name == ['unstable-ceiling']
    report.outdated.dependencies[0].available.release == '2.0'
  }

  @Unroll
  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/550')
  def 'Against a Maven-only graph, #module at revision #revision with stability #stability offers #expected'() {
    given: 'poms that carry no status, so Gradle calls every non-SNAPSHOT version a release'
    buildFile = writeBuildFile(module)

    when:
    def args = ['dependencyUpdates', "-Drevision=${revision}"]
    if (stability) {
      args += '-DcheckVersionStability'
    }
    def result = GradleRunner.create()
      .withProjectDir(testProjectDir.root)
      .withArguments(args as String[])
      .withPluginClasspath()
      .build()
    def report = new JsonSlurper().parseText(new File(reportFolder, 'report.json').text)

    then:
    result.task(':dependencyUpdates').outcome == SUCCESS
    report.outdated.dependencies[0].available[revision] == expected

    where:
    // 'unstable-ceiling' offers 1.0, 2.0 and the pre-release 3.0-Beta1; 'snapshot-mixed' offers
    // 1.0-SNAPSHOT, 1.5 and 2.0-SNAPSHOT. The guard narrows exactly one combination: a release
    // revision whose ceiling is a pre-release string that a bare pom leaves indistinguishable from
    // a release. It is inert everywhere the version string already meets the revision asked for,
    // including a Beta under 'milestone', which is the level that admits one.
    module                       | revision      | stability || expected
    'com.probe:unstable-ceiling' | 'release'     | false     || '3.0-Beta1'
    'com.probe:unstable-ceiling' | 'release'     | true      || '2.0'
    'com.probe:unstable-ceiling' | 'milestone'   | false     || '3.0-Beta1'
    'com.probe:unstable-ceiling' | 'milestone'   | true      || '3.0-Beta1'
    'com.probe:unstable-ceiling' | 'integration' | false     || '3.0-Beta1'
    'com.probe:unstable-ceiling' | 'integration' | true      || '3.0-Beta1'
    'com.example:snapshot-mixed' | 'release'     | false     || '1.5'
    'com.example:snapshot-mixed' | 'release'     | true      || '1.5'
    'com.example:snapshot-mixed' | 'milestone'   | false     || '1.5'
    'com.example:snapshot-mixed' | 'milestone'   | true      || '1.5'
    'com.example:snapshot-mixed' | 'integration' | false     || '2.0-SNAPSHOT'
    'com.example:snapshot-mixed' | 'integration' | true      || '2.0-SNAPSHOT'
  }

  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/550')
  def 'With no system property or task property, opt-in stays off'() {
    given:
    buildFile = writeBuildFile()

    when:
    def result = GradleRunner.create()
      .withProjectDir(testProjectDir.root)
      .withArguments('dependencyUpdates', '-Drevision=release')
      .withPluginClasspath()
      .build()
    def report = new JsonSlurper().parseText(new File(reportFolder, 'report.json').text)

    then: "today's byte-for-byte default: the metadata-only check alone accepts the pre-release"
    result.task(':dependencyUpdates').outcome == SUCCESS
    report.outdated.dependencies*.name == ['unstable-ceiling']
    report.outdated.dependencies[0].available.release == '3.0-Beta1'
  }
}
