package org.kman.updatechecker.model

import android.net.Uri

class AvailableVersion private constructor(val prefix: String, val ver: BasicVersion, val time: Long) {

    override fun toString(): String {
        return super.toString() + " - " + ver.toString()
    }

    fun format(): String {
		return ver.format()
	}

	fun buildChangeLogUri(base: Uri): Uri {
		val builder = base.buildUpon()
		val path = "${prefix}-${ver.major}.${ver.minor}.${ver.patch}-${ver.build}" +
				"-${ver.branch}-${ver.commit}" +
				".apk-changes.txt"
		return builder.appendPath(path).build()
	}

	fun buildDownloadUri(base: Uri): Uri {
		val builder = base.buildUpon()
		val path = "${prefix}-${ver.major}.${ver.minor}.${ver.patch}-${ver.build}" +
				"-${ver.branch}-${ver.commit}" +
				".apk"
		return builder.appendPath(path).build()
	}

	fun isNewerThan(basic: BasicVersion): Boolean {
		return this.ver.isNewerThan(basic)
	}

	companion object {
		val NONE = AvailableVersion("", BasicVersion.NONE, 0)

		fun fromVersionFileText(text: String): AvailableVersion {
			var index = 0
			var prefix: String? = null
			var ver: BasicVersion = BasicVersion.NONE
			var time: Long = 0

			for (s in text.trim().splitToSequence('\t')) {
				when (index) {
					0 -> prefix = s
					1 -> ver = BasicVersion.fromText(s)
					2 -> time = s.toLong()
				}
				if (++index > 5) {
					break
				}
			}

			if (!prefix.isNullOrEmpty() && ver != BasicVersion.NONE && time > 0) {
				return AvailableVersion(prefix, ver, time)
			}
			return NONE
		}
	}
}
