package com.subflow.utils

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.subflow.optimization.DeviceProfiler
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import okhttp3.Request

// downloads the release apk and hands it to the system installer. android installs it over the
// current app (same signing key), so user data is kept, no uninstall needed.
object ApkInstaller {

    suspend fun downloadAndInstall(context: Context, url: String): Boolean = withContext(DeviceProfiler.ioDispatcher) {
        try {
            val dir = File(context.cacheDir, "shared").apply { mkdirs() }
            val apk = File(dir, "update.apk")
            val req = Request.Builder().url(url).header("User-Agent", Net.USER_AGENT).build()
            Net.client.newCall(req).execute().use { resp ->
                val stream = resp.body?.takeIf { resp.isSuccessful }?.byteStream() ?: return@withContext false
                // stream to disk so a 100MB+ apk never sits in memory
                stream.use { input -> apk.outputStream().use { out -> input.copyTo(out) } }
            }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
    }
}
