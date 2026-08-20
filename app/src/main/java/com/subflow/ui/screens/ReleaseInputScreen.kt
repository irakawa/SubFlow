package com.subflow.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.subflow.R
import com.subflow.models.ContentType
import com.subflow.models.LangCatalog
import com.subflow.models.TorrentFile
import com.subflow.optimization.DeviceProfiler
import com.subflow.ui.InputForm
import com.subflow.ui.SearchViewModel
import com.subflow.ui.theme.SubFlowColors
import kotlinx.coroutines.delay

private val formatOptions = listOf("BD Remux", "WEB-DL", "BluRay Encode")
private val codecOptions = listOf("x264", "x265")
private val audioOptions = listOf("FLAC", "DTS-HD MA", "DDP5.1", "TrueHD Atmos")

// rough check that clipboard text looks like a release name
private val releaseHintRegex = Regex(
    """S\d{1,2}[. ]?E\d{1,3}|\d{3,4}p|x26[45]|HEVC|WEB-?DL|BluRay|Remux|\.mkv|\.mp4|^\[[^\]]+]""",
    RegexOption.IGNORE_CASE
)

@Composable
fun ReleaseInputScreen(viewModel: SearchViewModel, onStart: () -> Unit, onBack: () -> Unit) {
    val form by viewModel.form.collectAsState()
    val ocrBusy by viewModel.ocrBusy.collectAsState()
    val episodePicker by viewModel.episodePicker.collectAsState()
    val suggestions by viewModel.suggestions.collectAsState()
    // a run already in flight would refuse this one; say so instead of pretending
    val searchRunning by viewModel.pipelineStatus.collectAsState()
    val busySearching = searchRunning == com.subflow.models.PipelineStatus.RUNNING
    val clipboard = LocalClipboardManager.current

    // stagger the field reveal after a parse
    // animatedStamp keeps it from replaying when you return to the screen
    val fieldCount = 7
    var showHttpGuide by remember { mutableStateOf(false) }
    var visibleFields by remember { mutableStateOf(fieldCount) }
    var animatedStamp by rememberSaveable { mutableStateOf(0L) }
    LaunchedEffect(form.autoFillStamp) {
        if (form.autoFillStamp > 0 && form.autoFillStamp != animatedStamp) {
            animatedStamp = form.autoFillStamp
            visibleFields = 0
            repeat(fieldCount) {
                delay(DeviceProfiler.animMs(80).toLong())
                visibleFields++
            }
        }
    }

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { viewModel.applyScreenshot(it) }
    }
    val torrentPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { viewModel.applyTorrent(it) }
    }
    val videoPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { viewModel.applyVideoFile(it) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(SubFlowColors.Background)
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .navigationBarsPadding()
            .padding(horizontal = 24.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 12.dp)) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back), tint = SubFlowColors.TextSecondary)
            }
            Text(stringResource(R.string.new_search), style = MaterialTheme.typography.headlineMedium)
        }

        // quick input methods
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            QuickInputChip(
                label = if (ocrBusy) stringResource(R.string.chip_scanning) else stringResource(R.string.chip_screenshot),
                modifier = Modifier.weight(1f),
                busy = ocrBusy
            ) {
                // only parse the clipboard if it looks like a release name, else open the gallery
                val clipText = clipboard.getText()?.text
                val looksLikeRelease = clipText != null && clipText.length in 9..300 &&
                    releaseHintRegex.containsMatchIn(clipText)
                if (looksLikeRelease) {
                    viewModel.applyParsedText(clipText!!, fromOcr = true)
                } else {
                    imagePicker.launch("image/*")
                }
            }
            QuickInputChip(label = stringResource(R.string.chip_torrent), modifier = Modifier.weight(1f)) {
                torrentPicker.launch("*/*")
            }
        }
        Spacer(Modifier.height(12.dp))
        // file-first, no need to know the release name
        QuickInputChip(label = stringResource(R.string.chip_video), modifier = Modifier.fillMaxWidth()) {
            videoPicker.launch("video/*")
        }

        Spacer(Modifier.height(24.dp))

        StaggeredField(index = 0, visibleCount = visibleFields) {
            FlatTextField(
                label = stringResource(R.string.field_title),
                value = form.title,
                onValueChange = { v -> viewModel.onTitleChanged(v) }
            )
        }
        // autocomplete: tap a suggestion to fill the full title
        if (suggestions.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(SubFlowColors.Surface)
                    .border(1.dp, SubFlowColors.Border, RoundedCornerShape(12.dp))
            ) {
                suggestions.forEach { s ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { viewModel.applySuggestion(s) }
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            s.title,
                            style = MaterialTheme.typography.bodyMedium,
                            color = SubFlowColors.TextPrimary,
                            modifier = Modifier.weight(1f)
                        )
                        s.year?.let {
                            Text(
                                it.toString(),
                                style = MaterialTheme.typography.labelSmall,
                                color = SubFlowColors.TextSecondary
                            )
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(
                            stringResource(s.type.labelRes),
                            style = MaterialTheme.typography.labelSmall,
                            color = SubFlowColors.Accent
                        )
                    }
                }
            }
        }
        StaggeredField(index = 1, visibleCount = visibleFields) {
            Column {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Box(Modifier.weight(1f)) {
                        FlatTextField(
                            label = stringResource(R.string.field_season),
                            value = form.season,
                            onValueChange = { v -> viewModel.updateForm { it.copy(season = v.filter { c -> c.isDigit() }) } }
                        )
                    }
                    Box(Modifier.weight(1f)) {
                        FlatTextField(
                            label = stringResource(R.string.field_episode),
                            value = form.episode,
                            onValueChange = { v -> viewModel.updateForm { it.copy(episode = v.filter { c -> c.isDigit() }) } }
                        )
                    }
                    if (form.seasonMode) {
                        Box(Modifier.weight(1f)) {
                            FlatTextField(
                                label = stringResource(R.string.field_episode_end),
                                value = form.episodeEnd,
                                onValueChange = { v -> viewModel.updateForm { it.copy(episodeEnd = v.filter { c -> c.isDigit() }) } }
                            )
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                // season mode runs the whole episode range in one search
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable {
                        viewModel.updateForm { it.copy(seasonMode = !it.seasonMode) }
                    }
                ) {
                    Switch(
                        checked = form.seasonMode,
                        onCheckedChange = { on -> viewModel.updateForm { it.copy(seasonMode = on) } },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = SubFlowColors.Background,
                            checkedTrackColor = SubFlowColors.Accent,
                            uncheckedThumbColor = SubFlowColors.TextSecondary,
                            uncheckedTrackColor = SubFlowColors.SurfaceAlt,
                            uncheckedBorderColor = SubFlowColors.Border
                        )
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        stringResource(R.string.season_mode),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (form.seasonMode) SubFlowColors.Accent else SubFlowColors.TextSecondary
                    )
                }
            }
        }
        StaggeredField(index = 2, visibleCount = visibleFields) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Box(Modifier.weight(1f)) {
                    val typeLabels = ContentType.entries.map { it to stringResource(it.labelRes) }
                    DropdownField(
                        label = stringResource(R.string.field_type),
                        value = stringResource(form.type.labelRes),
                        options = typeLabels.map { it.second }
                    ) { selected ->
                        val picked = typeLabels.first { it.second == selected }.first
                        viewModel.updateForm { f -> f.copy(type = picked) }
                    }
                }
                Box(Modifier.weight(1f)) {
                    DropdownField(
                        label = stringResource(R.string.field_target_lang),
                        value = LangCatalog.nativeName(form.targetLang),
                        options = LangCatalog.supported.map { it.nativeName }
                    ) { selected ->
                        val lang = LangCatalog.supported.first { it.nativeName == selected }
                        viewModel.updateForm { it.copy(targetLang = lang.code) }
                    }
                }
            }
        }
        StaggeredField(index = 3, visibleCount = visibleFields) {
            DropdownField(label = stringResource(R.string.field_format), value = form.format, options = formatOptions) { v ->
                viewModel.updateForm { it.copy(format = v) }
            }
        }
        StaggeredField(index = 4, visibleCount = visibleFields) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Box(Modifier.weight(1f)) {
                    DropdownField(label = stringResource(R.string.field_codec), value = form.codec, options = codecOptions) { v ->
                        viewModel.updateForm { it.copy(codec = v) }
                    }
                }
                Box(Modifier.weight(1f)) {
                    DropdownField(label = stringResource(R.string.field_audio), value = form.audio, options = audioOptions) { v ->
                        viewModel.updateForm { it.copy(audio = v) }
                    }
                }
            }
        }
        StaggeredField(index = 5, visibleCount = visibleFields) {
            FlatTextField(
                label = stringResource(R.string.field_tags),
                value = form.extraTags,
                onValueChange = { v -> viewModel.updateForm { it.copy(extraTags = v) } }
            )
        }
        StaggeredField(index = 6, visibleCount = visibleFields) {
            Column {
                // label plus a tappable "how to use"
                Row(
                    Modifier.fillMaxWidth().padding(bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        stringResource(R.string.field_http),
                        style = MaterialTheme.typography.labelSmall,
                        color = SubFlowColors.TextSecondary,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        stringResource(R.string.http_guide_link),
                        style = MaterialTheme.typography.labelSmall,
                        color = SubFlowColors.Accent,
                        modifier = Modifier.clickable { showHttpGuide = true }
                    )
                }
                FlatTextField(
                    label = "",
                    value = form.httpUrl,
                    onValueChange = { v -> viewModel.updateForm { it.copy(httpUrl = v) } }
                )
            }
        }

        form.fileName?.let {
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.file_label, it), style = MaterialTheme.typography.bodySmall, color = SubFlowColors.Accent)
        }

        Spacer(Modifier.height(32.dp))

        // empty title on Start shows an error instead of doing nothing
        var showTitleError by remember { mutableStateOf(false) }
        if (showTitleError) {
            Text(
                stringResource(R.string.title_required),
                style = MaterialTheme.typography.labelSmall,
                color = SubFlowColors.Error,
                modifier = Modifier.fillMaxWidth(),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            Spacer(Modifier.height(8.dp))
        }
        if (busySearching) {
            Text(
                stringResource(R.string.search_already_running),
                style = MaterialTheme.typography.labelSmall,
                color = SubFlowColors.Accent,
                modifier = Modifier.fillMaxWidth(),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            Spacer(Modifier.height(8.dp))
        }
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            PressableButton(
                text = stringResource(R.string.start),
                enabled = !busySearching,
                onClick = {
                    if (form.title.isNotBlank()) onStart() else showTitleError = true
                }
            )
        }
        Spacer(Modifier.height(32.dp))
    }

    // multi-file torrent, pick an episode
    if (episodePicker.isNotEmpty()) {
        EpisodePickerDialog(
            files = episodePicker,
            onSelect = { viewModel.selectTorrentFile(it) },
            onDismiss = { viewModel.dismissEpisodePicker() }
        )
    }

    if (showHttpGuide) HttpGuideDialog { showHttpGuide = false }
}

