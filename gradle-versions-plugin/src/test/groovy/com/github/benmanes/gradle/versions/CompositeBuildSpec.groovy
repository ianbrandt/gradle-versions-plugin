package com.github.benmanes.gradle.versions

import static org.gradle.testkit.runner.TaskOutcome.SUCCESS

import groovy.json.JsonSlurper
import groovy.xml.XmlParser
import org.gradle.testkit.runner.GradleRunner
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import spock.lang.Issue
import spock.lang.Requires
import spock.lang.Specification
import spock.lang.Unroll

@Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/1004')
final class CompositeBuildSpec extends Specification {
  @Rule final TemporaryFolder testProjectDir = new TemporaryFolder()
  private String classpathString
  private String mavenRepoUrl

  def 'setup'() {
    def pluginClasspathResource = getClass().classLoader.getResource('plugin-classpath.txt')
    if (pluginClasspathResource == null) {
      throw new IllegalStateException(
        'Did not find plugin classpath resource, run `testClasses` build task.')
    }
    classpathString = pluginClasspathResource.readLines()
      .collect { it.replace('\\', '\\\\') } // escape backslashes in Windows paths
      .collect { "'$it'" }
      .join(', ')
    mavenRepoUrl = getClass().getResource('/maven/').toURI()
  }

  private def run(String... arguments) {
    return GradleRunner.create()
      .withProjectDir(testProjectDir.root)
      .withArguments(arguments)
      .withPluginClasspath()
      .build()
  }

  private void includedBuild(String name, String buildScript = '') {
    testProjectDir.newFolder(name)
    testProjectDir.newFile("$name/settings.gradle") << "rootProject.name = '$name'"
    testProjectDir.newFile("$name/build.gradle") << buildScript
  }

  def 'Reports the updates of a build that includes another build'() {
    given:
    testProjectDir.newFile('settings.gradle') << "includeBuild 'child'"
    testProjectDir.newFile('build.gradle') <<
      """
        plugins {
          id 'java-library'
          id 'io.github.ben-manes.versions'
        }

        repositories {
          maven {
            url '${mavenRepoUrl}'
          }
        }

        dependencies {
          api 'com.google.inject:guice:2.0'
        }
      """.stripIndent()
    includedBuild('child')

    when:
    def result = run('dependencyUpdates')

    then:
    result.task(':dependencyUpdates').outcome == SUCCESS
    result.output.contains('com.google.inject:guice [2.0 -> 3.1]')
  }

  def 'Aggregates the updates of every project in a build that includes another build'() {
    given:
    testProjectDir.newFile('settings.gradle') <<
      """
        include 'app', 'lib'
        includeBuild 'child'
      """.stripIndent()
    testProjectDir.newFile('build.gradle') <<
      """
        plugins {
          id 'io.github.ben-manes.versions'
        }

        allprojects {
          apply plugin: 'java'

          repositories {
            maven {
              url '${mavenRepoUrl}'
            }
          }
        }
      """.stripIndent()
    testProjectDir.newFolder('app')
    testProjectDir.newFile('app/build.gradle') <<
      """
        dependencies {
          implementation 'com.google.inject:guice:2.0'
        }
      """.stripIndent()
    testProjectDir.newFolder('lib')
    testProjectDir.newFile('lib/build.gradle') <<
      """
        dependencies {
          implementation 'com.google.guava:guava:15.0'
        }
      """.stripIndent()
    includedBuild('child')

    when:
    def result = run('dependencyUpdates')

    then:
    result.task(':dependencyUpdates').outcome == SUCCESS
    result.output.contains('com.google.inject:guice [2.0 -> 3.1]')
    result.output.contains('com.google.guava:guava [15.0 -> 16.0]')
  }

  def 'Reports the updates of a build that consumes the build it includes'() {
    given:
    testProjectDir.newFile('settings.gradle') << "includeBuild 'child'"
    testProjectDir.newFile('build.gradle') <<
      """
        plugins {
          id 'java-library'
          id 'io.github.ben-manes.versions'
        }

        repositories {
          maven {
            url '${mavenRepoUrl}'
          }
        }

        dependencies {
          api 'com.example:child:1.0'
          api 'com.google.inject:guice:2.0'
        }
      """.stripIndent()
    includedBuild(
      'child',
      """
        plugins {
          id 'java-library'
        }

        group = 'com.example'
        version = '1.0'
      """.stripIndent(),
    )

    when:
    def result = run('dependencyUpdates')

    then:
    result.task(':dependencyUpdates').outcome == SUCCESS
    result.output.contains('com.google.inject:guice [2.0 -> 3.1]')
  }

  @Unroll
  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/1095')
  def 'Reports the project url of a module #scenario'() {
    given:
    testProjectDir.newFile('settings.gradle') << settings
    testProjectDir.newFile('build.gradle') <<
      """
        plugins {
          id 'java-library'
          id 'io.github.ben-manes.versions'
        }

        repositories {
          maven {
            url '${mavenRepoUrl}'
          }
        }

        dependencies {
          implementation 'com.example:interpolated-url:1.0'
        }
      """.stripIndent()
    includedBuild(
      'interpolated-url',
      """
        plugins {
          id 'java-library'
        }

        group = 'com.example'
        version = '1.0'
      """.stripIndent(),
    )

    when:
    def result = run('dependencyUpdates', '-DoutputFormatter=json')
    def jsonReport = new JsonSlurper()
      .parse(new File(testProjectDir.root, 'build/dependencyUpdates/report.json'))

    then:
    result.task(':dependencyUpdates').outcome == SUCCESS
    def dependency = jsonReport.current.dependencies.find { it.name == 'interpolated-url' }
    dependency != null
    dependency.projectUrl == projectUrl

    where:
    scenario                        | settings                         | projectUrl
    'a repository publishes'        | ''                               | 'https://example.com/com.example/interpolated-url/1.0'
    'an included build substitutes' | "includeBuild 'interpolated-url'" | null
  }

  @Unroll
  @Issue([
    'https://github.com/ben-manes/gradle-versions-plugin/issues/781',
    'https://github.com/ben-manes/gradle-versions-plugin/issues/1004',
  ])
  def 'Reports the updates of a composite build using strict locking activated by #activation'() {
    given:
    testProjectDir.newFile('settings.gradle') << "includeBuild 'child'"
    testProjectDir.newFile('build.gradle') <<
      """
        plugins {
          id 'java-library'
          id 'io.github.ben-manes.versions'
        }

        repositories {
          maven {
            url '${mavenRepoUrl}'
          }
        }

        dependencyLocking {
          lockMode = LockMode.STRICT
        }

        ${script}

        dependencies {
          api 'com.google.inject:guice:2.0'
        }
      """.stripIndent()
    includedBuild('child')

    when:
    def result = run('dependencyUpdates')

    then:
    result.task(':dependencyUpdates').outcome == SUCCESS
    result.output.contains('com.google.inject:guice [2.0 -> 3.1]')

    where:
    activation              | script
    'a top-level hook'      | 'configurations.all { resolutionStrategy.activateDependencyLocking() }'
    'an afterEvaluate hook' | 'afterEvaluate { configurations.all { resolutionStrategy.activateDependencyLocking() } }'
  }

