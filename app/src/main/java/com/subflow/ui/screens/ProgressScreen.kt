package com.subflow.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.subflow.R
import com.subflow.models.LogEntry
import com.subflow.models.LogLevel
import com.subflow.models.PipelineStatus
import com.subflow.optimization.DeviceProfiler
import com.subflow.ui.SearchViewModel
import com.subflow.ui.theme.JetBrainsMonoFamily
import com.subflow.ui.theme.SubFlowColors

@Composable
fun ProgressScreen(viewModel: SearchViewModel, onDone: () -> Unit, onCancel: () -> Unit) {
    val logs by viewModel.logs.collectAsState()
    val progress by viewModel.progress.collectAsState()
    val status by viewModel.pipelineStatus.collectAsState()
    val currentSource by viewModel.currentSource.collectAsState()
    val whisperConsent by viewModel.whisperConsentNeeded.collectAsState()
    val notice by viewModel.notice.collectAsState()
    val listState = rememberLazyListState()

    // auto-scroll as new logs arrive
    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) listState.animateScrollToItem(logs.size - 1)
    }
    LaunchedEffect(status) {
        if (status == PipelineStatus.DONE) onDone()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(SubFlowColors.Background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp)
    ) {
        Spacer(Modifier.height(20.dp))

        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            PulsingDot(active = status == PipelineStatus.RUNNING)
            Spacer(Modifier.padding(start = 12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    currentSource?.first ?: when (status) {
                        PipelineStatus.RUNNING -> stringResource(R.string.pipeline_running)
                        PipelineStatus.FAILED -> stringResource(R.string.pipeline_not_found)
                        PipelineStatus.CANCELLED -> stringResource(R.string.pipeline_cancelled)
                        else -> stringResource(R.string.pipeline_preparing)
                    },
                    style = MaterialTheme.typography.titleMedium
                )
                currentSource?.let { (_, i, total) ->
                    Text(
                        stringResource(R.string.source_x_of_y, i, total),
                        style = MaterialTheme.typography.labelSmall,
                        color = SubFlowColors.TextSecondary
                    )
                }
            }
        }

        // prominent hint when the search failed for a knowable reason (e.g. the episode
        // number doesn't exist for this series), so it doesn't read as an app failure
        notice?.let { msg ->
            Spacer(Modifier.height(12.dp))
            Text(
                msg,
                style = MaterialTheme.typography.bodyMedium,
                color = SubFlowColors.Accent,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(SubFlowColors.Surface)
                    .padding(14.dp)
            )
        }

        Spacer(Modifier.height(16.dp))

        // progress bar, shimmer while indeterminate
        ShimmerProgressBar(progress = progress)

        Spacer(Modifier.height(16.dp))

        // live log stream
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(SubFlowColors.Surface)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items(logs, key = { it.id }) { entry ->
                LogLine(entry)
            }
        }

        Spacer(Modifier.height(16.dp))

        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            PressableButton(
                text = if (status == PipelineStatus.RUNNING) stringResource(R.string.cancel)
                       else stringResource(R.string.go_back),
                onClick = onCancel,
                outline = true
            )
        }
        Spacer(Modifier.height(20.dp))
    }

    // whisper download consent
    if (whisperConsent) {
        ModalBottomSheet(
            onDismissRequest = { viewModel.answerWhisperConsent(false) },
            containerColor = SubFlowColors.SurfaceAlt
        ) {
            Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(R.string.whisper_title), style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.height(12.dp))
                Text(
                    stringResource(R.string.whisper_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = SubFlowColors.TextSecondary
                )
                Spacer(Modifier.height(20.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    PressableButton(text = stringResource(R.string.download), onClick = { viewModel.answerWhisperConsent(true) })
                    PressableButton(text = stringResource(R.string.cancel), onClick = { viewModel.answerWhisperConsent(false) }, outline = true)
                }
                Spacer(Modifier.height(32.dp))
            }
        }
    }
}

@Composable
private fun LogLine(entry: LogEntry) {
    // animate each line in. needs a MutableTransitionState, a fixed visible=true wouldn't animate
    val visibleState = remember {
        androidx.compose.animation.core.MutableTransitionState(false).apply { targetState = true }
    }
    AnimatedVisibility(
        visibleState = visibleState,
        enter = slideInVertically(tween(DeviceProfiler.animMs(200))) { 40 } +
            fadeIn(tween(DeviceProfiler.animMs(250)))
    ) {
        val color = when (entry.level) {
            LogLevel.OK -> SubFlowColors.Success
            LogLevel.WARN -> SubFlowColors.Accent
            LogLevel.ERROR -> SubFlowColors.Error
            LogLevel.STEP -> SubFlowColors.TextPrimary
            LogLevel.INFO -> SubFlowColors.TextSecondary
        }
        Text(
            entry.message,
            fontFamily = JetBrainsMonoFamily,
            fontSize = 12.sp,
            lineHeight = 17.sp,
            color = color
        )
    }
}

@Composable
private fun PulsingDot(active: Boolean) {
    val transition = rememberInfiniteTransition(label = "pulse")
    val scale by transition.animateFloat(
        initialValue = 0.7f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            tween(DeviceProfiler.animMs(600)),
            RepeatMode.Reverse
        ),
        label = "pulseScale"
    )
    Box(
        Modifier
            .size(14.dp)
            .scale(if (active) scale else 1f)
            .clip(CircleShape)
            .background(if (active) SubFlowColors.Accent else SubFlowColors.Border)
    )
}

/** 4dp bar, shimmer until progress is known. */
@Composable
private fun ShimmerProgressBar(progress: Float) {
    val animated by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = tween(DeviceProfiler.animMs(400)),
        label = "progress"
    )
    val transition = rememberInfiniteTransition(label = "shimmer")
    val shimmerX by transition.animateFloat(
        initialValue = -1f,
        targetValue = 2f,
        animationSpec = infiniteRepeatable(tween(DeviceProfiler.animMs(1200))),
        label = "shimmerX"
    )

    Box(
        Modifier
            .fillMaxWidth()
            .height(4.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(SubFlowColors.Border)
    ) {
        if (progress < 0f) {
            // indeterminate shimmer
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.linearGradient(
                            colors = listOf(
                                Color.Transparent,
                                SubFlowColors.Accent.copy(alpha = 0.9f),
                                Color.Transparent
                            ),
                            start = Offset(shimmerX * 800f, 0f),
                            end = Offset(shimmerX * 800f + 400f, 0f)
                        )
                    )
            )
        } else {
            Box(
                Modifier
                    .fillMaxWidth(animated)
                    .fillMaxHeight()
                    .background(
                        Brush.linearGradient(
                            colors = listOf(
                                SubFlowColors.Accent,
                                SubFlowColors.Accent.copy(alpha = 0.75f + 0.25f * kotlin.math.abs(1f - shimmerX)),
                                SubFlowColors.Accent
                            )
                        )
                    )
            )
        }
    }
}