@Composable
private fun HttpGuideDialog(onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .background(SubFlowColors.Surface, RoundedCornerShape(12.dp))
                .border(1.dp, SubFlowColors.Border, RoundedCornerShape(12.dp))
                .padding(22.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text(stringResource(R.string.http_guide_title), style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(14.dp))
            Text(
                stringResource(R.string.http_guide_body),
                style = MaterialTheme.typography.bodyMedium,
                color = SubFlowColors.TextSecondary
            )
            Spacer(Modifier.height(18.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.close), color = SubFlowColors.Accent)
                }
            }
        }
    }
}

@Composable
private fun StaggeredField(index: Int, visibleCount: Int, content: @Composable () -> Unit) {
    Column {
        AnimatedVisibility(
            visible = index < visibleCount,
            enter = fadeIn(tween(DeviceProfiler.animMs(200))) +
                slideInVertically(tween(DeviceProfiler.animMs(200))) { it / 3 }
        ) {
            content()
        }
        Spacer(Modifier.height(20.dp))
    }
}

// flat input, bottom border animates to the accent color on focus
@Composable
private fun FlatTextField(label: String, value: String, onValueChange: (String) -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val borderColor by animateColorAsState(
        targetValue = if (focused) SubFlowColors.Accent else SubFlowColors.Border,
        animationSpec = tween(DeviceProfiler.animMs(200)),
        label = "inputBorder"
    )

    Column(Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = SubFlowColors.TextSecondary)
        Spacer(Modifier.height(6.dp))
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            interactionSource = interaction,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = SubFlowColors.TextPrimary),
            cursorBrush = SolidColor(SubFlowColors.Accent),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(borderColor)
        )
    }
}

