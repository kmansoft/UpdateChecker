package org.kman.updatechecker

import android.app.AlertDialog
import android.content.Context
import android.content.DialogInterface
import android.os.Bundle
import android.os.Message
import android.preference.PreferenceManager
import android.widget.*
import org.kman.updatechecker.model.PrefsKeys

class PrefsDialog(context: Context, confirmMessage: Message) : AlertDialog(context) {

	override fun onCreate(savedInstanceState: Bundle?) {
		val context = context
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
		val radioUpdateChannelId = when (updateChannel) {
			PrefsKeys.UPDATE_CHANNEL_STABLE -> R.id.prefs_update_channel_stable
			PrefsKeys.UPDATE_CHANNEL_DEV -> R.id.prefs_update_channel_dev
			PrefsKeys.UPDATE_CHANNEL_BOTH -> R.id.prefs_update_channel_both
			else -> R.id.prefs_update_channel_dev
		}
		radioUpdateChannel.check(radioUpdateChannelId)

		// Check enabled / interval

		val checkCheckEnabled: CheckBox = findViewById(R.id.prefs_check_enabled)

		val seekCheckInterval: SeekBar = findViewById(R.id.prefs_check_interval_bar)
		val textCheckInterval: TextView = findViewById(R.id.prefs_check_interval_value)

		val checkEnabled = prefs.getBoolean(PrefsKeys.CHECK_ENABLED, PrefsKeys.CHECK_ENABLED_DEFAULT)
		val checkInterval = prefs.getInt(PrefsKeys.CHECK_INTERVAL_MINUTES, PrefsKeys.CHECK_INTERVAL_MINUTES_DEFAULT)

		checkCheckEnabled.isChecked = checkEnabled

		val checkEnabledListener = CompoundButton.OnCheckedChangeListener { buttonView, isChecked ->
			seekCheckInterval.isEnabled = isChecked
			textCheckInterval.isEnabled = isChecked
		}
		checkCheckEnabled.setOnCheckedChangeListener(checkEnabledListener)

		seekCheckInterval.max = checkIntervalValueList.size - 1

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

	override fun onBackPressed() {
		cancel()
	}

	internal fun sendConfirmMessage() {
		val msg = confirmMessage
		if (msg != null) {
			confirmMessage = null

			// Update channel
			val radioUpdateChannel: RadioGroup = findViewById(R.id.prefs_update_channel_group)

			val updateChannel = when (radioUpdateChannel.checkedRadioButtonId) {
				R.id.prefs_update_channel_stable -> PrefsKeys.UPDATE_CHANNEL_STABLE
				R.id.prefs_update_channel_dev -> PrefsKeys.UPDATE_CHANNEL_DEV
				R.id.prefs_update_channel_both -> PrefsKeys.UPDATE_CHANNEL_BOTH
				else -> PrefsKeys.UPDATE_CHANNEL_DEFAULT
			}

			// Check enabled / interval interval
			val checkCheckEnabled: CheckBox = findViewById(R.id.prefs_check_enabled)
			val checkEnabled = checkCheckEnabled.isChecked

			val seekCheckInterval: SeekBar = findViewById(R.id.prefs_check_interval_bar)
			val checkInterval = checkIntervalValueList[seekCheckInterval.progress]

			// Save to prefs
			val prefs = PreferenceManager.getDefaultSharedPreferences(context)
			prefs.edit()
					.putInt(PrefsKeys.UPDATE_CHANNEL, updateChannel)
					.putBoolean(PrefsKeys.CHECK_ENABLED, checkEnabled)
					.putInt(PrefsKeys.CHECK_INTERVAL_MINUTES, checkInterval)
					.apply()

			msg.sendToTarget()
		}
	}

	private var confirmMessage: Message? = confirmMessage

	private val checkIntervalValueList by lazy {
		context.resources.getIntArray(R.array.prefs_check_interval_values)
	}
	private val checkIntervalLabelList by lazy {
		context.resources.getStringArray(R.array.prefs_check_interval_labels)
	}
}
