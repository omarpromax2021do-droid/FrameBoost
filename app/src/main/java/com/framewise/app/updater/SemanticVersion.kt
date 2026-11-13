package com.framewise.app.updater

data class SemanticVersion(
    val major: Int,
    val minor: Int,
    val patch: Int,
    val preRelease: String? = null
) : Comparable<SemanticVersion> {

    override fun compareTo(other: SemanticVersion): Int {
        if (major != other.major) return major.compareTo(other.major)
        if (minor != other.minor) return minor.compareTo(other.minor)
        if (patch != other.patch) return patch.compareTo(other.patch)

        // Stable release has higher precedence than any pre-release with same M.m.p.
        if (preRelease == null && other.preRelease == null) return 0
        if (preRelease == null) return 1
        if (other.preRelease == null) return -1

        val leftParts = preRelease.split('.')
        val rightParts = other.preRelease.split('.')
        val count = maxOf(leftParts.size, rightParts.size)

        for (i in 0 until count) {
            val l = leftParts.getOrNull(i) ?: return -1
            val r = rightParts.getOrNull(i) ?: return 1

            val lNum = l.toLongOrNull()
            val rNum = r.toLongOrNull()
            val cmp = when {
                lNum != null && rNum != null -> lNum.compareTo(rNum)
                lNum != null -> -1 // Numeric identifiers always have lower precedence than non-numeric.
                rNum != null -> 1
                else -> l.compareTo(r)
            }
            if (cmp != 0) return cmp
        }
        return 0
    }

    override fun toString(): String {
        val base = "$major.$minor.$patch"
        return if (preRelease != null) "$base-$preRelease" else base
    }

    companion object {
        private val SEMVER_REGEX = Regex(
            """^[vV]?(\d+)\.(\d+)\.(\d+)(?:-([0-9A-Za-z.-]+))?(?:\+[0-9A-Za-z.-]+)?$"""
        )

        fun parseOrNull(raw: String): SemanticVersion? {
            val trimmed = raw.trim()
            val match = SEMVER_REGEX.matchEntire(trimmed) ?: return null
            val major = match.groupValues[1].toIntOrNull() ?: return null
            val minor = match.groupValues[2].toIntOrNull() ?: return null
            val patch = match.groupValues[3].toIntOrNull() ?: return null
            val pre = match.groupValues[4].takeIf { it.isNotBlank() }
            if (pre != null && pre.split('.').any { it.isEmpty() }) return null
            return SemanticVersion(major, minor, patch, pre)
        }

        fun parse(raw: String): SemanticVersion {
            return parseOrNull(raw)
                ?: throw IllegalArgumentException("Invalid semantic version: '$raw'")
        }
    }
}
