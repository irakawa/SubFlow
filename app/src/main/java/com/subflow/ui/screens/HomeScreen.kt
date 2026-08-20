package com.subflow.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.subflow.BuildConfig
import com.subflow.R
import com.subflow.data.AppSettings
import com.subflow.data.HistoryEntry
import com.subflow.optimization.DeviceProfiler
import com.subflow.ui.Haptics
import com.subflow.ui.SearchViewModel
import com.subflow.ui.theme.SubFlowColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

// drift params for the backdrop bars
private val backdropLines = List(10) { i ->
    Triple((i * 61 % 100) / 100f, 0.2f + (i * 37 % 60) / 100f, 0.25f + (i * 23 % 50) / 100f)
}

@Composable
fun HomeScreen(
    viewModel: SearchViewModel,
    onNewSearch: () -> Unit,
    onRetryLast: () -> Unit = {},
    onSettings: () -> Unit = {},
    onOpenHistory: (HistoryEntry) -> Unit = {},
    onFavorites: () -> Unit = {},
    onContinue: () -> Unit = {},
    onRunQueue: () -> Unit = {}
) {
    val history by viewModel.history.collectAsState()
    val hasLastRelease by viewModel.hasLastRelease.collectAsState()
    val continueHint by viewModel.continueWatching.collectAsState()
    val queueSize by viewModel.queueSize.collectAsState()
    val pipelineStatus by viewModel.pipelineStatus.collectAsState()
    val searchRunning = pipelineStatus == com.subflow.models.PipelineStatus.RUNNING
    val update by viewModel.update.collectAsState()
    val updateDownloading by viewModel.updateDownloading.collectAsState()
    androidx.compose.runtime.LaunchedEffect(Unit) { viewModel.checkForUpdate() }
    // show changelog once after an update
    var showWhatsNew by remember {
        mutableStateOf(BuildConfig.VERSION_CODE > AppSettings.lastSeenVersion)
    }

    // soft glow plus drifting bars behind the title, read in the draw phase so no recompose
    val transition = rememberInfiniteTransition(label = "homeBackdrop")
    val time = transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(11000, easing = LinearEasing), RepeatMode.Restart),
        label = "drift"
    )
    val accent = SubFlowColors.Accent

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(SubFlowColors.Background)
            .drawBehind {
                val t = time.value
                drawRect(
                    brush = Brush.radialGradient(
                        colors = listOf(accent.copy(alpha = 0.06f), Color.Transparent),
                        center = Offset(size.width * 0.5f, size.height * 0.16f),
                        radius = size.width * 0.95f
                    )
                )
                for ((yf, speed, wf) in backdropLines) {
                    val y = yf * size.height
                    val lineW = wf * size.width * 0.5f
                    val travel = (t * speed) % 1f
                    val x = size.width - travel * (size.width + lineW)
                    drawLine(
                        color = accent.copy(alpha = 0.035f),
                        start = Offset(x, y),
                        end = Offset(x + lineW, y),
                        strokeWidth = 3.dp.toPx(),
                        cap = StrokeCap.Round
                    )
                }
            }
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // favorites + settings, top-right
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            IconButton(onClick = onFavorites) {
                Icon(
                    Icons.Outlined.StarBorder,
                    contentDescription = stringResource(R.string.favorites),
                    tint = SubFlowColors.TextSecondary
                )
            }
            IconButton(onClick = onSettings) {
                Icon(
                    Icons.Outlined.Settings,
                    contentDescription = stringResource(R.string.settings),
                    tint = SubFlowColors.TextSecondary
                )
            }
        }
        Spacer(Modifier.height(8.dp))

        Text(
            "SUBFLOW",
            style = MaterialTheme.typography.displayLarge,
            color = SubFlowColors.TextPrimary,
            textAlign = TextAlign.Center
        )
        Text(
            stringResource(R.string.tagline),
            style = MaterialTheme.typography.labelSmall,
            color = SubFlowColors.TextSecondary,
            modifier = Modifier.padding(top = 8.dp)
        )

        Spacer(Modifier.height(40.dp))

        // offline-queued searches waiting for connectivity
        if (queueSize > 0) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(SubFlowColors.SurfaceAlt)
                    .border(1.dp, SubFlowColors.Border, RoundedCornerShape(12.dp))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    stringResource(R.string.queue_banner, queueSize),
                    style = MaterialTheme.typography.labelSmall,
                    color = SubFlowColors.TextSecondary,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = onRunQueue, enabled = !searchRunning) {
                    Text(stringResource(R.string.queue_run), color = SubFlowColors.Accent)
                }
                TextButton(onClick = { viewModel.clearQueue() }) {
                    Text(stringResource(R.string.queue_clear), color = SubFlowColors.TextSecondary, style = MaterialTheme.typography.labelSmall)
                }
            }
            Spacer(Modifier.height(16.dp))
        }

        PressableButton(text = stringResource(R.string.new_search), onClick = onNewSearch)
        if (hasLastRelease) {
            Spacer(Modifier.height(12.dp))
            // every entry point has to show the same refusal, or the user just finds a
            // different button that pretends to work
            PressableButton(
                text = stringResource(R.string.retry_last),
                onClick = onRetryLast,
                outline = true,
                enabled = !searchRunning
            )
        }

        // jump to the next episode of the last show
        continueHint?.let { hint ->
            Spacer(Modifier.height(16.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(SubFlowColors.Accent.copy(alpha = 0.10f))
                    .border(1.dp, SubFlowColors.Accent.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                    .clickable(enabled = !searchRunning) {
                        if (viewModel.searchContinue(hint).opensProgress) onContinue()
                    }
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.continue_watching), style = MaterialTheme.typography.labelSmall, color = SubFlowColors.TextSecondary)
                    Spacer(Modifier.height(2.dp))
                    Text(hint.label, style = MaterialTheme.typography.titleMedium, color = SubFlowColors.Accent)
                }
                Text("▶", color = SubFlowColors.Accent)
            }
        }

        Spacer(Modifier.height(36.dp))

        if (history.isNotEmpty()) {
            Row(Modifier.fillMaxWidth()) {
                Text(
                    stringResource(R.string.history_header),
                    style = MaterialTheme.typography.labelSmall,
                    color = SubFlowColors.TextSecondary
                )
            }
            Spacer(Modifier.height(12.dp))
        }

        if (history.isEmpty()) {
            Column(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text("🎞", style = MaterialTheme.typography.displayMedium)
                Spacer(Modifier.height(12.dp))
                Text(
                    stringResource(R.string.empty_history_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = SubFlowColors.TextSecondary
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    stringResource(R.string.empty_history_body),
                    style = MaterialTheme.typography.labelSmall,
                    color = SubFlowColors.TextSecondary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 32.dp)
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(history, key = { it.id }) { entry ->
                    SwipeToDeleteCard(
                        entry = entry,
                        onDelete = { viewModel.deleteHistory(entry) },
                        onOpen = { onOpenHistory(entry) }
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))
    }

    if (showWhatsNew) {
        WhatsNewDialog {
            AppSettings.lastSeenVersion = BuildConfig.VERSION_CODE
            showWhatsNew = false
        }
    }

    update?.let { info ->
        UpdateDialog(
            version = info.version,
            downloading = updateDownloading,
            onUpdate = { viewModel.installUpdate() },
            onDismiss = { viewModel.dismissUpdate() }
        )
    }
}

/** Offers to install a newer release from GitHub. */
@Composable
private fun UpdateDialog(version: String, downloading: Boolean, onUpdate: () -> Unit, onDismiss: () -> Unit) {
    // while the apk downloads the dialog can't be dismissed, so the install isn't cut off
    Dialog(onDismissRequest = { if (!downloading) onDismiss() }) {
        Column(
            modifier = Modifier
                .background(SubFlowColors.Surface, RoundedCornerShape(12.dp))
                .border(1.dp, SubFlowColors.Border, RoundedCornerShape(12.dp))
                .padding(24.dp)
        ) {
            Text(stringResource(R.string.update_available), style = MaterialTheme.typography.headlineMedium, color = SubFlowColors.Accent)
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.update_body, version), style = MaterialTheme.typography.bodyMedium, lineHeight = 22.sp)
            Spacer(Modifier.height(20.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                if (!downloading) {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.update_later), color = SubFlowColors.TextSecondary)
                    }
                    Spacer(Modifier.width(8.dp))
                }
                TextButton(onClick = onUpdate, enabled = !downloading) {
                    Text(
                        stringResource(if (downloading) R.string.update_downloading else R.string.update_now),
                        color = SubFlowColors.Accent
                    )
                }
            }
        }
    }
}

