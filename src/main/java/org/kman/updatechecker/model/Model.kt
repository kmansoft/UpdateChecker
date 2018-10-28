package org.kman.updatechecker.model

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.SystemClock
import android.preference.PreferenceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
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

	private val BUFFER_SIZE = 64 * 1024

	private val httpClient = OkHttpClient()

	fun getInstalledVersionSync(context: Context): BasicVersion {
		try {
			val pm = context.packageManager
			val pi = pm.getPackageInfo(AQUA_MAIL_PACKAGE, 0)
			return BasicVersion.fromText(pi.versionName)
		} catch (x: PackageManager.NameNotFoundException) {
			// Not installed
			return BasicVersion.NONE
		}
	}

	@Suppress("UNUSED_PARAMETER")
	fun getAvailableVersionSync(context: Context): AvailableVersion {
		val prefs = PreferenceManager.getDefaultSharedPreferences(context)
		val updateChannel = prefs.getInt(PrefsKeys.UPDATE_CHANNEL, PrefsKeys.UPDATE_CHANNEL_DEFAULT)
		val uri = when (updateChannel) {
			PrefsKeys.UPDATE_CHANNEL_STABLE -> VERSION_URL_STABLE
			PrefsKeys.UPDATE_CHANNEL_DEV -> VERSION_URL_BETA
			else -> VERSION_URL_STABLE
		}

		val body = withTiming("Get version") {
			val request = Request.Builder().url(uri.toString()).get().build()
			val result = httpClient.newCall(request).execute()

			checkHttpResult(result)
		}

		val text = body.string()
		if (text.isNullOrEmpty()) {
			return AvailableVersion.NONE
		}

		return AvailableVersion.fromVersionFileText(text)
	}

	@Suppress("UNUSED_PARAMETER")
	fun getChangeLogSync(context: Context, version: AvailableVersion): String {
		val body = withTiming("Get changes") {
			val uri = version.buildChangeLogUri(DOWNLOAD_BASE)
			val request = Request.Builder().url(uri.toString()).get().build()
			val result = httpClient.newCall(request).execute()

			checkHttpResult(result)
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
				val request = Request.Builder().url(uri.toString()).get().build()
				try {
					BufferedOutputStream(FileOutputStream(saveFile), BUFFER_SIZE).use {
						val result = httpClient.newCall(request).execute()
						retainFile = saveHttpToFile(it, result)
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

		internal fun removeOldRetainedFile() {
			val prefs = PreferenceManager.getDefaultSharedPreferences(context)
			val name = prefs.getString(PrefsKeys.SAVED_FILE, null)
			if (!name.isNullOrEmpty()) {
				val file = File(name)
				file.delete()
			}
		}

		internal fun saveNewRetainedFileName() {
			val prefs = PreferenceManager.getDefaultSharedPreferences(context)
			prefs.edit().putString(PrefsKeys.SAVED_FILE, saveFile.absolutePath).apply()
		}

		internal suspend fun saveHttpToFile(fileStream: OutputStream, result: Response): Boolean {
			val body = checkHttpResult(result)

			body.byteStream().use {
				var progress = 0
				val total = body.contentLength().toInt()
				var curPercent = 0

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

	internal fun checkHttpResult(result: Response): ResponseBody {
		if (!result.isSuccessful) {
			throw IOException("http error " + result.code())
		}
		return result.body()!!
	}

	internal inline fun <T : Any> withTiming(msg: String, block: () -> T): T {
		val ms0 = SystemClock.elapsedRealtime()

		val r = block()

		val ms1 = SystemClock.elapsedRealtime()

		MyLog.i(TAG, "%s: %d ms", msg, ms1 - ms0)

		return r
	}
}