  // The composite computes its task graph under configure on demand before projectsEvaluated
  // fires, so no lifecycle callback can mutate the results strategy in time.
  private void compositeUsingConfigureOnDemand() {
    testProjectDir.newFile('settings.gradle') << "includeBuild 'child'"
    testProjectDir.newFile('build.gradle') <<
      """
        plugins {
          id 'java-library'
          id 'io.github.ben-manes.versions'
        }

        repositories {
          maven {
            url '${mavenRepoUrl}'
          }
        }

        dependencies {
          api 'com.google.inject:guice:2.0'
        }
      """.stripIndent()
    includedBuild('child')
  }

  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/1006')
  def 'Reports the updates of a composite build with configure on demand'() {
    given:
    compositeUsingConfigureOnDemand()

    when:
    def result = run(':dependencyUpdates', '--configure-on-demand')

    then:
    result.task(':dependencyUpdates').outcome == SUCCESS
    result.output.contains('com.google.inject:guice [2.0 -> 3.1]')
  }

  // Gradle 9 requires JVM 17. The guard that rejects the mutation is worded differently there,
  // and only this combination matches a build that sets all three properties in gradle.properties.
  @Requires({ jvm.java17Compatible })
  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/1006')
  def 'Reports the updates of a composite build with configure on demand in parallel'() {
    given:
    compositeUsingConfigureOnDemand()

    when:
    def result = GradleRunner.create()
      .withGradleVersion(GradleVersions.CURRENT)
      .withProjectDir(testProjectDir.root)
      .withArguments(':dependencyUpdates', '--configure-on-demand', '--parallel',
        '--configuration-cache')
      .withPluginClasspath()
      .build()

    then:
    result.task(':dependencyUpdates').outcome == SUCCESS
    result.output.contains('com.google.inject:guice [2.0 -> 3.1]')
  }

  def 'Reports the updates of an included build from the including build'() {
    given:
    testProjectDir.newFile('settings.gradle') << "includeBuild 'child'"
    testProjectDir.newFile('build.gradle') << ''
    includedBuild(
      'child',
      """
        buildscript {
          dependencies {
            classpath files($classpathString)
          }
        }

        apply plugin: 'java-library'
        apply plugin: 'io.github.ben-manes.versions'

        repositories {
          maven {
            url '${mavenRepoUrl}'
          }
        }

        dependencies {
          api 'com.google.guava:guava:15.0'
        }
      """.stripIndent(),
    )

    when:
    def result = run(':child:dependencyUpdates')

    then:
    result.task(':child:dependencyUpdates').outcome == SUCCESS
    result.output.contains('com.google.guava:guava [15.0 -> 16.0]')
  }

  def 'Reports the updates of both builds when each applies the plugin'() {
    given:
    testProjectDir.newFile('settings.gradle') << "includeBuild 'child'"
    testProjectDir.newFile('build.gradle') <<
      """
        plugins {
          id 'java-library'
          id 'io.github.ben-manes.versions'
        }

        repositories {
          maven {
            url '${mavenRepoUrl}'
          }
        }

        dependencies {
          api 'com.google.inject:guice:2.0'
        }
      """.stripIndent()
    includedBuild(
      'child',
      """
        buildscript {
          dependencies {
            classpath files($classpathString)
          }
        }

        apply plugin: 'java-library'
        apply plugin: 'io.github.ben-manes.versions'

        repositories {
          maven {
            url '${mavenRepoUrl}'
          }
        }

        dependencies {
          api 'com.google.guava:guava:15.0'
        }
      """.stripIndent(),
    )

    when:
    def result = run('dependencyUpdates', ':child:dependencyUpdates')

    then:
    result.task(':dependencyUpdates').outcome == SUCCESS
    result.task(':child:dependencyUpdates').outcome == SUCCESS
    result.output.contains('com.google.inject:guice [2.0 -> 3.1]')
    result.output.contains('com.google.guava:guava [15.0 -> 16.0]')
  }

  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/1048')
  def 'Aggregates an included build named by its coordinates that publishes by fallback'() {
    given:
    testProjectDir.newFile('settings.gradle') << "includeBuild 'child'"
    testProjectDir.newFile('build.gradle') <<
      """
        plugins {
          id 'io.github.ben-manes.versions'
        }

        dependencies {
          dependencyUpdatesAggregation 'com.example:child:1.0'
        }
      """.stripIndent()
    // Substituted onto the included build's project, so the aggregation has a module dependency
    // rather than a project one, and its artifact is added late enough to leave the project without
    // a variant of its own to be selected by.
    includedBuild(
      'child',
      """
        buildscript {
          dependencies {
            classpath files($classpathString)
          }
        }

        apply plugin: 'io.github.ben-manes.versions'

        group = 'com.example'
        version = '1.0'

        configurations.maybeCreate('default')
        afterEvaluate {
          artifacts.add('default', file('child.jar'))
        }

        repositories {
          maven {
            url '${mavenRepoUrl}'
          }
        }

        configurations.create('tool') {
          canBeResolved = true
          canBeConsumed = false
        }

        dependencies {
          tool 'com.example:jvm-library:1.0'
        }
      """.stripIndent(),
    )
    testProjectDir.newFile('child/child.jar')

    when:
    def result = run('dependencyUpdates')

    then:
    result.task(':dependencyUpdates').outcome == SUCCESS
    result.output.contains('com.example:jvm-library [1.0 -> 2.0]')
  }

  def 'Aggregates every project of an included build named by its coordinates'() {
    given:
    aggregatedIncludedBuild("dependencyUpdatesAggregation 'com.example:child:1.0'")

    when:
    def result = run('dependencyUpdates')

    then:
    result.task(':dependencyUpdates').outcome == SUCCESS
    result.output.contains('com.google.guava:guava [15.0 -> 16.0-rc1]')
    result.output.contains('com.example:jvm-library [1.0 -> 2.0]')
  }

  def 'Reports a project of an included build once when named with the build it belongs to'() {
    given:
    aggregatedIncludedBuild(
      """
        dependencyUpdatesAggregation 'com.example:child:1.0'
        dependencyUpdatesAggregation 'com.example:sub:1.0'
      """.stripIndent(),
    )

    when:
    def result = run('dependencyUpdates')

    then:
    result.task(':dependencyUpdates').outcome == SUCCESS
    result.output.count('com.example:jvm-library [1.0 -> 2.0]') == 1
  }

  // The results are published as the graph edges rather than as the files the aggregate collected,
  // which Gradle 9 refuses to resolve for a consumer that holds no lock on the included build.
  // Gradle 9 requires JVM 17.
  @Requires({ jvm.java17Compatible })
  @Unroll
  def 'Aggregates every project of an included build on Gradle #gradleVersion'() {
    given:
    aggregatedIncludedBuild("dependencyUpdatesAggregation 'com.example:child:1.0'")

    when:
    def result = GradleRunner.create()
      .withGradleVersion(gradleVersion)
      .withProjectDir(testProjectDir.root)
      .withArguments('dependencyUpdates')
      .withPluginClasspath()
      .build()

    then:
    result.task(':dependencyUpdates').outcome == SUCCESS
    result.output.contains('com.google.guava:guava [15.0 -> 16.0-rc1]')
    result.output.contains('com.example:jvm-library [1.0 -> 2.0]')

    where:
    gradleVersion << ['9.0.0', '9.6.1']
  }