/** Changelog shown once after an update. */
@Composable
private fun WhatsNewDialog(onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .background(SubFlowColors.Surface, RoundedCornerShape(12.dp))
                .border(1.dp, SubFlowColors.Border, RoundedCornerShape(12.dp))
                .padding(24.dp)
        ) {
            Text(stringResource(R.string.whats_new), style = MaterialTheme.typography.headlineMedium, color = SubFlowColors.Accent)
            Spacer(Modifier.height(4.dp))
            Text("v${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.labelSmall, color = SubFlowColors.TextSecondary)
            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.whats_new_body), style = MaterialTheme.typography.bodyMedium, lineHeight = 24.sp)
            Spacer(Modifier.height(20.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.close), color = SubFlowColors.Accent)
                }
            }
        }
    }
}

/** Press feedback: dips to 0.96 then springs back, plus a haptic tick. */
@Composable
fun PressableButton(text: String, onClick: () -> Unit, outline: Boolean = false, enabled: Boolean = true) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale = remember { Animatable(1f) }
    val view = androidx.compose.ui.platform.LocalView.current
    LaunchedPressEffect(pressed, scale)

    Button(
        onClick = { Haptics.tick(view); onClick() },
        enabled = enabled,
        interactionSource = interaction,
        shape = RoundedCornerShape(50), // pill
        colors = if (outline) ButtonDefaults.outlinedButtonColors(contentColor = SubFlowColors.Accent)
        else ButtonDefaults.buttonColors(
            containerColor = SubFlowColors.Accent,
            contentColor = SubFlowColors.Background
        ),
        border = if (outline) androidx.compose.foundation.BorderStroke(1.dp, SubFlowColors.Accent) else null,
        modifier = Modifier.scale(scale.value)
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
    }
}