@Composable
private fun DropdownField(label: String, value: String, options: List<String>, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = SubFlowColors.TextSecondary)
        Spacer(Modifier.height(6.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .clickable { expanded = true }
        ) {
            Row(
                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(value, style = MaterialTheme.typography.bodyLarge)
                Text("▾", color = SubFlowColors.Accent)
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier.background(SubFlowColors.SurfaceAlt)
            ) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option, style = MaterialTheme.typography.bodyMedium) },
                        onClick = {
                            expanded = false
                            onSelect(option)
                        }
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(SubFlowColors.Border)
        )
    }
}

@Composable
private fun QuickInputChip(label: String, modifier: Modifier = Modifier, busy: Boolean = false, onClick: () -> Unit) {
    Row(
        modifier = modifier
            .background(SubFlowColors.Surface, RoundedCornerShape(12.dp))
            .border(1.dp, SubFlowColors.Border, RoundedCornerShape(12.dp))
            .clickable(enabled = !busy) { onClick() }
            .padding(vertical = 14.dp, horizontal = 12.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (busy) {
            CircularProgressIndicator(
                modifier = Modifier.width(16.dp).height(16.dp),
                color = SubFlowColors.Accent,
                strokeWidth = 2.dp
            )
            Spacer(Modifier.width(8.dp))
        }
        Text(label, style = MaterialTheme.typography.labelSmall, color = SubFlowColors.TextPrimary)
    }
}

@Composable
private fun EpisodePickerDialog(files: List<TorrentFile>, onSelect: (TorrentFile) -> Unit, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .background(SubFlowColors.Surface, RoundedCornerShape(12.dp))
                .border(1.dp, SubFlowColors.Border, RoundedCornerShape(12.dp))
                .padding(20.dp)
        ) {
            Text(stringResource(R.string.pick_episode), style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(12.dp))
            Column(Modifier.verticalScroll(rememberScrollState()).weight(1f, fill = false)) {
                files.forEach { file ->
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(file) }
                            .padding(vertical = 10.dp)
                    ) {
                        Text(
                            file.path.substringAfterLast('/'),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            "%.1f MB".format(file.size / (1024f * 1024f)),
                            style = MaterialTheme.typography.labelSmall,
                            color = SubFlowColors.TextSecondary
                        )
                    }
                }
            }
            TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                Text(stringResource(R.string.cancel), color = SubFlowColors.TextSecondary)
            }
        }
    }
}