  def 'Aggregates the projects of an included build that share a group and name'() {
    given:
    testProjectDir.newFile('settings.gradle') << "includeBuild 'child'"
    testProjectDir.newFile('build.gradle') <<
      """
        plugins {
          id 'io.github.ben-manes.versions'
        }

        dependencies {
          dependencyUpdatesAggregation 'com.example:child:1.0'
        }
      """.stripIndent()
    testProjectDir.newFolder('child')
    testProjectDir.newFile('child/settings.gradle') <<
      """
        rootProject.name = 'child'
        include 'a:common', 'b:common'
      """.stripIndent()
    testProjectDir.newFile('child/build.gradle') <<
      """
        buildscript {
          dependencies {
            classpath files($classpathString)
          }
        }

        apply plugin: 'io.github.ben-manes.versions'

        allprojects {
          group = 'com.example'
          version = '1.0'

          repositories {
            maven {
              url '${mavenRepoUrl}'
            }
          }

          configurations.create('tool') {
            canBeResolved = true
            canBeConsumed = false
          }
        }
      """.stripIndent()
    testProjectDir.newFolder('child', 'a', 'common')
    testProjectDir.newFile('child/a/common/build.gradle') <<
      """
        dependencies {
          tool 'com.google.guava:guava:15.0'
        }
      """.stripIndent()
    testProjectDir.newFolder('child', 'b', 'common')
    testProjectDir.newFile('child/b/common/build.gradle') <<
      """
        dependencies {
          tool 'com.example:jvm-library:1.0'
        }
      """.stripIndent()

    when:
    def result = run('dependencyUpdates')

    then:
    result.task(':dependencyUpdates').outcome == SUCCESS
    result.output.contains('com.google.guava:guava [15.0 -> 16.0-rc1]')
    result.output.contains('com.example:jvm-library [1.0 -> 2.0]')
  }

  /** Writes a build that aggregates an included build of two projects, each with an update. */
  private void aggregatedIncludedBuild(String aggregated) {
    testProjectDir.newFile('settings.gradle') << "includeBuild 'child'"
    testProjectDir.newFile('build.gradle') <<
      """
        plugins {
          id 'io.github.ben-manes.versions'
        }

        dependencies {
          ${aggregated}
        }
      """.stripIndent()
    testProjectDir.newFolder('child')
    testProjectDir.newFile('child/settings.gradle') <<
      """
        rootProject.name = 'child'
        include 'sub'
      """.stripIndent()
    testProjectDir.newFile('child/build.gradle') <<
      """
        buildscript {
          dependencies {
            classpath files($classpathString)
          }
        }

        apply plugin: 'io.github.ben-manes.versions'

        allprojects {
          group = 'com.example'
          version = '1.0'

          repositories {
            maven {
              url '${mavenRepoUrl}'
            }
          }

          configurations.create('tool') {
            canBeResolved = true
            canBeConsumed = false
          }
        }

        dependencies {
          tool 'com.google.guava:guava:15.0'
        }
      """.stripIndent()
    testProjectDir.newFolder('child', 'sub')
    testProjectDir.newFile('child/sub/build.gradle') <<
      """
        dependencies {
          tool 'com.example:jvm-library:1.0'
        }
      """.stripIndent()
  }

  def 'Reports the platform that an included build platform imports'() {
    given:
    testProjectDir.newFile('settings.gradle') << "includeBuild 'platforms'"
    testProjectDir.newFile('build.gradle') <<
      """
        plugins {
          id 'java-library'
          id 'io.github.ben-manes.versions'
        }

        repositories {
          maven {
            url '${mavenRepoUrl}'
          }
        }

        dependencies {
          implementation platform('com.example:platforms:1.0')
          implementation 'com.google.inject:guice'
          implementation 'com.example:bom-consumer:1.0'
        }

        tasks.dependencyUpdates {
          checkConstraints = true
        }
      """.stripIndent()
    includedBuild(
      'platforms',
      """
        plugins {
          id 'java-platform'
        }

        group = 'com.example'
        version = '1.0'

        javaPlatform {
          allowDependencies()
        }

        repositories {
          maven {
            url '${mavenRepoUrl}'
          }
        }

        dependencies {
          api platform('com.example:external-bom:1.0')
        }
      """.stripIndent(),
    )

    when:
    def result = run('dependencyUpdates')

    then:
    result.task(':dependencyUpdates').outcome == SUCCESS
    result.output.contains('com.example:external-bom [1.0 -> 2.0]')
    result.output.contains('imported by the platform :platforms\n')
    // A platform that only a library's metadata drags in is not one the build imported.
    !result.output.contains('com.example:dragged-bom')
  }

