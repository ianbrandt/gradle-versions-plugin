package com.github.benmanes.gradle.versions.reporter.result

class VersionAvailable
  @JvmOverloads
  constructor(
    val release: String? = null,
    val milestone: String? = null,
    val integration: String? = null,
    /**
     * The newest candidate the pre-release check left out, null when there is none or when the
     * report's `rejectPreReleases` leaves the step out. Newer than the version the three fields
     * above carry, which is the newest the resolution accepted.
     */
    val preRelease: String? = null,
  ) {
    operator fun get(revision: String): String? {
      return when (revision) {
        "release" -> release
        "milestone" -> milestone
        "integration" -> integration
        else -> {
          ""
        }
      }
    }
  }
