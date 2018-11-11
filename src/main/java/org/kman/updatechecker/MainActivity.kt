package org.kman.updatechecker

import android.app.Activity
import android.app.Dialog
import android.app.ProgressDialog
import android.content.BroadcastReceiver
import android.content.Intent
import android.net.Uri
import android.os.*
import android.provider.Browser
import android.text.format.DateUtils
import android.transition.TransitionManager
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import kotlinx.coroutines.*
import org.kman.updatechecker.model.AvailableVersion
import org.kman.updatechecker.model.BasicVersion
import org.kman.updatechecker.model.Model
import org.kman.updatechecker.util.MyLog
import java.io.File

class MainActivity : Activity() {

	private companion object {
		val TAG = "MainActivity"

		val MIN_REFRESH_TIME = 500

		val DIALOG_ID_SETTINGS = 1

		val WHAT_CHANGE_SETTING = 1

		val SOURCE_CODE_LINK = Uri.parse("https://github.com/kmansoft/UpdateChecker")
	}

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(R.layout.activity_main)

		val footerView: TextView = findViewById(R.id.footer)
		footerView.text = getString(R.string.footer, BuildConfig.VERSION_NAME)
		footerView.setOnClickListener {
			startLink(SOURCE_CODE_LINK)
		}

		val lastObject = lastNonConfigurationInstance
		if (lastObject is Model.ApkDownloadTask) {
			val task = lastObject
			downloadTask = task
			showDownloadProgress(task)
		}

		updateReceiver = Model.registerUpdateMonitorReceiver(this) {
			checkJobStart()
		}