  def 'Bounds the reported platform at the version the platform project declares'() {
    given:
    testProjectDir.newFile('settings.gradle') << "includeBuild 'platforms'"
    testProjectDir.newFile('build.gradle') <<
      """
        plugins {
          id 'java-library'
          id 'io.github.ben-manes.versions'
        }

        repositories {
          maven {
            url '${mavenRepoUrl}'
          }
        }

        dependencies {
          implementation platform('com.example:platforms:1.0')
          implementation 'com.google.inject:guice'
        }

        tasks.dependencyUpdates {
          checkConstraints = true
          rejectVersionIf {
            !satisfiesDeclaredBound
          }
        }
      """.stripIndent()
    includedBuild(
      'platforms',
      """
        plugins {
          id 'java-platform'
        }

        group = 'com.example'
        version = '1.0'

        javaPlatform {
          allowDependencies()
        }

        repositories {
          maven {
            url '${mavenRepoUrl}'
          }
        }

        dependencies {
          api(platform('com.example:external-bom')) {
            version {
              strictly '[1.0, 2.0['
            }
          }
        }
      """.stripIndent(),
    )

    when:
    def result = run('dependencyUpdates')

    then:
    result.task(':dependencyUpdates').outcome == SUCCESS
    result.output.contains('com.example:external-bom:1.0')
    // 2.0 exists, but the platform project's declaration excludes it and the bound rides along.
    !result.output.contains('com.example:external-bom [1.0 -> 2.0]')
  }

  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/1070')
  def 'Reports the imported platform when every declared module is versioned'() {
    given:
    testProjectDir.newFile('settings.gradle') << "includeBuild 'platforms'"
    testProjectDir.newFile('build.gradle') <<
      """
        plugins {
          id 'java-library'
          id 'io.github.ben-manes.versions'
        }

        repositories {
          maven {
            url '${mavenRepoUrl}'
          }
        }

        dependencies {
          implementation platform('com.example:platforms:1.0')
          implementation 'com.google.inject:guice:2.0'
        }

        tasks.dependencyUpdates {
          checkConstraints = true
        }
      """.stripIndent()
    includedBuild(
      'platforms',
      """
        plugins {
          id 'java-platform'
        }

        group = 'com.example'
        version = '1.0'

        javaPlatform {
          allowDependencies()
        }

        repositories {
          maven {
            url '${mavenRepoUrl}'
          }
        }

        dependencies {
          api platform('com.example:external-bom:1.0')
        }
      """.stripIndent(),
    )

    when:
    def result = run('dependencyUpdates')

    then:
    result.task(':dependencyUpdates').outcome == SUCCESS
    result.output.contains('com.example:external-bom [1.0 -> 2.0]')
    result.output.contains('imported by the platform :platforms\n')
    result.output.contains('com.google.inject:guice [2.0 -> 3.1]')
  }

  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/1070')
  def 'Prints the importing platform in the file reports'() {
    given:
    testProjectDir.newFile('settings.gradle') << "includeBuild 'platforms'"
    testProjectDir.newFile('build.gradle') <<
      """
        plugins {
          id 'java-library'
          id 'io.github.ben-manes.versions'
        }

        repositories {
          maven {
            url '${mavenRepoUrl}'
          }
        }

        dependencies {
          implementation platform('com.example:platforms:1.0')
          implementation 'com.google.inject:guice'
        }

        tasks.dependencyUpdates {
          checkConstraints = true
        }
      """.stripIndent()
    includedBuild(
      'platforms',
      """
        plugins {
          id 'java-platform'
        }

        group = 'com.example'
        version = '1.0'

        javaPlatform {
          allowDependencies()
        }

        repositories {
          maven {
            url '${mavenRepoUrl}'
          }
        }

        dependencies {
          api platform('com.example:external-bom:1.0')
        }
      """.stripIndent(),
    )

    when:
    def result = run('dependencyUpdates', '-DoutputFormatter=json,xml')
    def jsonReport = new JsonSlurper()
      .parse(new File(testProjectDir.root, 'build/dependencyUpdates/report.json'))
    def xmlReport = new XmlParser()
      .parse(new File(testProjectDir.root, 'build/dependencyUpdates/report.xml'))

    then:
    result.task(':dependencyUpdates').outcome == SUCCESS
    def bom = jsonReport.outdated.dependencies.find { it.name == 'external-bom' }
    bom.platformProjects == [':platforms']
    def guice = jsonReport.current.dependencies.find { it.name == 'guice' }
    !guice.containsKey('platformProjects')
    def bomElement = xmlReport.outdated.dependencies.outdatedDependency.find {
      it.name.text() == 'external-bom'
    }
    bomElement.platformProjects.platformProject*.text() == [':platforms']
  }

  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/1070')
  def 'Aggregates the importers of a platform that two projects import'() {
    given:
    testProjectDir.newFile('settings.gradle') <<
      """
        include 'app', 'lib', 'platform-a', 'platform-b'
      """.stripIndent()
    testProjectDir.newFile('build.gradle') <<
      """
        plugins {
          id 'io.github.ben-manes.versions'
        }

        allprojects {
          repositories {
            maven {
              url '${mavenRepoUrl}'
            }
          }
        }

        tasks.dependencyUpdates {
          checkConstraints = true
        }
      """.stripIndent()
    ['platform-a', 'platform-b'].each { name ->
      testProjectDir.newFolder(name)
      testProjectDir.newFile("$name/build.gradle") <<
        """
          plugins { id 'java-platform' }
          javaPlatform {
            allowDependencies()
          }
          dependencies {
            api platform('com.example:external-bom:1.0')
          }
        """.stripIndent()
    }
    testProjectDir.newFolder('app')
    testProjectDir.newFile('app/build.gradle') <<
      """
        apply plugin: 'java-library'
        dependencies {
          implementation platform(project(':platform-a'))
          implementation 'com.google.inject:guice'
        }
      """.stripIndent()
    testProjectDir.newFolder('lib')
    testProjectDir.newFile('lib/build.gradle') <<
      """
        apply plugin: 'java-library'
        dependencies {
          implementation platform(project(':platform-b'))
          implementation 'com.google.inject:guice'
        }
      """.stripIndent()

    when:
    def result = run('dependencyUpdates', '-DoutputFormatter=json', '--no-parallel')
    def jsonReport = new JsonSlurper()
      .parse(new File(testProjectDir.root, 'build/dependencyUpdates/report.json'))

    then:
    result.task(':dependencyUpdates').outcome == SUCCESS
    def boms = jsonReport.outdated.dependencies.findAll { it.name == 'external-bom' }
    boms.size() == 1
    boms[0].platformProjects == [':platform-a', ':platform-b']
  }

  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/1070')
  def 'Reports the platform imported by the only dependency the build declares'() {
    given:
    testProjectDir.newFile('settings.gradle') <<
      """
        include 'app', 'platform-a'
      """.stripIndent()
    testProjectDir.newFile('build.gradle') <<
      """
        plugins {
          id 'io.github.ben-manes.versions'
        }

        allprojects {
          repositories {
            maven {
              url '${mavenRepoUrl}'
            }
          }
        }

        tasks.dependencyUpdates {
          checkConstraints = true
        }
      """.stripIndent()
    testProjectDir.newFolder('platform-a')
    testProjectDir.newFile('platform-a/build.gradle') <<
      """
        plugins { id 'java-platform' }
        javaPlatform {
          allowDependencies()
        }
        dependencies {
          api platform('com.example:external-bom:1.0')
        }
      """.stripIndent()
    testProjectDir.newFolder('app')
    testProjectDir.newFile('app/build.gradle') <<
      """
        apply plugin: 'java-library'
        dependencies {
          implementation platform(project(':platform-a'))
        }
      """.stripIndent()

    when:
    def result = run('dependencyUpdates', '-DoutputFormatter=json', '--no-parallel')
    def jsonReport = new JsonSlurper()
      .parse(new File(testProjectDir.root, 'build/dependencyUpdates/report.json'))

    then:
    result.task(':dependencyUpdates').outcome == SUCCESS
    def boms = jsonReport.outdated.dependencies.findAll { it.name == 'external-bom' }
    boms.size() == 1
    boms[0].platformProjects == [':platform-a']
  }

  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/1070')
  def 'Reports the platform an enforcedPlatform declaration imports'() {
    given:
    testProjectDir.newFile('settings.gradle') <<
      """
        include 'app', 'platform-a'
      """.stripIndent()
    testProjectDir.newFile('build.gradle') <<
      """
        plugins {
          id 'io.github.ben-manes.versions'
        }

        allprojects {
          repositories {
            maven {
              url '${mavenRepoUrl}'
            }
          }
        }

        tasks.dependencyUpdates {
          checkConstraints = true
        }
      """.stripIndent()
    testProjectDir.newFolder('platform-a')
    testProjectDir.newFile('platform-a/build.gradle') <<
      """
        plugins { id 'java-platform' }
        javaPlatform {
          allowDependencies()
        }
        dependencies {
          api platform('com.example:external-bom:1.0')
        }
      """.stripIndent()
    testProjectDir.newFolder('app')
    testProjectDir.newFile('app/build.gradle') <<
      """
        apply plugin: 'java-library'
        dependencies {
          implementation enforcedPlatform(project(':platform-a'))
        }
      """.stripIndent()

    when:
    def result = run('dependencyUpdates', '-DoutputFormatter=json', '--no-parallel')
    def jsonReport = new JsonSlurper()
      .parse(new File(testProjectDir.root, 'build/dependencyUpdates/report.json'))

    then:
    result.task(':dependencyUpdates').outcome == SUCCESS
    def boms = jsonReport.outdated.dependencies.findAll { it.name == 'external-bom' }
    boms.size() == 1
    boms[0].platformProjects == [':platform-a']
  }

  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/1070')
  def 'Skips the platform scan when the imported platform cannot be resolved'() {
    given:
    testProjectDir.newFile('settings.gradle') << "includeBuild 'platforms'"
    testProjectDir.newFile('build.gradle') <<
      """
        plugins {
          id 'java-library'
          id 'io.github.ben-manes.versions'
        }

        repositories {
          maven {
            url '${mavenRepoUrl}'
          }
        }

        dependencies {
          implementation platform('com.example:platforms:1.0')
          implementation 'com.google.inject:guice'
        }

        tasks.dependencyUpdates {
          checkConstraints = true
        }
      """.stripIndent()
    includedBuild(
      'platforms',
      """
        plugins {
          id 'java-platform'
        }

        group = 'com.example'
        version = '1.0'

        javaPlatform {
          allowDependencies()
        }

        repositories {
          maven {
            url '${mavenRepoUrl}'
          }
        }

        dependencies {
          api platform('com.example:missing-bom:9.9')
        }
      """.stripIndent(),
    )

    when:
    def result = run('dependencyUpdates')

    then:
    result.task(':dependencyUpdates').outcome == SUCCESS
    !result.output.contains('missing-bom')
    // Disabling the platform scan entirely would also satisfy the absence above, so pin that the
    // configuration's other dependency still made it into the report.
    result.output.contains('com.google.inject:guice')
  }

  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/1070')
  def 'Omits the platform mark when another project declares the imported platform'() {
    given:
    testProjectDir.newFile('settings.gradle') <<
      """
        include 'app', 'lib', 'platform-b'
      """.stripIndent()
    testProjectDir.newFile('build.gradle') <<
      """
        plugins {
          id 'io.github.ben-manes.versions'
        }

        allprojects {
          repositories {
            maven {
              url '${mavenRepoUrl}'
            }
          }
        }

        tasks.dependencyUpdates {
          checkConstraints = true
        }
      """.stripIndent()
    testProjectDir.newFolder('platform-b')
    testProjectDir.newFile('platform-b/build.gradle') <<
      """
        plugins { id 'java-platform' }
        javaPlatform {
          allowDependencies()
        }
        dependencies {
          api platform('com.example:external-bom:1.0')
        }
      """.stripIndent()
    testProjectDir.newFolder('app')
    testProjectDir.newFile('app/build.gradle') <<
      """
        apply plugin: 'java-library'
        dependencies {
          implementation platform('com.example:external-bom:1.0')
        }
      """.stripIndent()
    testProjectDir.newFolder('lib')
    testProjectDir.newFile('lib/build.gradle') <<
      """
        apply plugin: 'java-library'
        dependencies {
          implementation platform(project(':platform-b'))
        }
      """.stripIndent()

    when:
    def result = run('dependencyUpdates', '-DoutputFormatter=json', '--info', '--no-parallel')
    def jsonReport = new JsonSlurper()
      .parse(new File(testProjectDir.root, 'build/dependencyUpdates/report.json'))

    then:
    result.task(':dependencyUpdates').outcome == SUCCESS
    def boms = jsonReport.outdated.dependencies.findAll { it.name == 'external-bom' }
    boms.size() == 1
    !boms[0].containsKey('platformProjects')
    !result.output.contains('imported by the platform')
    result.output.contains(
      "A project outside com.example:external-bom's platform importers declares it, " +
        'so the platform mark is withheld')
  }

  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/1070')
  def 'Prints the platform importer by its build-tree path when the report runs in an included build'() {
    given: 'the composite runs the aggregating task from inside the included build, not the root'
    testProjectDir.newFile('settings.gradle') << "includeBuild 'child'"
    testProjectDir.newFile('build.gradle') << ''
    testProjectDir.newFolder('child')
    testProjectDir.newFile('child/settings.gradle') <<
      """
        rootProject.name = 'child'
        include 'app', 'platform-a'
      """.stripIndent()
    testProjectDir.newFile('child/build.gradle') <<
      """
        buildscript {
          dependencies {
            classpath files($classpathString)
          }
        }

        apply plugin: 'io.github.ben-manes.versions'

        tasks.dependencyUpdates {
          checkConstraints = true
        }

        allprojects {
          repositories {
            maven {
              url '${mavenRepoUrl}'
            }
          }
        }
      """.stripIndent()
    testProjectDir.newFolder('child/platform-a')
    testProjectDir.newFile('child/platform-a/build.gradle') <<
      """
        plugins { id 'java-platform' }
        javaPlatform {
          allowDependencies()
        }
        dependencies {
          api platform('com.example:external-bom:1.0')
        }
      """.stripIndent()
    testProjectDir.newFolder('child/app')
    testProjectDir.newFile('child/app/build.gradle') <<
      """
        apply plugin: 'java-library'
        dependencies {
          implementation platform(project(':platform-a'))
        }
      """.stripIndent()

    when:
    def result = run(':child:dependencyUpdates')

    then: 'the importer is named by its path in the build tree, not platform-a\'s path within child'
    result.task(':child:dependencyUpdates').outcome == SUCCESS
    result.output.contains('com.example:external-bom [1.0 -> 2.0]')
    result.output.contains('imported by the platform :child:platform-a\n')
  }

  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/1070')
  def 'Merges an imported platform with the version a library elsewhere drags in'() {
    given: 'a library drags a newer version of the same bom the platform states'
    testProjectDir.newFile('settings.gradle') << "include 'platform-a'"
    testProjectDir.newFile('build.gradle') <<
      """
        plugins {
          id 'java-library'
          id 'io.github.ben-manes.versions'
        }

        repositories {
          maven {
            url '${mavenRepoUrl}'
          }
        }

        dependencies {
          implementation platform(project(':platform-a'))
          implementation 'com.example:external-bom-consumer:1.0'
          implementation 'com.google.inject:guice'
        }

        tasks.dependencyUpdates {
          checkConstraints = true
        }
      """.stripIndent()
    testProjectDir.newFolder('platform-a')
    testProjectDir.newFile('platform-a/build.gradle') <<
      """
        plugins { id 'java-platform' }
        javaPlatform {
          allowDependencies()
        }
        repositories {
          maven {
            url '${mavenRepoUrl}'
          }
        }
        dependencies {
          api platform('com.example:external-bom:1.0')
        }
      """.stripIndent()

    when:
    def result = run('dependencyUpdates')

    then: 'one merged row names the platform-stated version and the update the drag cannot hide'
    result.task(':dependencyUpdates').outcome == SUCCESS
    result.output.contains('com.example:external-bom [1.0 -> 2.0]')
    result.output.contains('imported by the platform :platform-a\n')
    !result.output.contains('com.example:external-bom:2.0')
  }

