package com.github.benmanes.gradle.versions

import com.github.benmanes.gradle.versions.updates.Coordinate
import com.github.benmanes.gradle.versions.updates.DependencyStatus
import com.github.benmanes.gradle.versions.updates.Resolver
import org.gradle.api.artifacts.ExternalModuleDependency
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import spock.lang.Issue
import spock.lang.Specification

/**
 * A specification for the candidates a dynamic query reaches, recorded by a policy-free walk that
 * rejects every one rather than the first-accept walk the report itself resolves against.
 * https://github.com/ben-manes/gradle-versions-plugin/issues/948
 */
@Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/948')
final class ResolverCandidatesSpec extends Specification {
  @Rule final TemporaryFolder repoDir = new TemporaryFolder()
  private File repo

  def 'setup'() {
    repo = repoDir.newFolder('repository')
  }

  /**
   * Writes a module with a maven-metadata.xml listing every version in {@code allVersions}, newest
   * first, but only publishes a pom for {@code declaredVersion}. A listed version with no pom
   * proves the walk never fetches metadata: fetching one for a candidate absent from disk would
   * fail or drop it rather than recording it.
   */
  private void publishModule(String group, String artifact, String declaredVersion, List<String> allVersions) {
    def dir = new File(repo, "${group.replace('.', '/')}/${artifact}")
    dir.mkdirs()
    def versionDir = new File(dir, declaredVersion)
    versionDir.mkdirs()
    new File(versionDir, "${artifact}-${declaredVersion}.pom").text = """<?xml version="1.0" encoding="UTF-8"?>
      <project>
        <modelVersion>4.0.0</modelVersion>
        <groupId>${group}</groupId>
        <artifactId>${artifact}</artifactId>
        <version>${declaredVersion}</version>
      </project>
    """.stripIndent()
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
          <lastUpdated>20100320162336</lastUpdated>
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

  private Set<String> candidatesOf(project, String configurationName, String coordinate) {
    project.configurations.create(configurationName)
    project.dependencies.add(configurationName, coordinate)
    def resolver = new Resolver(project, null, false)
    def configuration = project.configurations.getByName(configurationName)
    resolver.resolve(configuration, 'integration', resolver.declaredKeys(configuration))
    return resolver.candidates
  }

  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/948')
  def 'Records every listed candidate in newest-first order, including one with no published artifact'() {
    given:
    publishModule('com.example', 'widget', '1.0', ['3.1', '3.0', '2.2', '2.1', '2.0', '1.0'])

    expect:
    candidatesOf(project(), 'app', 'com.example:widget:1.0') as List ==
      ['com.example:widget:3.1', 'com.example:widget:3.0', 'com.example:widget:2.2',
       'com.example:widget:2.1', 'com.example:widget:2.0', 'com.example:widget:1.0']
  }

  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/948')
  def 'Preserves the order Gradle presents candidates in rather than a lexical sort'() {
    given:
    // Lexically, '2.0.9' sorts after '2.0.10'; Gradle's own comparator orders them the other way.
    publishModule('com.example', 'widget', '2.0.9', ['2.0.10', '2.0.9'])

    expect:
    candidatesOf(project(), 'app', 'com.example:widget:2.0.9') as List ==
      ['com.example:widget:2.0.10', 'com.example:widget:2.0.9']
  }

  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/948')
  def 'Does not duplicate a candidate across the configurations the java plugin resolves'() {
    given:
    publishModule('com.example', 'widget', '1.0', ['2.0', '1.0'])
    def app = project()
    app.pluginManager.apply('java')
    app.dependencies.add('implementation', 'com.example:widget:1.0')

    when:
    def resolver = new Resolver(app, null, false)
    ['compileClasspath', 'runtimeClasspath', 'testCompileClasspath', 'testRuntimeClasspath'].each { name ->
      def configuration = app.configurations.getByName(name)
      resolver.resolve(configuration, 'integration', resolver.declaredKeys(configuration))
    }

    then:
    resolver.candidates.count { it == 'com.example:widget:2.0' } == 1
    resolver.candidates.count { it == 'com.example:widget:1.0' } == 1
  }

  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/948')
  def 'Attributes each candidate to its module when two use one artifact id across groups'() {
    given:
    publishModule('com.a', 'lib', '1.0', ['1.0'])
    publishModule('com.b', 'lib', '9.0', ['9.0'])
    def app = project()
    app.configurations.create('app')
    app.dependencies.add('app', 'com.a:lib:1.0')
    app.dependencies.add('app', 'com.b:lib:9.0')
    def resolver = new Resolver(app, null, false)
    def configuration = app.configurations.getByName('app')

    when:
    resolver.resolve(configuration, 'integration', resolver.declaredKeys(configuration))

    then:
    resolver.candidates == (['com.a:lib:1.0', 'com.b:lib:9.0'] as Set)
  }

  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/948')
  def 'Records a buildscript classpath candidate from the buildscript repositories'() {
    given:
    publishModule('com.example', 'someplugin', '1.0', ['2.0', '1.0'])
    def app = ProjectBuilder.builder().withName('root').build()
    // The repository with the module in it is declared only in the buildscript, so a walk resolving
    // against the project's own repositories records nothing at all.
    app.repositories {
      maven { url repoDir.newFolder('empty').toURI() }
    }
    app.buildscript.repositories {
      maven { url repo.toURI() }
    }
    app.buildscript.dependencies.add('classpath', 'com.example:someplugin:1.0')
    def configuration = app.buildscript.configurations.getByName('classpath')
    def resolver = new Resolver(app, null, false)

    when:
    resolver.resolve(configuration, 'integration', resolver.declaredKeys(configuration))

    then:
    resolver.candidates.contains('com.example:someplugin:2.0')
  }

  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/1058')
  def 'Records a classpath candidate of a container the project does not hold'() {
    given: 'the module is reachable only from the other build script, as a settings plugin is'
    publishModule('com.example', 'someplugin', '1.0', ['2.0', '1.0'])
    def app = ProjectBuilder.builder().withName('root').build()
    app.repositories {
      maven { url repoDir.newFolder('empty').toURI() }
    }
    app.buildscript.repositories {
      maven { url repoDir.newFolder('empty-buildscript').toURI() }
    }
    // A second project stands in for the settings script, whose classpath configuration shares the
    // name 'classpath' with the one this project's own buildscript holds.
    def other = ProjectBuilder.builder().withName('other').build()
    other.buildscript.repositories {
      maven { url repo.toURI() }
    }
    other.buildscript.dependencies.add('classpath', 'com.example:someplugin:1.0')
    def configuration = other.buildscript.configurations.getByName('classpath')
    def resolver = new Resolver(app, null, false, true, true, null, null,
      other.buildscript.configurations, {})

    when:
    resolver.resolve(configuration, 'integration', resolver.declaredKeys(configuration))

    then: 'the walk detaches from the container that holds it rather than the one named alike'
    resolver.candidates.contains('com.example:someplugin:2.0')
  }

  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/948')
  def 'Records every listed candidate despite a componentSelection rule on the resolved configuration'() {
    given:
    publishModule('com.probe', 'rejected', '3.0', ['3.0', '2.0', '1.0'])
    def app = project()
    def configuration = app.configurations.create('app')
    app.dependencies.add('app', 'com.probe:rejected:3.0')
    configuration.resolutionStrategy.componentSelection.all { selection ->
      if (selection.candidate.version == '2.0') {
        selection.reject('rejected by the build script')
      }
    }
    def resolver = new Resolver(app, null, false)

    when:
    resolver.resolve(configuration, 'integration', resolver.declaredKeys(configuration))

    then:
    resolver.candidates == (['com.probe:rejected:3.0', 'com.probe:rejected:2.0', 'com.probe:rejected:1.0'] as Set)
  }

  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/948')
  def 'Records every listed candidate despite a force on the resolved configuration'() {
    given:
    publishModule('com.probe', 'forced', '2.5', ['3.0', '2.5', '2.0', '1.0'])
    def app = project()
    def configuration = app.configurations.create('app')
    app.dependencies.add('app', 'com.probe:forced:1.0')
    configuration.resolutionStrategy.force('com.probe:forced:2.5')
    def resolver = new Resolver(app, null, false)

    when:
    resolver.resolve(configuration, 'integration', resolver.declaredKeys(configuration))

    then:
    resolver.candidates == (['com.probe:forced:3.0', 'com.probe:forced:2.5',
                              'com.probe:forced:2.0', 'com.probe:forced:1.0'] as Set)
  }

  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/948')
  def 'Records every listed candidate despite an eachDependency useVersion on the resolved configuration'() {
    given:
    publishModule('com.probe', 'pinned', '2.0', ['3.0', '2.0', '1.0'])
    def app = project()
    def configuration = app.configurations.create('app')
    app.dependencies.add('app', 'com.probe:pinned:1.0')
    configuration.resolutionStrategy.eachDependency { details ->
      details.useVersion('2.0')
    }
    def resolver = new Resolver(app, null, false)

    when:
    resolver.resolve(configuration, 'integration', resolver.declaredKeys(configuration))

    then:
    resolver.candidates == (['com.probe:pinned:3.0', 'com.probe:pinned:2.0',
                              'com.probe:pinned:1.0'] as Set)
  }

  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/948')
  def 'Reports the forced version while recording every candidate the force did not shape'() {
    given:
    publishModule('com.probe', 'forcedreport', '2.5', ['3.0', '2.5', '2.0', '1.0'])
    def app = project()
    def configuration = app.configurations.create('app')
    app.dependencies.add('app', 'com.probe:forcedreport:1.0')
    configuration.resolutionStrategy.force('com.probe:forcedreport:2.5')
    def resolver = new Resolver(app, null, false)

    when:
    def statuses = resolver.resolve(configuration, 'integration', resolver.declaredKeys(configuration))

    then:
    statuses.find { it.coordinate.artifactId == 'forcedreport' }?.latestVersion == '2.5'
    resolver.candidates == (['com.probe:forcedreport:3.0', 'com.probe:forcedreport:2.5',
                              'com.probe:forcedreport:2.0', 'com.probe:forcedreport:1.0'] as Set)
  }

  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/948')
  def 'Reports the eachDependency-pinned version while recording every candidate the rule did not shape'() {
    given:
    publishModule('com.probe', 'pinnedreport', '2.0', ['3.0', '2.5', '2.0', '1.0'])
    def app = project()
    def configuration = app.configurations.create('app')
    app.dependencies.add('app', 'com.probe:pinnedreport:1.0')
    configuration.resolutionStrategy.eachDependency { details ->
      details.useVersion('2.0')
    }
    def resolver = new Resolver(app, null, false)

    when:
    def statuses = resolver.resolve(configuration, 'integration', resolver.declaredKeys(configuration))

    then:
    statuses.find { it.coordinate.artifactId == 'pinnedreport' }?.latestVersion == '2.0'
    resolver.candidates == (['com.probe:pinnedreport:3.0', 'com.probe:pinnedreport:2.5',
                              'com.probe:pinnedreport:2.0', 'com.probe:pinnedreport:1.0'] as Set)
  }

  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/948')
  def 'Reports the componentSelection-rejected version while recording every candidate the rule did not shape'() {
    given:
    publishModule('com.probe', 'rejectedreport', '2.5', ['3.0', '2.5', '2.0', '1.0'])
    def app = project()
    def configuration = app.configurations.create('app')
    app.dependencies.add('app', 'com.probe:rejectedreport:1.0')
    configuration.resolutionStrategy.componentSelection.all { selection ->
      if (selection.candidate.version == '3.0') {
        selection.reject('rejected by the build script')
      }
    }
    def resolver = new Resolver(app, null, false)

    when:
    def statuses = resolver.resolve(configuration, 'integration', resolver.declaredKeys(configuration))

    then:
    statuses.find { it.coordinate.artifactId == 'rejectedreport' }?.latestVersion == '2.5'
    resolver.candidates == (['com.probe:rejectedreport:3.0', 'com.probe:rejectedreport:2.5',
                              'com.probe:rejectedreport:2.0', 'com.probe:rejectedreport:1.0'] as Set)
  }

  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/948')
  def 'Records nothing and does not throw for a module absent from every repository'() {
    given:
    publishModule('com.example', 'widget', '1.0', ['1.0'])
    def app = project()
    app.configurations.create('app')
    app.dependencies.add('app', 'com.example:widget:1.0')
    app.dependencies.add('app', 'com.example:ghost:1.0')
    def resolver = new Resolver(app, null, false)
    def configuration = app.configurations.getByName('app')

    when:
    def statuses = resolver.resolve(configuration, 'integration', resolver.declaredKeys(configuration))

    then:
    noExceptionThrown()
    statuses.find { it.coordinate.artifactId == 'widget' }?.latestVersion == '1.0'
    statuses.find { it.coordinate.artifactId == 'ghost' }?.unresolved != null
    resolver.candidates.contains('com.example:widget:1.0')
    !resolver.candidates.any { it.startsWith('com.example:ghost:') }
  }

  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/948')
  def 'Serializes the declared version constraint into the partial verbatim'() {
    given:
    def project = ProjectBuilder.builder().withName('root').build()
    def dependency = project.dependencies.create('com.google.guava:guava') as ExternalModuleDependency
    dependency.version { v ->
      v.strictly('15.0')
      v.prefer('16.0')
      v.reject('17.0', '18.0')
    }
    def constraint = dependency.versionConstraint
    def coordinate = new Coordinate('com.google.guava', 'guava', 'none', null, constraint)
    def status = new DependencyStatus(coordinate, '16.0', null, false, [])

    when:
    def partial = status.toPartialStatus()

    then:
    partial.constraint.required == constraint.requiredVersion
    partial.constraint.strict == constraint.strictVersion
    partial.constraint.preferred == constraint.preferredVersion
    partial.constraint.rejected == constraint.rejectedVersions
  }

  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/948')
  def 'Serializes no constraint when no declaration named a version'() {
    given:
    def coordinate = new Coordinate('com.google.guava', 'guava', 'none', null)
    def status = new DependencyStatus(coordinate, '16.0', null, false, [])

    when:
    def partial = status.toPartialStatus()

    then:
    partial.constraint == null
    partial.platformConstraints == []
  }

  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/948')
  def 'Serializes the platform-supplied constraints separately from the declared one'() {
    given:
    def project = ProjectBuilder.builder().withName('root').build()
    def dependency = project.dependencies.create('com.google.guava:guava') as ExternalModuleDependency
    dependency.version { v -> v.require('12.0'); v.reject('13.0') }
    def platformConstraint = dependency.versionConstraint
    def coordinate = new Coordinate('com.google.guava', 'guava', 'none', null, null, [platformConstraint])
    def status = new DependencyStatus(coordinate, '12.0', null, false, [])

    when:
    def partial = status.toPartialStatus()

    then:
    partial.constraint == null
    partial.platformConstraints.size() == 1
    partial.platformConstraints[0].required == platformConstraint.requiredVersion
    partial.platformConstraints[0].rejected == platformConstraint.rejectedVersions
  }
}