@Composable
private fun LaunchedPressEffect(pressed: Boolean, scale: Animatable<Float, *>) {
    androidx.compose.runtime.LaunchedEffect(pressed) {
        if (pressed) {
            scale.animateTo(0.96f, tween(DeviceProfiler.animMs(100)))
        } else {
            scale.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium))
        }
    }
}

/** Swipe-to-delete, springs back below the threshold. */
@Composable
private fun SwipeToDeleteCard(entry: HistoryEntry, onDelete: () -> Unit, onOpen: () -> Unit) {
    val offsetX = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .offset { IntOffset(offsetX.value.roundToInt(), 0) }
            .clickable { onOpen() }
            .pointerInput(entry.id) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        val widthPx = size.width.toFloat()
                        scope.launch {
                            if (abs(offsetX.value) > widthPx * 0.4f) {
                                offsetX.animateTo(
                                    if (offsetX.value > 0) widthPx else -widthPx,
                                    tween(DeviceProfiler.animMs(180))
                                )
                                onDelete()
                            } else {
                                // spring back
                                offsetX.animateTo(
                                    0f,
                                    spring(
                                        dampingRatio = Spring.DampingRatioMediumBouncy,
                                        stiffness = Spring.StiffnessLow
                                    )
                                )
                            }
                        }
                    },
                    onDragCancel = {
                        scope.launch { offsetX.animateTo(0f, spring()) }
                    },
                    onHorizontalDrag = { _, dragAmount ->
                        scope.launch { offsetX.snapTo(offsetX.value + dragAmount) }
                    }
                )
            }
            .background(SubFlowColors.Surface, RoundedCornerShape(12.dp))
            .border(1.dp, SubFlowColors.Border, RoundedCornerShape(12.dp))
            .padding(16.dp)
    ) {
        Column {
            Text(entry.title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(entry.detail, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Text(
                    stringResource(R.string.results_count, entry.resultCount, entry.method),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (entry.resultCount > 0) SubFlowColors.Success else SubFlowColors.Error
                )
                Text(
                    SimpleDateFormat("dd MMM HH:mm", Locale.getDefault()).format(Date(entry.timestamp)),
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
    }
}