  def 'Reuses the configuration cache across runs of a composite build'() {
    given:
    testProjectDir.newFile('settings.gradle') << "includeBuild 'child'"
    testProjectDir.newFile('build.gradle') <<
      """
        plugins {
          id 'java-library'
          id 'io.github.ben-manes.versions'
        }

        repositories {
          maven {
            url '${mavenRepoUrl}'
          }
        }

        dependencies {
          api 'com.google.inject:guice:2.0'
        }
      """.stripIndent()
    includedBuild('child')

    when:
    run('dependencyUpdates', '--configuration-cache')
    def result = run('dependencyUpdates', '--configuration-cache')

    then:
    result.task(':dependencyUpdates').outcome == SUCCESS
    result.output.contains('Configuration cache entry reused.')
    // The report must survive the cache hit, not just the task outcome.
    result.output.contains('com.google.inject:guice [2.0 -> 3.1]')
  }

  // Gradle 9 requires JVM 17.
  @Requires({ jvm.java17Compatible })
  @Unroll
  def 'Reports the updates of a composite build on Gradle #gradleVersion'() {
    given:
    testProjectDir.newFile('settings.gradle') << "includeBuild 'child'"
    testProjectDir.newFile('build.gradle') <<
      """
        plugins {
          id 'java-library'
          id 'io.github.ben-manes.versions'
        }

        repositories {
          maven {
            url '${mavenRepoUrl}'
          }
        }

        dependencies {
          api 'com.google.inject:guice:2.0'
        }
      """.stripIndent()
    includedBuild('child')

    when:
    def result = GradleRunner.create()
      .withGradleVersion(gradleVersion)
      .withProjectDir(testProjectDir.root)
      .withArguments('dependencyUpdates')
      .withPluginClasspath()
      .build()

    then:
    result.task(':dependencyUpdates').outcome == SUCCESS
    result.output.contains('com.google.inject:guice [2.0 -> 3.1]')

    where:
    gradleVersion << ['9.0.0', GradleVersions.CURRENT]
  }

