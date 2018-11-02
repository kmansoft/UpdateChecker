package org.kman.updatechecker

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.PersistableBundle
import android.preference.PreferenceManager
import kotlinx.coroutines.*
import org.kman.updatechecker.model.AvailableVersion
import org.kman.updatechecker.model.BasicVersion
import org.kman.updatechecker.model.Model
import org.kman.updatechecker.model.PrefsKeys
import org.kman.updatechecker.util.MyLog
import java.util.concurrent.TimeUnit

class CheckService : JobService() {

	companion object {
		private val TAG = "CheckService"

		private val PERIODIC_JOB_ID = 1

		private val NOTIFICATION_ID = 1

		private val KEY_SET_AT = "setAt"
		private val RESET_PERIOD = TimeUnit.DAYS.toMillis(4)
		private val BACKOFF_PERIOD = TimeUnit.MINUTES.toMillis(15)

		fun createUpdateJob(context: Context) {
			val now = System.currentTimeMillis()
			val scheduler = context.getSystemService(android.content.Context.JOB_SCHEDULER_SERVICE) as JobScheduler

			var existing: JobInfo? = null
			for (job in scheduler.allPendingJobs) {
				if (job.id == CheckService.PERIODIC_JOB_ID) {
					existing = job
					break
				}
			}

			val prefs = PreferenceManager.getDefaultSharedPreferences(context)
			val minutes = prefs.getInt(PrefsKeys.CHECK_INTERVAL_MINUTES, PrefsKeys.CHECK_INTERVAL_MINUTES_DEFAULT)
			val interval = TimeUnit.MINUTES.toMillis(minutes.toLong())

			if (existing != null) {
				val extras = existing.extras
				if (now - extras.getLong(KEY_SET_AT, 0) < RESET_PERIOD &&
						existing.isPeriodic &&
						existing.intervalMillis == interval) {
					if (!BuildConfig.DEBUG) {
						// Existing job still good, leave alone
						return
					}
				}
			}

			val extras = PersistableBundle()
			extras.putLong(KEY_SET_AT, now)

			val builder = JobInfo.Builder(PERIODIC_JOB_ID, ComponentName(context, CheckService::class.java))
			val info = builder.apply {
				setPeriodic(interval)
				setBackoffCriteria(BACKOFF_PERIOD, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
				setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
				setExtras(extras)
			}.build()
			scheduler.schedule(info)
		}

		fun hideUpdateNotification(context: Context) {
			val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
			nm.cancel(NOTIFICATION_ID)
		}
	}

	override fun onStartJob(params: JobParameters?): Boolean {
		MyLog.i(TAG, "onStartJob: %s", params)

		cancelJob()

		if (params != null) {
			MyLog.i(TAG, "Starting check job ${params} from service")

			checkJob = GlobalScope.launch(Dispatchers.Main) {
				var worked = true
				var cancelled = false
				try {
					checkJobImpl(applicationContext)
				} catch (x: CancellationException) {
					MyLog.w(TAG, "Check job ${params} cancelled", x)
					cancelled = true
				} catch (x: Throwable) {
					MyLog.w(TAG, "Check job ${params} exception", x)
					worked = false
				} finally {
					if (!cancelled) {
						jobFinished(params, !worked)
					}

					if (checkJob == this) {
						checkJob = null
					}
				}
			}

			return true
		}

		return false
	}

	override fun onStopJob(params: JobParameters?): Boolean {
		MyLog.i(TAG, "onStopJob: %s", params)

		cancelJob()

		return true
	}

	override fun onDestroy() {
		MyLog.i(TAG, "onDestroy")
		super.onDestroy()

		cancelJob()
	}

	private fun cancelJob() {
		checkJob?.cancel()
		checkJob = null
	}

	private suspend fun checkJobImpl(appContext: Context) {
		val verInstalled = withContext(Dispatchers.IO) {
			Model.getInstalledVersion(appContext)
		}

		MyLog.i(TAG, "Ver installed = %s", verInstalled)

		if (verInstalled == BasicVersion.NONE) {
			return
		}

		val verAvailable = withContext(Dispatchers.IO) {
			Model.getAvailableVersion(appContext)
		}

		MyLog.i(TAG, "Ver available = %s", verAvailable)

		if (verAvailable == AvailableVersion.NONE) {
			return
		}

		if (verAvailable.isNewerThan(verInstalled)) {
			showUpdateNotification(verAvailable)
		} else {
			hideUpdateNotification()
		}
	}

	private fun showUpdateNotification(ver: AvailableVersion) {
		val intent = Intent(this, MainActivity::class.java)
		intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
		intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)

		intent.setAction(Intent.ACTION_MAIN)
		intent.addCategory(Intent.CATEGORY_LAUNCHER)

		val pending = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT)

		val res = resources
		val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
		val builder = Notification.Builder(this)

		val notification = builder.apply {
			setSmallIcon(R.drawable.ic_notify_get_app_white_24dp)
			setContentTitle(getString(R.string.notification_title))
			setContentText(ver.format())
			setContentIntent(pending)
			setColor(res.getColor(R.color.colorPrimary))
			setAutoCancel(true)
		}.build()

		nm.notify(NOTIFICATION_ID, notification)
	}

	private fun hideUpdateNotification() {
		hideUpdateNotification(this)
	}

	private var checkJob: Job? = null
}
