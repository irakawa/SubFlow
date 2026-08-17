package com.subflow.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.subflow.R
import com.subflow.models.SubtitleResult
import com.subflow.pipeline.SyncEngine
import com.subflow.ui.Haptics
import com.subflow.ui.SearchViewModel
import com.subflow.ui.theme.SubFlowColors
import com.subflow.utils.FileUtils
import com.subflow.utils.PlayerLauncher
import kotlin.math.sin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ResultScreen(
    viewModel: SearchViewModel,
    onSearchAgain: () -> Unit,
    onHome: () -> Unit,
    onRetryFailed: () -> Unit = {}
) {
    val results by viewModel.results.collectAsState()
    val failedCount by viewModel.retryableFailedCount.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    var savedAll by remember { mutableStateOf<String?>(null) }

    // haptic buzz once when results first show
    LaunchedEffect(results.isNotEmpty()) {
        if (results.isNotEmpty()) Haptics.success(view)
    }

    Box(Modifier.fillMaxSize().background(SubFlowColors.Background)) {
        if (results.isNotEmpty()) ParticleOverlay()

        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
        ) {
            Spacer(Modifier.height(24.dp))
            Text(
                if (results.isNotEmpty()) stringResource(R.string.results_ready)
                else stringResource(R.string.no_results),
                style = MaterialTheme.typography.displayMedium,
                color = if (results.isNotEmpty()) SubFlowColors.TextPrimary else SubFlowColors.TextSecondary
            )
            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(R.string.srt_produced, results.size),
                style = MaterialTheme.typography.labelSmall,
                color = SubFlowColors.TextSecondary
            )
            Spacer(Modifier.height(20.dp))

            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // key by content identity, not index, so card state can't attach to the wrong subtitle on reorder
                itemsIndexed(
                    results,
                    key = { _, r -> "${r.episodeLabel}/${r.fileName}/${r.content.hashCode()}" }
                ) { _, result ->
                    ResultCard(result = result, viewModel = viewModel)
                }
            }

            // bulk actions for multi-file seasons
            if (results.size > 1) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)
                ) {
                    TextButton(onClick = {
                        val snapshot = results
                        scope.launch {
                            val n = withContext(Dispatchers.IO) {
                                snapshot.count { viewModel.saveResultToDownloads(it) }
                            }
                            savedAll = context.getString(R.string.saved_all, n)
                        }
                    }) {
                        Text(savedAll ?: stringResource(R.string.save_all), color = SubFlowColors.Success)
                    }
                    TextButton(onClick = {
                        val items = results.map { it.fileName to it.content }
                        scope.launch {
                            val uris = withContext(Dispatchers.IO) { FileUtils.writeShareFiles(context, items) }
                            FileUtils.fireShareMultiple(context, uris)
                        }
                    }) {
                        Text(stringResource(R.string.share_all), color = SubFlowColors.Accent)
                    }
                }
                Spacer(Modifier.height(4.dp))
            }
            // follow a series for one-tap next episode
            val followable = viewModel.followableRelease
            if (results.isNotEmpty() && followable != null) {
                val favorites by viewModel.favorites.collectAsState()
                val followed = favorites.any { it.title.equals(followable.title, true) }
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    TextButton(onClick = { viewModel.toggleFollow() }) {
                        Text(
                            if (followed) stringResource(R.string.favorite_added) else "☆ " + stringResource(R.string.favorite_add),
                            color = if (followed) SubFlowColors.Accent else SubFlowColors.TextSecondary
                        )
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            // re-run only the episodes that came up empty
            if (failedCount > 0) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    PressableButton(
                        text = stringResource(R.string.retry_failed, failedCount),
                        onClick = onRetryFailed
                    )
                }
                Spacer(Modifier.height(12.dp))
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally)
            ) {
                PressableButton(text = stringResource(R.string.search_again), onClick = onSearchAgain)
                PressableButton(text = stringResource(R.string.home), onClick = onHome, outline = true)
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

// one card per .srt, expands to save/share/mux actions
@Composable
private fun ResultCard(result: SubtitleResult, viewModel: SearchViewModel) {
    val context = LocalContext.current
    val cardScope = rememberCoroutineScope()
    var expanded by remember { mutableStateOf(false) }
    var saveState by remember { mutableStateOf<String?>(null) }         // SAF save
    var downloadsState by remember { mutableStateOf<String?>(null) }    // Downloads
    var showMuxInfo by remember { mutableStateOf(false) }
    var showPreview by remember { mutableStateOf(false) }
    // manual timing nudge, shifted content is what gets saved
    var offsetMs by remember { mutableStateOf(0L) }
    val effective = remember(offsetMs, result) {
        if (offsetMs == 0L) result else result.copy(content = SyncEngine.shift(result.content, offsetMs))
    }

    val savedText = stringResource(R.string.saved)
    val saveFailedText = stringResource(R.string.save_failed)
    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/x-subrip")
    ) { uri ->
        if (uri != null) {
            // save off the main thread so it can't ANR the UI
            cardScope.launch {
                val ok = withContext(Dispatchers.IO) { viewModel.saveResultTo(uri, effective) }
                saveState = if (ok) savedText else saveFailedText
            }
        }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .background(SubFlowColors.Surface, RoundedCornerShape(12.dp))
            .border(1.dp, SubFlowColors.Border, RoundedCornerShape(12.dp))
            .clickable { expanded = !expanded }
            .animateContentSize(spring())
            .padding(16.dp)
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) {
                Text(result.fileName, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                // source label · episode · size · method
                Text(
                    "${result.sourceName} · ${result.episodeLabel.ifBlank { "—" }} · " +
                        "${result.sizeBytes / 1024}KB · ${result.method}",
                    style = MaterialTheme.typography.bodySmall
                )
                if (result.qualityScore > 0 || result.tonePreserved || result.untranslatedPct > 0) {
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (result.qualityScore > 0) QualityBadge(result.qualityScore)
                        if (result.untranslatedPct > 0) UntranslatedBadge(result.untranslatedPct)
                        if (result.tonePreserved) ToneBadge()
                    }
                }
            }
            Text(if (expanded) "▴" else "▾", color = SubFlowColors.Accent)
        }

        result.syncWarning?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, style = MaterialTheme.typography.labelSmall, color = SubFlowColors.Accent)
        }

        if (expanded) {
            Spacer(Modifier.height(12.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(SubFlowColors.Border)
            )
            Spacer(Modifier.height(12.dp))
            Text(
                result.content.take(600) + if (result.content.length > 600) "\n…" else "",
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.height(12.dp))
            TimingRow(offsetMs = offsetMs, onChange = { offsetMs = it })
            Spacer(Modifier.height(8.dp))
            val downloadedText = stringResource(R.string.saved)
            val downloadFailText = stringResource(R.string.save_failed)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                // one-tap save to Downloads, no picker
                TextButton(onClick = {
                    cardScope.launch {
                        val ok = withContext(Dispatchers.IO) { viewModel.saveResultToDownloads(effective) }
                        downloadsState = if (ok) downloadedText else downloadFailText
                    }
                }) {
                    Text(downloadsState ?: stringResource(R.string.save_downloads), color = SubFlowColors.Success)
                }
                TextButton(onClick = { saveLauncher.launch(effective.fileName) }) {
                    Text(saveState ?: stringResource(R.string.save), color = SubFlowColors.TextSecondary)
                }
                TextButton(onClick = { FileUtils.shareSubtitle(context, effective.fileName, effective.content) }) {
                    Text(stringResource(R.string.share), color = SubFlowColors.Accent)
                }
                if (viewModel.lastHttpUrl != null) {
                    TextButton(onClick = { showPreview = true }) {
                        Text(stringResource(R.string.preview), color = SubFlowColors.Accent)
                    }
                }
            }
            // play the picked video with this subtitle
            val videoUri by viewModel.pickedVideoUri.collectAsState()
            Row {
                videoUri?.let { uri ->
                    TextButton(onClick = {
                        PlayerLauncher.openInPlayer(context, uri, effective.fileName, effective.content)
                    }) {
                        Text(stringResource(R.string.open_in_player), color = SubFlowColors.Accent)
                    }
                }
                TextButton(onClick = { showMuxInfo = !showMuxInfo }) {
                    Text(stringResource(R.string.mux_mkv), color = SubFlowColors.TextSecondary)
                }
            }
            if (showMuxInfo) {
                Text(
                    stringResource(R.string.mux_info),
                    style = MaterialTheme.typography.labelSmall,
                    color = SubFlowColors.TextSecondary
                )
            }
        }
    }

    if (showPreview) {
        viewModel.lastHttpUrl?.let { url ->
            PreviewPlayerDialog(
                httpUrl = url,
                srtContent = effective.content,
                onDismiss = { showPreview = false }
            )
        }
    }
}