  // The measured #801 trigger: a withModule id missing its ':name' half.
  private static String throwingStrategy() {
    return '''
      dependencyUpdates.resolutionStrategy {
        componentSelection { rules ->
          rules.withModule('com.google.guava') { }
        }
      }
      '''.stripIndent()
  }

  // Gradle 9 requires JVM 17.
  @Requires({ jvm.java17Compatible })
  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/801')
  def 'Surfaces the skipped configurations of both builds on Gradle 9'() {
    given:
    testProjectDir.newFile('settings.gradle') << "includeBuild 'child'"
    testProjectDir.newFile('build.gradle') <<
      """
        plugins {
          id 'java-library'
          id 'io.github.ben-manes.versions'
        }

        repositories {
          maven {
            url '${mavenRepoUrl}'
          }
        }

        dependencies {
          api 'com.google.inject:guice:2.0'
        }

        ${throwingStrategy()}
      """.stripIndent()
    includedBuild(
      'child',
      """
        buildscript {
          dependencies {
            classpath files($classpathString)
          }
        }

        apply plugin: 'java-library'
        apply plugin: 'io.github.ben-manes.versions'

        repositories {
          maven {
            url '${mavenRepoUrl}'
          }
        }

        dependencies {
          api 'com.google.guava:guava:15.0'
        }

        ${throwingStrategy()}
      """.stripIndent(),
    )

    when:
    def result = GradleRunner.create()
      .withGradleVersion(GradleVersions.CURRENT)
      .withProjectDir(testProjectDir.root)
      .withArguments('dependencyUpdates', ':child:dependencyUpdates',
        '-DoutputFormatter=plain,json')
      .withPluginClasspath()
      .build()
    def including = report('')
    def included = report('child/')

    then:
    result.task(':dependencyUpdates').outcome == SUCCESS
    result.task(':child:dependencyUpdates').outcome == SUCCESS
    result.output.contains('Failed to inspect the dependencies of the following configurations')

    // Each build reports on its own projects, printed as the path they have in the build tree so
    // that the included build's root is told apart from the including build's.
    [including, included].every { it.skipped.count > 0 }
    [including, included].every { it.skipped.configurations*.name.contains('compileClasspath') }
    [including, included].every { it.skipped.configurations*.name.unique().size() == it.skipped.count }
    including.skipped.count + included.skipped.count == 13
    including.skipped.configurations.every { it.project == ':' }
    included.skipped.configurations.every { it.project == ':child' }

    // The warning above the section shows the same project the section does, rather than printing
    // both builds' roots under the ':' that each build uses for its own.
    result.output.contains('in :child:')
    result.output.count('in root project:') == 1
  }

  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/1075')
  def 'Prints an included build that aggregates its own projects by its build tree path'() {
    given:
    testProjectDir.newFile('settings.gradle') << "includeBuild 'child'"
    testProjectDir.newFile('build.gradle') << ''
    testProjectDir.newFolder('child')
    testProjectDir.newFile('child/settings.gradle') <<
      """
        rootProject.name = 'child'
        include 'alpha', 'beta'
      """.stripIndent()
    testProjectDir.newFile('child/build.gradle') <<
      """
        buildscript {
          dependencies {
            classpath files($classpathString)
          }
        }

        apply plugin: 'io.github.ben-manes.versions'

        allprojects {
          apply plugin: 'java'

          repositories {
            maven {
              url '${mavenRepoUrl}'
            }
          }
        }
      """.stripIndent()
    testProjectDir.newFolder('child', 'alpha')
    testProjectDir.newFile('child/alpha/build.gradle') <<
      """
        dependencies {
          implementation 'com.google.inject:guice:2.0'
        }
      """.stripIndent()
    testProjectDir.newFolder('child', 'beta')
    testProjectDir.newFile('child/beta/build.gradle') <<
      """
        dependencies {
          implementation 'com.google.guava:guava:15.0'
        }
      """.stripIndent()

    when:
    def result = run(':child:dependencyUpdates')

    then:
    result.task(':child:dependencyUpdates').outcome == SUCCESS
    result.output.contains(':child Project Dependency Updates')
    result.output.contains('com.google.inject:guice [2.0 -> 3.1]')
    result.output.contains('com.google.guava:guava [15.0 -> 16.0]')

    // The projects the completeness check expects are named the way the partial results stamp
    // them, so an included build that aggregates does not report its own projects as absent.
    !result.output.contains('The dependency updates report is missing')
  }

  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/1075')
  def 'Prints the projects that declare a divergent version by their build tree paths'() {
    given:
    testProjectDir.newFile('settings.gradle') << "includeBuild 'child'"
    testProjectDir.newFile('build.gradle') <<
      """
        plugins {
          id 'io.github.ben-manes.versions'
        }

        repositories {
          maven {
            url '${mavenRepoUrl}'
          }
        }

        configurations.create('tool') {
          canBeResolved = true
          canBeConsumed = false
        }

        dependencies {
          dependencyUpdatesAggregation 'com.example:child:1.0'
          tool 'com.example:jvm-library:2.0'
        }
      """.stripIndent()
    includedBuild(
      'child',
      """
        buildscript {
          dependencies {
            classpath files($classpathString)
          }
        }

        apply plugin: 'io.github.ben-manes.versions'

        group = 'com.example'
        version = '1.0'

        configurations.maybeCreate('default')
        afterEvaluate {
          artifacts.add('default', file('child.jar'))
        }

        repositories {
          maven {
            url '${mavenRepoUrl}'
          }
        }

        configurations.create('tool') {
          canBeResolved = true
          canBeConsumed = false
        }

        dependencies {
          tool 'com.example:jvm-library:1.0'
        }
      """.stripIndent(),
    )
    testProjectDir.newFile('child/child.jar')

    when:
    def result = run('dependencyUpdates', '-DoutputFormatter=plain,json')
    def json = report('')

    then:
    result.task(':dependencyUpdates').outcome == SUCCESS

    // The path of each build's own root is ':', which read as one project and left out the
    // divergence altogether rather than printing the two that disagree.
    json.outdated.dependencies.find { it.name == 'jvm-library' }.projects == [':child']
    json.current.dependencies.find { it.name == 'jvm-library' }.projects == [':']
    result.output.contains("declared in the 'tool' configuration in root project")
    result.output.contains("declared in the 'tool' configuration in :child")
  }

