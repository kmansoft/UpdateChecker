package org.kman.updatechecker.util

import android.util.Log
import java.util.*

object MyLog {
	fun i(tag: String, msg: String) {
		Log.i(tag, msg)
	}

	fun i(tag: String, format: String, vararg args: Any?) {
		Log.i(tag, String.format(Locale.US, format, *args))
	}

	fun w(tag: String, msg: String, t: Throwable) {
		Log.w(tag, msg, t)
	}
}
