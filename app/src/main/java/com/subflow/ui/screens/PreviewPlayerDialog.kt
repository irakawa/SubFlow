package com.subflow.ui.screens

import android.net.Uri
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.subflow.R
import com.subflow.ui.theme.SubFlowColors
import java.io.File

/**
 * Sync preview. play the subtitle over the real video before saving.
 * streams over http with a side-loaded .srt, starts at 60s to skip the intro.
 */
@OptIn(UnstableApi::class)
@Composable
fun PreviewPlayerDialog(httpUrl: String, srtContent: String, onDismiss: () -> Unit) {
    val context = LocalContext.current

    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    var player by remember { mutableStateOf<ExoPlayer?>(null) }

    // set up in DisposableEffect so it releases cleanly and no file i/o runs during composition
    DisposableEffect(lifecycleOwner) {
        val srtFile = File(context.cacheDir, "preview_sub.srt").apply {
            writeText(srtContent, Charsets.UTF_8)
        }
        val subtitle = MediaItem.SubtitleConfiguration.Builder(Uri.fromFile(srtFile))
            .setMimeType(MimeTypes.APPLICATION_SUBRIP)
            .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
            .build()
        val item = MediaItem.Builder()
            .setUri(httpUrl)
            .setSubtitleConfigurations(listOf(subtitle))
            .build()
        val exo = ExoPlayer.Builder(context).build().apply {
            setMediaItem(item)
            prepare()
            seekTo(60_000) // skip the intro
            playWhenReady = true
        }
        player = exo

        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) exo.pause()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            exo.release()
            srtFile.delete()
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(SubFlowColors.Background)
        ) {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        useController = true
                        setShowNextButton(false)
                        setShowPreviousButton(false)
                    }
                },
                update = { view -> view.player = player },
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
            )
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.align(Alignment.End).padding(4.dp)
            ) {
                Text(
                    stringResource(R.string.close),
                    style = MaterialTheme.typography.labelLarge,
                    color = SubFlowColors.Accent
                )
            }
        }
    }
}
