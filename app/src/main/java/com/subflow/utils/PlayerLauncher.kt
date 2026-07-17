package com.subflow.utils

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import com.subflow.R

/**
 * Opens the user's video in an external player with the subtitle side-loaded.
 * The video isn't copied, we just forward the read grant for the picked Uri.
 */
object PlayerLauncher {

    fun openInPlayer(context: Context, videoUri: Uri, fileName: String, srtContent: String) {
        val subUri = FileUtils.writeShareFiles(context, listOf(fileName to srtContent)).firstOrNull()
        if (subUri == null) {
            Toast.makeText(context, context.getString(R.string.save_failed), Toast.LENGTH_SHORT).show()
            return
        }

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(videoUri, "video/*")
            // grant the video (data) and subtitle (clip) to the player
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            clipData = ClipData.newRawUri("subtitle", subUri)
            // MX Player
            putExtra("subs", arrayOf(subUri))
            putExtra("subs.name", arrayOf(fileName))
            // VLC
            putExtra("subtitles_location", subUri.toString())
        }

        try {
            context.startActivity(Intent.createChooser(intent, context.getString(R.string.open_in_player)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, context.getString(R.string.no_player), Toast.LENGTH_SHORT).show()
        }
    }
}
