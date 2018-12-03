package org.kman.updatechecker.model

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.SystemClock
import android.preference.PreferenceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import okhttp3.CacheControl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.ResponseBody
import org.kman.updatechecker.util.MyLog
import java.io.*

object Model {
	val TAG = "Model"

	val AQUA_MAIL_PACKAGE = "org.kman.AquaMail"

	val VERSION_BASE = Uri.parse("https://www.aqua-mail.com/version")
	val VERSION_URL_STABLE = VERSION_BASE.buildUpon().appendPath("xversion-AquaMail-market.txt").build()
	val VERSION_URL_BETA = VERSION_BASE.buildUpon().appendPath("xversion-AquaMail-market-beta.txt").build()

	val DOWNLOAD_BASE = Uri.parse("https://www.aqua-mail.com/download")

	val PACKAGE_SCHEME = "package"
	val PACKAGE_PREFIX = PACKAGE_SCHEME + ":"

	private val BUFFER_SIZE = 64 * 1024

	private val httpClient = OkHttpClient()

	fun getInstalledVersion(context: Context): BasicVersion {
		return try {
			val pm = context.packageManager
			val pi = pm.getPackageInfo(AQUA_MAIL_PACKAGE, 0)
			BasicVersion.fromText(pi.versionName)
		} catch (x: PackageManager.NameNotFoundException) {
			// Not installed
			BasicVersion.NONE
		}
	}

	fun registerUpdateMonitorReceiver(context: Context, block: () -> kotlin.Unit) : BroadcastReceiver {
		val receiver = object : BroadcastReceiver() {
			override fun onReceive(context: Context?, intent: Intent?) {
				if (intent != null) {
					intent.data?.also {
						val s = it.toString()
						if (s.startsWith(PACKAGE_PREFIX)) {
							MyLog.i(TAG, "Package replaced: %s", it)
							val name = s.substring(PACKAGE_PREFIX.length)
							if (AQUA_MAIL_PACKAGE.equals(name)) {
								block()
							}
						}
					}
				}
			}
		}

		val filter = IntentFilter(Intent.ACTION_PACKAGE_REPLACED)
		filter.addDataScheme(PACKAGE_SCHEME)

		context.registerReceiver(receiver, filter)

		return receiver
	}

	fun getAvailableVersion(context: Context): AvailableVersion {
		val prefs = PreferenceManager.getDefaultSharedPreferences(context)
		val updateChannel = prefs.getInt(PrefsKeys.UPDATE_CHANNEL, PrefsKeys.UPDATE_CHANNEL_DEFAULT)

		when (updateChannel) {
			PrefsKeys.UPDATE_CHANNEL_STABLE ->
				// Stable
				return getAvailableVersionImpl(context, VERSION_URL_STABLE)
			PrefsKeys.UPDATE_CHANNEL_BOTH -> {
				// Both
				val versionBeta = getAvailableVersionImpl(context, VERSION_URL_BETA)
				val versionStable = getAvailableVersionImpl(context, VERSION_URL_STABLE)

				if (versionBeta.isNewerThan(versionStable.ver) || versionStable == AvailableVersion.NONE) {
					return versionBeta
				}
				return versionStable
			}
			else ->
				// Beta is default
				return getAvailableVersionImpl(context, VERSION_URL_BETA)
		}
	}

	@Suppress("UNUSED_PARAMETER")
	fun getChangeLog(context: Context, version: AvailableVersion): String {
		val body = withTiming("Get changes") {
			val uri = version.buildChangeLogUri(DOWNLOAD_BASE)
			executeHttpRequest(uri)
		}

		val text = body.string()
		if (text.isNullOrEmpty()) {
			return ""
		}

		val sb = StringBuilder()
		for (line in text.lineSequence()) {
			if (line.startsWith("Version ")) {
				if (sb.isNotEmpty()) {
					break
				}
				continue
			}
			sb.append(line).append("\n")
		}
		return sb.toString().trim()
	}

	class ApkDownloadTask(private val context: Context, private val ver: AvailableVersion) {

		interface Callback {
			fun onApkDownloadProgress(progress: Int, total: Int)
			fun onApkDownloadDone(x: Throwable?)
		}

		fun cancel() {
			job.cancel()
		}

