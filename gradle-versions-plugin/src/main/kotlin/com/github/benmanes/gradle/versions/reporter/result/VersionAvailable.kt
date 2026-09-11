package com.github.benmanes.gradle.versions.reporter.result

class VersionAvailable
  @JvmOverloads
  constructor(
    val release: String? = null,
    val milestone: String? = null,
    val integration: String? = null,
    /**
     * The newest candidate left out by the pre-release check, null when there is none or when
     * `rejectPreReleases` is set on the report. Newer than the version in the three fields above,
     * which is the newest the resolution accepted.
     */
    val preRelease: String? = null,
  ) {
    /**
     * Returns the version available at [revision], and the release-level one for a revision outside
     * the three levels, which is where the report files the version it found for such a revision.
     * Reading back an empty string there left every reporter printing a row with no version at all.
     */
    operator fun get(revision: String): String? {
      return when (revision) {
        "milestone" -> milestone
        "integration" -> integration
        else -> release
      }
    }
  }
