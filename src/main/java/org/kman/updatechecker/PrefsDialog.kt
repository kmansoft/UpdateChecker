package org.kman.updatechecker

import android.app.AlertDialog
import android.content.Context
import android.content.DialogInterface
import android.os.Bundle
import android.os.Message
import android.preference.PreferenceManager
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import org.kman.updatechecker.model.PrefsKeys

class PrefsDialog(context: Context, confirmMessage: Message) : AlertDialog(context) {

	override fun onCreate(savedInstanceState: Bundle?) {
		val context = context
		val res = context.resources
		val inflater = layoutInflater

		setCancelable(false)
		setTitle(R.string.prefs_title)

		val view = inflater.inflate(R.layout.prefs_dialog, null, false)
		setView(view)

		setButton(DialogInterface.BUTTON_POSITIVE, context.getString(R.string.ok)) { _, _ ->
			sendConfirmMessage()
		}

		setButton(DialogInterface.BUTTON_NEGATIVE, context.getString(R.string.cancel)) { _, _ ->
		}

		super.onCreate(savedInstanceState)

		val prefs = PreferenceManager.getDefaultSharedPreferences(context)

		// Update channel (stable / development)
		val updateChannel = prefs.getInt(PrefsKeys.UPDATE_CHANNEL, PrefsKeys.UPDATE_CHANNEL_DEFAULT)

		val radioUpdateChannel: RadioGroup = findViewById(R.id.prefs_update_channel_group)
		when (updateChannel) {
			PrefsKeys.UPDATE_CHANNEL_STABLE -> radioUpdateChannel.check(R.id.prefs_update_channel_stable)
			PrefsKeys.UPDATE_CHANNEL_DEV -> radioUpdateChannel.check(R.id.prefs_update_channel_dev)
			else -> radioUpdateChannel.check(R.id.prefs_update_channel_dev)
		}

		// Check interval

		val seekCheckInterval: SeekBar = findViewById(R.id.prefs_check_interval_bar)
		val textCheckInterval: TextView = findViewById(R.id.prefs_check_interval_value)

		val checkIntervalValueList = res.getIntArray(R.array.prefs_check_interval_values)
		val checkIntervalLabelList = res.getStringArray(R.array.prefs_check_interval_labels)

		seekCheckInterval.max = checkIntervalValueList.size - 1

		val checkInterval = prefs.getInt(PrefsKeys.CHECK_INTERVAL_MINUTES, PrefsKeys.CHECK_INTERVAL_MINUTES_DEFAULT)
		val checkIntervalIndex = checkIntervalValueList.indexOf(checkInterval)
		if (checkIntervalIndex >= 0) {
			seekCheckInterval.progress = checkIntervalIndex
			textCheckInterval.text = checkIntervalLabelList[checkIntervalIndex]
		} else {
			seekCheckInterval.progress = 0
			textCheckInterval.text = checkIntervalLabelList[0]
		}

		val checkIntervalListener = object : SeekBar.OnSeekBarChangeListener {
			override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
				textCheckInterval.text = checkIntervalLabelList[progress]
			}

			override fun onStartTrackingTouch(seekBar: SeekBar?) {
			}

			override fun onStopTrackingTouch(seekBar: SeekBar?) {
			}
		}

		seekCheckInterval.setOnSeekBarChangeListener(checkIntervalListener)
	}

	internal fun sendConfirmMessage() {
		val msg = confirmMessage
		if (msg != null) {
			confirmMessage = null

			val context = context
			val res = context.resources

			// Update channel
			val radioUpdateChannel: RadioGroup = findViewById(R.id.prefs_update_channel_group)

			val updateChannel = when (radioUpdateChannel.checkedRadioButtonId) {
				R.id.prefs_update_channel_stable -> PrefsKeys.UPDATE_CHANNEL_STABLE
				R.id.prefs_update_channel_dev -> PrefsKeys.UPDATE_CHANNEL_DEV
				else -> PrefsKeys.UPDATE_CHANNEL_DEFAULT
			}

			// Check interval
			val checkIntervalValueList = res.getIntArray(R.array.prefs_check_interval_values)
			val seekCheckInterval: SeekBar = findViewById(R.id.prefs_check_interval_bar)

			val checkInterval = checkIntervalValueList[seekCheckInterval.progress]

			// Save to prefs
			val prefs = PreferenceManager.getDefaultSharedPreferences(context)
			prefs.edit()
					.putInt(PrefsKeys.UPDATE_CHANNEL, updateChannel)
					.putInt(PrefsKeys.CHECK_INTERVAL_MINUTES, checkInterval)
					.apply()

			msg.sendToTarget()
		}
	}

	private var confirmMessage: Message? = confirmMessage
}