  private def report(String path) {
    return new JsonSlurper()
      .parse(new File(testProjectDir.root, "${path}build/dependencyUpdates/report.json"))
  }

  private void judgedComposite(
    String outerBody =
      """
        dependencies {
          dependencyUpdatesAggregation 'com.example:child:1.0'
        }

        tasks.named('dependencyUpdates').configure {
          rejectVersionIf {
            candidate.version == '3.1'
          }
        }
      """) {
    testProjectDir.newFile('settings.gradle') << "includeBuild 'child'"
    testProjectDir.newFile('build.gradle') <<
      """
        plugins {
          id 'io.github.ben-manes.versions'
        }

        ${outerBody}
      """.stripIndent()
    testProjectDir.newFolder('child')
    testProjectDir.newFile('child/settings.gradle') << "rootProject.name = 'child'"
    testProjectDir.newFile('child/build.gradle') <<
      """
        buildscript {
          dependencies {
            classpath files($classpathString)
          }
        }

        apply plugin: 'io.github.ben-manes.versions'

        group = 'com.example'
        version = '1.0'

        repositories {
          maven {
            url '${mavenRepoUrl}'
          }
        }

        configurations.create('tool') {
          canBeResolved = true
          canBeConsumed = false
        }

        dependencies {
          tool 'com.google.inject:guice:2.0'
        }
      """.stripIndent()
  }

  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/1058')
  def "An including build's rejectVersionIf governs an included build's dependency"() {
    given: "the child's own resolution accepts 3.1, but the outer's rule rejects it"
    judgedComposite()

    when:
    def result = run('dependencyUpdates')

    then: "the outer's rule reaches the merged-in row, stopping it at 3.0 rather than the child's own 3.1"
    result.task(':dependencyUpdates').outcome == SUCCESS
    result.output.contains('com.google.inject:guice [2.0 -> 3.0]')
    !result.output.contains('com.google.inject:guice [2.0 -> 3.1]')
  }

  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/1058')
  def "An including build's rejectVersionIf governs a merged-in row under the configuration cache"() {
    given: "the same composite, run with the cache stored and then reused"
    judgedComposite()

    when:
    def store = run('dependencyUpdates', '--configuration-cache', '--no-parallel')
    def hit = run('dependencyUpdates', '--configuration-cache', '--no-parallel')

    then: "the rules are judged from the serialized task, so both legs cap the row at 3.0"
    store.task(':dependencyUpdates').outcome == SUCCESS
    store.output.contains('com.google.inject:guice [2.0 -> 3.0]')
    !store.output.contains('com.google.inject:guice [2.0 -> 3.1]')
    hit.output.contains('Reusing configuration cache')
    hit.output.contains('com.google.inject:guice [2.0 -> 3.0]')
    !hit.output.contains('com.google.inject:guice [2.0 -> 3.1]')
  }