		startWork()
	}

	override fun onNewIntent(intent: Intent?) {
		startWork();
	}

	override fun onCreateOptionsMenu(menu: Menu?): Boolean {
		super.onCreateOptionsMenu(menu)

		menuInflater.inflate(R.menu.activity_main, menu)

		return true
	}

	override fun onOptionsItemSelected(item: MenuItem?): Boolean {
		when (item?.itemId) {
			R.id.menu_refresh -> checkJobStart()
			R.id.menu_settings -> showDialog(DIALOG_ID_SETTINGS)
			else -> return super.onOptionsItemSelected(item)
		}
		return true
	}

	override fun onDestroy() {
		super.onDestroy()

		checkJob?.cancel()
		checkJob = null

		downloadDialog?.dismiss()
		downloadDialog = null

		downloadTask?.cancel()
		downloadTask = null

		updateReceiver?.also {
			unregisterReceiver(it)
		}
		updateReceiver = null
	}

	override fun onRetainNonConfigurationInstance(): Any? {
		val task = downloadTask
		if (task != null) {
			// No call back for now
			task.clearCallback()

			// Clear so it's not cancelled in onDestroy
			downloadTask = null

			// Everything will rebuild in onCreate
			return task

		}
		return null
	}

	override fun onCreateDialog(id: Int): Dialog {
		when (id) {
			DIALOG_ID_SETTINGS -> {
				val dialog = PrefsDialog(this, handler.obtainMessage(WHAT_CHANGE_SETTING))
				dialog.setOnDismissListener { removeDialog(DIALOG_ID_SETTINGS) }
				return dialog
			}
			else -> return super.onCreateDialog(id)
		}
	}

	internal fun handleMessage(msg: Message): Boolean {
		when (msg.what) {
			WHAT_CHANGE_SETTING -> onChangeSettings()
			else -> return false
		}
		return true
	}

	private fun startLink(link: Uri) {
		val intent = Intent(Intent.ACTION_VIEW, link)
		intent.putExtra(Browser.EXTRA_APPLICATION_ID, BuildConfig.APPLICATION_ID)
		startActivity(intent)
	}

	private fun checkJobStart() {
		if (checkJob != null) {
			return
		}

		MyLog.i(TAG, "Starting check job from activity")

		checkJob = GlobalScope.launch(SupervisorJob() + Dispatchers.Main) {
			val progress: ProgressBar = findViewById(R.id.progress)
			val start = SystemClock.elapsedRealtime()

			try {
				progress.visibility = View.VISIBLE
				checkJobImpl()
			} catch (x: Throwable) {
				Log.w(TAG, "Top level catch", x)
			} finally {
				val elapsed = SystemClock.elapsedRealtime() - start
				val delay = MIN_REFRESH_TIME - elapsed
				if (delay > 0) {
					delay(delay)
				}

				progress.visibility = View.GONE

				if (checkJob == this) {
					checkJob = null
				}
			}
		}
	}

	private suspend fun checkJobImpl() {
		val appContext = applicationContext

		val container: ViewGroup = findViewById(R.id.version_container)

		val textInstalled: TextView = findViewById(R.id.version_installed)
		val textAvailable: TextView = findViewById(R.id.version_available)
		val textUpToDate: TextView = findViewById(R.id.version_up_to_date)
		val buttonDownload: Button = findViewById(R.id.button_download)
		val textChanges: TextView = findViewById(R.id.changes_web_site)

		textInstalled.text = null
		textAvailable.text = null
		textUpToDate.visibility = View.GONE
		buttonDownload.visibility = View.INVISIBLE
		textChanges.text = null

		// Installed version
		val verInstalled = try {
			withContext(Dispatchers.IO) {
				Model.getInstalledVersion(appContext)
			}
		} catch (x: Throwable) {
			MyLog.w(TAG, "Catch from get installed", x)
			textInstalled.text = x.toString()
			return
		}

		MyLog.i(TAG, "verInstalled = %s", verInstalled)

		if (verInstalled == BasicVersion.NONE) {
			textInstalled.text = getString(R.string.not_installed)
			return
		}

		textInstalled.text = verInstalled.format()

		// Available version
		val verAvailable = try {
			withContext(Dispatchers.IO) {
				Model.getAvailableVersion(appContext)
			}
		} catch (x: Throwable) {
			MyLog.w(TAG, "Catch from get available", x)
			textAvailable.text = x.toString()
			return
		}

		MyLog.i(TAG, "verAvailable = %s", verAvailable)

		if (verAvailable == AvailableVersion.NONE) {
			textAvailable.text = getString(R.string.no_available_version_info)
			return
		}

		val sb = StringBuilder()
		sb.append(verAvailable.format())
		sb.append("\n")
		sb.append(DateUtils.formatDateTime(appContext, verAvailable.time,
				DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH))

		textAvailable.text = sb

		if (verAvailable.isNewerThan(verInstalled)) {
			// There is a newer version, get changelog
			buttonDownload.visibility = View.VISIBLE
			buttonDownload.setOnClickListener {
				startDownload(verAvailable)
			}

			val valChangeLog = try {
				withContext(Dispatchers.IO) {
					Model.getChangeLog(appContext, verAvailable)
				}
			} catch (x: Throwable) {
				// ignore
				MyLog.w(TAG, "Catch from async", x)
				null
			}

			if (!valChangeLog.isNullOrEmpty()) {
				TransitionManager.beginDelayedTransition(container)
				textChanges.text = valChangeLog
			}
		} else {
			// No updates
			textUpToDate.visibility = View.VISIBLE

			CheckService.hideUpdateNotification(this)
		}
	}

	private fun startDownload(ver: AvailableVersion) {
		val task = Model.ApkDownloadTask(applicationContext, ver)
		downloadTask = task

		showDownloadProgress(task)
	}

	private fun showDownloadProgress(task: Model.ApkDownloadTask) {
		val dialog = ProgressDialog(this);
		dialog.setTitle(R.string.download_title)
		dialog.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL)
		dialog.setMessage(task.getVersion().format())
		dialog.isIndeterminate = false

		dialog.setOnDismissListener {
			if (downloadDialog == it) {
				downloadDialog = null
			}
		}
		dialog.setOnCancelListener {
			if (downloadDialog == it) {
				downloadTask?.cancel()
				downloadTask = null
			}
		}

		val callback = object : Model.ApkDownloadTask.Callback {
			override fun onApkDownloadProgress(progress: Int, total: Int) {
				if (total > 0) {
					dialog.progress = progress
					dialog.max = total
				} else {
					dialog.progress = 50
					dialog.max = 100
				}
			}

			override fun onApkDownloadDone(x: Throwable?) {
				val saveFile = downloadTask?.getSaveFile()
				downloadTask = null

				if (x == null) {
					dialog.dismiss()

					if (saveFile != null) {
						installDownloadedFile(saveFile)
					}

				} else if (x is CancellationException) {
					dialog.dismiss()
				} else {
					dialog.setMessage(x.toString())
				}
			}
		}

		task.setCallback(callback)
		dialog.show()

		downloadDialog = dialog
	}

	private fun installDownloadedFile(file: File) {
		val intent = Intent(Intent.ACTION_VIEW)
		intent.setDataAndType(Uri.fromFile(file), "application/vnd.android.package-archive")
		startActivity(intent)
	}

	private fun startWork() {
		CheckService.createUpdateJob(this)

		checkJobStart()
	}

	private fun onChangeSettings() {
		startWork()
	}

	private val handler: Handler by lazy {
		Handler(Looper.getMainLooper(), this::handleMessage)
	}

	private var checkJob: Job? = null

	private var downloadTask: Model.ApkDownloadTask? = null
	private var downloadDialog: ProgressDialog? = null

	private var updateReceiver: BroadcastReceiver? = null
}
