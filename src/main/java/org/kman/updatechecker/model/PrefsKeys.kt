package org.kman.updatechecker.model

object PrefsKeys {
	val SAVED_FILE = "savedFile"

	val UPDATE_CHANNEL = "updateChannel"
	val UPDATE_CHANNEL_STABLE = 0
	val UPDATE_CHANNEL_DEV = 1
	val UPDATE_CHANNEL_BOTH = 2
	val UPDATE_CHANNEL_DEFAULT = UPDATE_CHANNEL_DEV

	val CHECK_ENABLED = "checkEnabled"
	val CHECK_ENABLED_DEFAULT = true

	val CHECK_INTERVAL_MINUTES = "checkInterval"
	val CHECK_INTERVAL_MINUTES_DEFAULT = 4 * 60	// 4 hours, value is in minutes
}