  @Unroll
  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/1058')
  def "A composite judges the same rows with the cache as without it, #declared"() {
    given: 'the rule and the aggregation coordinate declared in either order, one of them late'
    judgedComposite(outerBody)

    when:
    def plain = run('dependencyUpdates')
    def store = run('dependencyUpdates', '--configuration-cache', '--no-parallel')
    def hit = run('dependencyUpdates', '--configuration-cache', '--no-parallel')

    then: 'a hook that runs after the project is evaluated is still seen by the judge'
    [plain, store, hit].every { it.output.contains('com.google.inject:guice [2.0 -> 3.0]') }
    [plain, store, hit].every { !it.output.contains('com.google.inject:guice [2.0 -> 3.1]') }

    where:
    declared << ['the rule from a later hook', 'the coordinate from a later hook']
    outerBody << [
      '''
        dependencies {
          dependencyUpdatesAggregation 'com.example:child:1.0'
        }

        afterEvaluate {
          tasks.named('dependencyUpdates').configure {
            rejectVersionIf {
              candidate.version == '3.1'
            }
          }
        }
      ''',
      '''
        tasks.named('dependencyUpdates').configure {
          rejectVersionIf {
            candidate.version == '3.1'
          }
        }

        afterEvaluate {
          dependencies {
            dependencyUpdatesAggregation 'com.example:child:1.0'
          }
        }
      ''',
    ]
  }

  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/1058')
  def "A resolutionStrategy cleared after it was captured does not govern the report"() {
    given: 'a rule registered in the build script and cleared from a later hook'
    judgedComposite(
      '''
        dependencies {
          dependencyUpdatesAggregation 'com.example:child:1.0'
        }

        tasks.named('dependencyUpdates').configure {
          rejectVersionIf {
            candidate.version == '3.1'
          }
        }

        afterEvaluate {
          tasks.named('dependencyUpdates').configure {
            resolutionStrategy()
          }
        }
      ''')

    when:
    def result = run('dependencyUpdates')

    then: 'clearing the strategy clears what the judge would have applied along with it'
    result.output.contains('com.google.inject:guice [2.0 -> 3.1]')
  }

  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/1058')
  def "A report that cannot apply its own rules says so rather than judging nothing quietly"() {
    given: 'a strategy reading a script object as it registers, which a serialized closure may not do'
    testProjectDir.newFile('settings.gradle') << "includeBuild 'child'"
    testProjectDir.newFile('build.gradle') <<
      """
        plugins {
          id 'io.github.ben-manes.versions'
        }

        dependencies {
          dependencyUpdatesAggregation 'com.example:child:1.0'
        }

        tasks.named('dependencyUpdates').configure {
          resolutionStrategy {
            def owner = project.path
            it.componentSelection { rules ->
              rules.all { selection ->
                if (selection.candidate.version == '3.1') {
                  selection.reject('rejected by the test rule')
                }
              }
            }
          }
        }
      """.stripIndent()
    testProjectDir.newFolder('child')
    testProjectDir.newFile('child/settings.gradle') << "rootProject.name = 'child'"
    testProjectDir.newFile('child/build.gradle') <<
      """
        buildscript {
          dependencies {
            classpath files($classpathString)
          }
        }

        apply plugin: 'io.github.ben-manes.versions'

        group = 'com.example'
        version = '1.0'

        repositories {
          maven {
            url '${mavenRepoUrl}'
          }
        }

        configurations.create('tool') {
          canBeResolved = true
          canBeConsumed = false
        }

        dependencies {
          tool 'com.google.inject:guice:2.0'
        }
      """.stripIndent()

    when:
    def result = run('dependencyUpdates', '--configuration-cache', '--no-parallel')

    then: 'the rows stay as their own builds resolved them, and the report names the reason'
    result.task(':dependencyUpdates').outcome == SUCCESS
    result.output.contains('The report kept each dependency as the build that resolved it reported it')
    result.output.contains('com.google.inject:guice [2.0 -> 3.1]')
  }

  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/1058')
  def "An including build's report offers no candidate its own revision rejects"() {
    given: 'a listing whose integration version sits below the release the child resolved'
    testProjectDir.newFile('settings.gradle') << "includeBuild 'child'"
    testProjectDir.newFile('build.gradle') <<
      """
        plugins {
          id 'io.github.ben-manes.versions'
        }

        dependencies {
          dependencyUpdatesAggregation 'com.example:child:1.0'
        }

        tasks.named('dependencyUpdates').configure {
          rejectVersionIf {
            candidate.version == '3.0'
          }
        }
      """.stripIndent()
    testProjectDir.newFolder('child')
    testProjectDir.newFile('child/settings.gradle') << "rootProject.name = 'child'"
    testProjectDir.newFile('child/build.gradle') <<
      """
        buildscript {
          dependencies {
            classpath files($classpathString)
          }
        }

        apply plugin: 'io.github.ben-manes.versions'

        group = 'com.example'
        version = '1.0'

        repositories {
          maven {
            url '${mavenRepoUrl}'
          }
        }

        configurations.create('tool') {
          canBeResolved = true
          canBeConsumed = false
        }

        dependencies {
          tool 'com.example:snapshot-interleaved:1.0'
        }
      """.stripIndent()

    when:
    def result = run('dependencyUpdates', '-DoutputFormatter=plain,json')
    def json = report('')
    def offered = json.outdated.dependencies.find { it.name == 'snapshot-interleaved' }
    def unchanged = json.current.dependencies.find { it.name == 'snapshot-interleaved' }

    then: 'the row is reported, and never at the integration version a milestone report rejects'
    result.task(':dependencyUpdates').outcome == SUCCESS
    (offered != null) || (unchanged != null)
    offered?.available?.milestone != '2.5-SNAPSHOT'
  }

  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/1058')
  def "An included build's own report is not governed by the including build"() {
    given: "the outer's rejectVersionIf targets guice, but only when it aggregates the child"
    testProjectDir.newFile('settings.gradle') << "includeBuild 'child'"
    testProjectDir.newFile('build.gradle') << ''
    testProjectDir.newFolder('child')
    testProjectDir.newFile('child/settings.gradle') << "rootProject.name = 'child'"
    testProjectDir.newFile('child/build.gradle') <<
      """
        buildscript {
          dependencies {
            classpath files($classpathString)
          }
        }

        apply plugin: 'java-library'
        apply plugin: 'io.github.ben-manes.versions'

        group = 'com.example'
        version = '1.0'

        repositories {
          maven {
            url '${mavenRepoUrl}'
          }
        }

        dependencies {
          api 'com.google.inject:guice:2.0'
        }
      """.stripIndent()

    when:
    def result = run(':child:dependencyUpdates')

    then: "the child's own report is unaffected by a rule the outer never gets to register"
    result.task(':child:dependencyUpdates').outcome == SUCCESS
    result.output.contains('com.google.inject:guice [2.0 -> 3.1]')
  }

  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/1058')
  def "The #475 snapshot exemption survives on a merged row"() {
    given: 'a snapshot-only module in the child, exempted only when the judge rebuilds its own current version'
    testProjectDir.newFile('settings.gradle') << "includeBuild 'child'"
    testProjectDir.newFile('build.gradle') <<
      """
        plugins {
          id 'io.github.ben-manes.versions'
        }

        dependencies {
          dependencyUpdatesAggregation 'com.example:child:1.0'
        }

        tasks.named('dependencyUpdates').configure {
          rejectVersionIf {
            candidate.version == '1.5'
          }
          rejectVersionIf {
            candidate.version.endsWith('-SNAPSHOT') && candidate.version != currentVersion
          }
        }
      """.stripIndent()
    testProjectDir.newFolder('child')
    testProjectDir.newFile('child/settings.gradle') << "rootProject.name = 'child'"
    testProjectDir.newFile('child/build.gradle') <<
      """
        buildscript {
          dependencies {
            classpath files($classpathString)
          }
        }

        apply plugin: 'io.github.ben-manes.versions'

        group = 'com.example'
        version = '1.0'

        repositories {
          maven {
            url '${mavenRepoUrl}'
          }
        }

        configurations.create('tool') {
          canBeResolved = true
          canBeConsumed = false
        }

        dependencies {
          tool 'com.example:snapshot-mixed:1.0-SNAPSHOT'
        }
      """.stripIndent()

    when:
    def result = run('dependencyUpdates', '-DoutputFormatter=plain,json')
    def json = report('')

    then: "the outer's rule rejects the release ceiling, and its rebuilt current version exempts the snapshot below it"
    result.task(':dependencyUpdates').outcome == SUCCESS
    json.current.dependencies.find { it.name == 'snapshot-mixed' }?.version == '1.0-SNAPSHOT'
    !json.outdated.dependencies*.name.contains('snapshot-mixed')
    !json.unresolved.dependencies*.name.contains('snapshot-mixed')
  }

  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/1058')
  def "A merged row's declared bound holds through the rebuilt constraint"() {
    given: 'a module the child bounds only through a platform, merged into a build that judges the bound'
    testProjectDir.newFile('settings.gradle') << "includeBuild 'child'"
    testProjectDir.newFile('build.gradle') <<
      """
        plugins {
          id 'io.github.ben-manes.versions'
        }

        dependencies {
          dependencyUpdatesAggregation 'com.example:child:1.0'
        }

        tasks.named('dependencyUpdates').configure {
          rejectVersionIf {
            !satisfiesDeclaredBound
          }
        }
      """.stripIndent()
    testProjectDir.newFolder('child')
    testProjectDir.newFile('child/settings.gradle') << "rootProject.name = 'child'"
    testProjectDir.newFile('child/build.gradle') <<
      """
        buildscript {
          dependencies {
            classpath files($classpathString)
          }
        }

        apply plugin: 'java-library'
        apply plugin: 'io.github.ben-manes.versions'

        group = 'com.example'
        version = '1.0'

        repositories {
          maven {
            url '${mavenRepoUrl}'
          }
        }

        dependencies {
          implementation platform('org.apache.logging.log4j:log4j:2.16.0')
          implementation 'org.apache.logging.log4j:log4j-core'
        }

        tasks.named('dependencyUpdates').configure {
          checkConstraints = true
        }
      """.stripIndent()

    when:
    def result = run('dependencyUpdates', '-DoutputFormatter=plain,json')
    def json = report('')

    then: 'the platform bound the child recorded still holds the merged row at the platform version'
    result.task(':dependencyUpdates').outcome == SUCCESS
    json.current.dependencies.find { it.name == 'log4j-core' }?.version == '2.16.0'
    !json.outdated.dependencies*.name.contains('log4j-core')
  }
}
