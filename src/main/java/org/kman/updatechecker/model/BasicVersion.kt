package org.kman.updatechecker.model

class BasicVersion private constructor(val major: Int, val minor: Int, val patch: Int, val build: Int, val branch: String?, val commit:
String?) {

    override fun toString(): String {
        return super.toString() + " - " + format()
    }

	fun format(): String {
		if (branch != null && commit != null) {
			return "${major}.${minor}.${patch}-${build}-${branch}-${commit}"
		} else if (build > 0) {
			return "${major}.${minor}.${patch}-${build}"
		} else {
			return "${major}.${minor}.${patch}"
		}
	}

	fun isNewerThan(ver: BasicVersion): Boolean {
		if (this.major > ver.major) {
			return true
		} else if (this.major < ver.major) {
			return false
		}

		if (this.minor > ver.minor) {
			return true
		} else if (this.minor < ver.minor) {
			return false
		}

		if (this.patch > ver.patch) {
			return true
		} else if (this.patch < ver.patch) {
			return false
		}

		if (this.build > ver.build) {
			return true
		} else if (this.build < ver.build) {
			return false
		}

		return false
	}

	companion object {
		val NONE = BasicVersion(0, 0, 0, 0, null, null)
		val REGEX_FULL_WITH_COMMIT = """(\d+)\.(\d+)\.(\d+)-(\d+)-([:alnum:]+)-([:alnum:]+)""".toRegex()
		val REGEX_FULL_WITH_BUILD = """(\d+)\.(\d+)\.(\d+)-(\d+)""".toRegex()
		val REGEX_SHORT = """(\d+)\.(\d+)\.(\d+)""".toRegex()

		fun fromText(text: String): BasicVersion {
			val matchFullWithCommit = REGEX_FULL_WITH_COMMIT.matchEntire(text)
			if (matchFullWithCommit != null) {
				val values = matchFullWithCommit.groupValues
				return BasicVersion(values[1].toInt(), values[2].toInt(), values[3].toInt(), values[4].toInt(),
						values[5], values[6])
			}

			val matchFullWithBuild = REGEX_FULL_WITH_BUILD.matchEntire(text)
			if (matchFullWithBuild != null) {
				val values = matchFullWithBuild.groupValues
				return BasicVersion(values[1].toInt(), values[2].toInt(), values[3].toInt(), values[4].toInt(),
						null, null)
			}

			val matchShort = REGEX_SHORT.matchEntire(text)
			if (matchShort != null) {
				val values = matchShort.groupValues
				return BasicVersion(values[1].toInt(), values[2].toInt(), values[3].toInt(), 0, null, null)
			}

			return NONE
		}
	}
}
