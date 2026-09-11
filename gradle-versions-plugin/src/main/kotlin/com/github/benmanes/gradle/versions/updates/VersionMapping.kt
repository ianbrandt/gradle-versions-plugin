package com.github.benmanes.gradle.versions.updates

import org.gradle.api.internal.artifacts.ivyservice.ivyresolve.strategy.DefaultVersionComparator
import org.gradle.api.internal.artifacts.ivyservice.ivyresolve.strategy.VersionParser
import org.gradle.api.logging.Logger

/**
 * A mapping of which versions are out of date, up to date, undeclared, or exceed the latest found.
 */
class VersionMapping(private val logger: Logger, statuses: List<PartialStatus>) {
  val downgrade = sortedSetOf<Coordinate>()
  val upToDate = sortedSetOf<Coordinate>()
  val upgrade = sortedSetOf<Coordinate>()
  val undeclared = sortedSetOf<Coordinate>()
  val unresolved = sortedSetOf<Coordinate>()
  val current = sortedSetOf<Coordinate>()
  val latest = sortedSetOf<Coordinate>()
  val latestByCurrent = hashMapOf<Coordinate, Coordinate>()

  /**
   * The newest candidate the pre-release check left out, per declared coordinate, absent where it
   * left none out. Kept beside [latestByCurrent] rather than replacing its entry, so a row with
   * both steps reports the newest the resolution accepted as well as the one it did not.
   */
  val preReleaseByCurrent = hashMapOf<Coordinate, String>()
  private var comparator = makeVersionComparator()

  init {
    for (status in statuses) {
      current.add(status.coordinate)
      if (status.unresolved == null) {
        val latestCoordinate = status.latestCoordinate
        latest.add(latestCoordinate)
        val previous = latestByCurrent[status.coordinate]
        if (previous == null || comparator.compare(previous.version, latestCoordinate.version) < 0) {
          latestByCurrent[status.coordinate] = latestCoordinate
        }
        status.preReleaseVersion?.let { preRelease ->
          val seen = preReleaseByCurrent[status.coordinate]
          if (seen == null || comparator.compare(seen, preRelease) < 0) {
            preReleaseByCurrent[status.coordinate] = preRelease
          }
        }
      } else {
        unresolved.add(status.coordinate)
      }
    }
    organize()
  }

  /** Groups the dependencies into up-to-date, upgrades available, or downgrade buckets.  */
  private fun organize() {
    for (coordinate in current) {
      // A resolution that produced a version for this exact coordinate is the one to report, even
      // when
      // another one failed on it. The failure is still recorded in the unresolved set, so both the
      // update and the resolution that could not find it are reported.
      val resolved = latestByCurrent[coordinate]
      val version = resolved?.version
      logger
        .info("Comparing dependency (current: {}, latest: {})", coordinate, version ?: "unresolved")
      if (resolved == null && unresolved.contains(coordinate)) {
        continue
      } else if (coordinate.version == "none") {
        undeclared.add(coordinate)
        continue
      }
      val result = comparator.compare(coordinate.version, version)
      if (result <= -1) {
        upgrade.add(coordinate)
      } else if (result == 0) {
        // A module whose only newer candidate is a pre-release resolves to the version already
        // declared, so the row is an upgrade by the pre-release step alone and the breadcrumb
        // prints that step in place of the middle one.
        if (preReleaseByCurrent.containsKey(coordinate)) {
          upgrade.add(coordinate)
        } else {
          upToDate.add(coordinate)
        }
      } else {
        downgrade.add(coordinate)
      }
    }
  }

  companion object {
    private fun makeVersionComparator(): Comparator<String> = versionComparator(VersionParser())

    /** Returns the comparator that orders two version strings as Gradle's resolution does. */
    internal fun versionComparator(): Comparator<String> = makeVersionComparator()

    /** Orders version strings the way dependency resolution orders them, through the given parser. */
    internal fun versionComparator(versionParser: VersionParser): Comparator<String> {
      val baseComparator = DefaultVersionComparator().asVersionComparator()
      return Comparator { string1, string2 ->
        baseComparator.compare(versionParser.transform(string1), versionParser.transform(string2))
      }
    }
  }
}