		fun getVersion(): AvailableVersion {
			return ver
		}

		fun getSaveFile(): File {
			return saveFile
		}

		fun setCallback(c: Callback) {
			callback = c
		}

		fun clearCallback() {
			callback = null
		}

		private class Progress(val progress: Int, val total: Int)

		private val job = SupervisorJob()
		private val uri = ver.buildDownloadUri(Model.DOWNLOAD_BASE)
		private val saveFile = File(context.externalCacheDir, uri.lastPathSegment)
		private val channel = Channel<Progress>(Channel.CONFLATED)

		private var callback: Callback? = null

		init {
			GlobalScope.launch(job + Dispatchers.IO) {
				removeOldRetainedFile()

				var retainFile = false
				val body = executeHttpRequest(uri)
				try {
					BufferedOutputStream(FileOutputStream(saveFile), BUFFER_SIZE).use {
						retainFile = saveHttpToFile(it, body)
					}
				} catch (x: Exception) {
					channel.close(x)
				} finally {
					channel.close()

					if (retainFile) {
						saveNewRetainedFileName()
					} else {
						saveFile.delete()
					}
				}
			}

			GlobalScope.launch(job + Dispatchers.Main) {
				try {
					for (progress in channel) {
						MyLog.i(TAG, "apk download: %d / %d", progress.progress, progress.total)
						callback?.onApkDownloadProgress(progress.progress, progress.total)
					}
					callback?.onApkDownloadDone(null)
				} catch (x: Throwable) {
					MyLog.w(TAG, "apk download: channel exception", x)
					callback?.onApkDownloadDone(x)
				}
			}
		}

		private fun removeOldRetainedFile() {
			val prefs = PreferenceManager.getDefaultSharedPreferences(context)
			val name = prefs.getString(PrefsKeys.SAVED_FILE, null)
			if (!name.isNullOrEmpty()) {
				val file = File(name)
				file.delete()
			}
		}

		private fun saveNewRetainedFileName() {
			val prefs = PreferenceManager.getDefaultSharedPreferences(context)
			prefs.edit()
					.putString(PrefsKeys.SAVED_FILE, saveFile.absolutePath)
					.apply()
		}

		private suspend fun saveHttpToFile(fileStream: OutputStream, body: ResponseBody): Boolean {

			body.byteStream().use {
				var progress = 0
				val total = body.contentLength().toInt()
				var curPercent = 0

				if (job.isCancelled) {
					return false
				}

				channel.send(Progress(progress, total))

				val buf = ByteArray(BUFFER_SIZE)
				while (true) {
					if (job.isCancelled) {
						return false
					}

					val r = it.read(buf)
					if (r <= 0) {
						break
					}

					fileStream.write(buf, 0, r)

					progress += r

					MyLog.i(TAG, "apk download: %d of %d", progress, total)

					val newPercent = 100 * progress / total
					if (curPercent != newPercent) {
						curPercent = newPercent
						channel.send(Progress(progress, total))
					}
				}

				MyLog.i(TAG, "apk download: done, %d of %d", progress, total)
				channel.send(Progress(progress, total))
				return true
			}
		}
	}

	@Suppress("UNUSED_PARAMETER")
	private fun getAvailableVersionImpl(context: Context, uri: Uri): AvailableVersion {
		val body = withTiming("Get version") {
			executeHttpRequest(uri)
		}

		val text = body.string()
		if (text.isNullOrEmpty()) {
			return AvailableVersion.NONE
		}

		return AvailableVersion.fromVersionFileText(text)
	}

	private fun executeHttpRequest(uri: Uri): ResponseBody {
		val request = Request.Builder().url(uri.toString()).get().cacheControl(CacheControl.FORCE_NETWORK).build()
		val result = httpClient.newCall(request).execute()

		if (!result.isSuccessful) {
			throw IOException("http error " + result.code())
		}
		return result.body()!!
	}

	private inline fun <T : Any> withTiming(msg: String, block: () -> T): T {
		val ms0 = SystemClock.elapsedRealtime()

		val r = block()

		val ms1 = SystemClock.elapsedRealtime()

		MyLog.i(TAG, "%s: %d ms", msg, ms1 - ms0)

		return r
	}
}