// match badge from sync confidence, tag match, source tier
@Composable
private fun QualityBadge(score: Int) {
    val color = when {
        score >= 75 -> SubFlowColors.Success
        score >= 45 -> SubFlowColors.Accent
        else -> SubFlowColors.Error
    }
    Box(
        Modifier
            .background(color.copy(alpha = 0.12f), RoundedCornerShape(50))
            .border(1.dp, color.copy(alpha = 0.5f), RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 3.dp)
    ) {
        Text(
            stringResource(R.string.quality_match, score),
            style = MaterialTheme.typography.labelSmall,
            color = color
        )
    }
}

// shift the subtitle earlier/later before saving
@Composable
private fun TimingRow(offsetMs: Long, onChange: (Long) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(
            stringResource(R.string.timing) + ":  " + "%+d ms".format(offsetMs),
            style = MaterialTheme.typography.labelSmall,
            color = if (offsetMs == 0L) SubFlowColors.TextSecondary else SubFlowColors.Accent,
            modifier = Modifier.weight(1f)
        )
        NudgeButton("-1s") { onChange(offsetMs - 1000) }
        NudgeButton("-0.1") { onChange(offsetMs - 100) }
        NudgeButton("+0.1") { onChange(offsetMs + 100) }
        NudgeButton("+1s") { onChange(offsetMs + 1000) }
        if (offsetMs != 0L) {
            TextButton(onClick = { onChange(0L) }, contentPadding = PaddingValues(horizontal = 6.dp)) {
                Text(stringResource(R.string.offset_reset), color = SubFlowColors.TextSecondary, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun NudgeButton(label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .padding(horizontal = 2.dp)
            .clip(RoundedCornerShape(6.dp))
            .border(1.dp, SubFlowColors.Border, RoundedCornerShape(6.dp))
            .clickable { onClick() }
            .padding(horizontal = 8.dp, vertical = 5.dp)
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = SubFlowColors.TextPrimary)
    }
}

// badge: part of the file never left the source language. always shown in the error
// colour — this is the one thing a user cannot discover without opening the file.
@Composable
private fun UntranslatedBadge(pct: Int) {
    val color = SubFlowColors.Error
    Box(
        Modifier
            .background(color.copy(alpha = 0.12f), RoundedCornerShape(50))
            .border(1.dp, color.copy(alpha = 0.5f), RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 3.dp)
    ) {
        Text(
            stringResource(R.string.untranslated_badge, pct),
            style = MaterialTheme.typography.labelSmall,
            color = color
        )
    }
}

// badge: translation kept slang and tone
@Composable
private fun ToneBadge() {
    Box(
        Modifier
            .background(SubFlowColors.TextPrimary.copy(alpha = 0.06f), RoundedCornerShape(50))
            .border(1.dp, SubFlowColors.Border, RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 3.dp)
    ) {
        Text(
            stringResource(R.string.uncensored_badge),
            style = MaterialTheme.typography.labelSmall,
            color = SubFlowColors.TextPrimary
        )
    }
}

// theme-flavored particle drift: petals for sakura, bubbles for abyss, leaves for verdant, sparks otherwise
@Composable
private fun ParticleOverlay() {
    val particles = remember {
        List(22) { i ->
            Triple(
                (i * 37 % 100) / 100f,          // x position
                0.4f + (i * 13 % 60) / 100f,    // speed
                (i * 53 % 100) / 100f           // phase
            )
        }
    }
    val transition = rememberInfiniteTransition(label = "particles")
    val t by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(9000, easing = LinearEasing), RepeatMode.Restart),
        label = "particleT"
    )
    val accent = SubFlowColors.Accent
    val palette = SubFlowColors.palette.id
    val fallsDown = palette == "sakura" || palette == "verdant"

    Canvas(Modifier.fillMaxSize()) {
        for ((x, speed, phase) in particles) {
            val progress = (t * speed + phase) % 1f
            // rise for most themes, petals and leaves fall
            val y = if (fallsDown) size.height * progress else size.height * (1f - progress)
            val sway = sin(progress * 6.28f * 2 + phase * 10) * (if (fallsDown) 34f else 24f)
            val alpha = ((1f - progress) * progress * 4f * 0.35f).coerceIn(0f, 0.35f)
            val cx = x * size.width + sway
            val color = accent.copy(alpha = alpha)
            when (palette) {
                "sakura", "verdant" -> {
                    // small rotated ovals
                    rotate(degrees = progress * 360f + phase * 180f, pivot = Offset(cx, y)) {
                        drawOval(
                            color = color,
                            topLeft = Offset(cx - 3.5f, y - 2f),
                            size = androidx.compose.ui.geometry.Size(7f, 4f)
                        )
                    }
                }
                "abyss" -> {
                    // thin rings
                    drawCircle(
                        color = color,
                        radius = 2f + phase * 3f,
                        center = Offset(cx, y),
                        style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.2f)
                    )
                }
                else -> drawCircle(color, radius = 1.6f + phase * 2f, center = Offset(cx, y))
            }
        }
    }
}
