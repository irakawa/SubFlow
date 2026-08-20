package com.subflow.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.subflow.BuildConfig
import com.subflow.R
import com.subflow.data.ApiKeys
import com.subflow.data.AppSettings
import com.subflow.data.BackupManager
import com.subflow.data.CrashLog
import com.subflow.data.Stats
import com.subflow.models.LangCatalog
import com.subflow.pipeline.WhisperEngine
import com.subflow.ui.theme.CinzelFamily
import com.subflow.ui.theme.Palettes
import com.subflow.ui.theme.SubFlowColors
import com.subflow.utils.FileUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SettingsScreen(onBack: () -> Unit, onStats: () -> Unit = {}) {
    val context = LocalContext.current
    var showThemes by remember { mutableStateOf(false) }
    var showApiKeys by remember { mutableStateOf(false) }
    var showLanguage by remember { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }
    var targetLang by remember { mutableStateOf(AppSettings.defaultTargetLang) }
    var modelSize by remember { mutableStateOf(0L) }
    var modelJustDeleted by remember { mutableStateOf(false) }
    var autoSave by remember { mutableStateOf(AppSettings.autoSave) }
    var haptics by remember { mutableStateOf(AppSettings.haptics) }
    var showCrash by remember { mutableStateOf(false) }
    var crashText by remember { mutableStateOf<String?>(null) }
    // model size and crash log are disk reads, keep off the main thread
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val size = WhisperEngine.modelSizeBytes(context)
            val crash = CrashLog.read(context)
            withContext(Dispatchers.Main) {
                modelSize = size
                crashText = crash
            }
        }
    }
    var backupState by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    val backupDoneText = stringResource(R.string.backup_done)
    val restoreDoneText = stringResource(R.string.restore_done)
    val restoreFailText = stringResource(R.string.restore_fail)
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) scope.launch {
            backupState = if (BackupManager.export(context, uri)) backupDoneText else restoreFailText
        }
    }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) scope.launch {
            if (BackupManager.import(context, uri)) {
                // import applied the restored values to storage. refresh the on-screen
                // toggles/fields too so the user sees them change instead of stale values.
                autoSave = AppSettings.autoSave
                haptics = AppSettings.haptics
                targetLang = AppSettings.defaultTargetLang
                backupState = restoreDoneText
            } else {
                backupState = restoreFailText
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(SubFlowColors.Background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 12.dp)) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back), tint = SubFlowColors.TextSecondary)
            }
            Text(stringResource(R.string.settings), style = MaterialTheme.typography.headlineMedium)
        }
        Spacer(Modifier.height(8.dp))

        SettingRow(
            title = stringResource(R.string.stats),
            value = "${Stats.found} · ${Stats.searches}",
            onClick = onStats
        )
        SettingRow(
            title = stringResource(R.string.theme),
            value = SubFlowColors.palette.displayName,
            onClick = { showThemes = true }
        )
        SettingRow(
            title = stringResource(R.string.default_language),
            value = LangCatalog.nativeName(targetLang),
            onClick = { showLanguage = true }
        )
        SettingRow(
            title = stringResource(R.string.auto_save),
            value = stringResource(if (autoSave) R.string.auto_save_on else R.string.auto_save_off),
            onClick = {
                autoSave = !autoSave
                AppSettings.autoSave = autoSave
            },
            trailing = {
                Switch(
                    checked = autoSave,
                    onCheckedChange = { on -> autoSave = on; AppSettings.autoSave = on },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = SubFlowColors.Background,
                        checkedTrackColor = SubFlowColors.Accent,
                        uncheckedThumbColor = SubFlowColors.TextSecondary,
                        uncheckedTrackColor = SubFlowColors.SurfaceAlt,
                        uncheckedBorderColor = SubFlowColors.Border
                    )
                )
            }
        )
        SettingRow(
            title = stringResource(R.string.haptics),
            value = stringResource(if (haptics) R.string.auto_save_on else R.string.auto_save_off),
            onClick = { haptics = !haptics; AppSettings.haptics = haptics },
            trailing = {
                Switch(
                    checked = haptics,
                    onCheckedChange = { on -> haptics = on; AppSettings.haptics = on },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = SubFlowColors.Background,
                        checkedTrackColor = SubFlowColors.Accent,
                        uncheckedThumbColor = SubFlowColors.TextSecondary,
                        uncheckedTrackColor = SubFlowColors.SurfaceAlt,
                        uncheckedBorderColor = SubFlowColors.Border
                    )
                )
            }
        )
        SettingRow(
            title = stringResource(R.string.api_keys),
            value = apiKeySummary(),
            onClick = { showApiKeys = true }
        )
        SettingRow(
            title = stringResource(R.string.transcription_model),
            value = if (modelSize > 0)
                stringResource(R.string.model_status_downloaded, formatSize(modelSize))
            else stringResource(R.string.model_status_ondemand),
            onClick = null,
            trailing = {
                if (modelSize > 0) {
                    TextButton(onClick = {
                        if (WhisperEngine.deleteModel(context)) {
                            modelSize = 0
                            modelJustDeleted = true
                        }
                    }) {
                        Text(stringResource(R.string.model_delete), color = SubFlowColors.Error, style = MaterialTheme.typography.labelSmall)
                    }
                } else if (modelJustDeleted) {
                    Text(stringResource(R.string.model_deleted), color = SubFlowColors.Success, style = MaterialTheme.typography.labelSmall)
                }
            }
        )
        // the caption spells out what leaves the device. a backup file is easy to share
        // by accident, so what is and isn't in it should not be a guess.
        SettingRow(
            title = stringResource(R.string.backup),
            value = backupState ?: stringResource(R.string.backup_contents),
            onClick = { exportLauncher.launch("subflow_backup.json") }
        )
        SettingRow(
            title = stringResource(R.string.restore),
            value = stringResource(R.string.restore_contents),
            onClick = { importLauncher.launch(arrayOf("application/json", "text/*", "*/*")) }
        )
        SettingRow(
            title = stringResource(R.string.crash_log),
            value = if (crashText != null) "⚠" else stringResource(R.string.crash_log_none),
            onClick = if (crashText != null) ({ showCrash = true }) else null
        )
        SettingRow(
            title = stringResource(R.string.about),
            value = "v${BuildConfig.VERSION_NAME}",
            onClick = { showAbout = true }
        )
        Spacer(Modifier.height(24.dp))
    }

    if (showThemes) ThemePickerDialog { showThemes = false }
    if (showApiKeys) ApiKeysDialog { showApiKeys = false }
    if (showAbout) AboutDialog { showAbout = false }
    if (showCrash) {
        crashText?.let { text ->
            CrashLogDialog(
                text = text,
                onShare = { FileUtils.shareSubtitle(context, "subflow_crash.txt", text) },
                onClear = {
                    CrashLog.clear(context)
                    crashText = null
                    showCrash = false
                },
                onDismiss = { showCrash = false }
            )
        }
    }
    if (showLanguage) {
        LanguagePickerDialog(
            current = targetLang,
            onPick = {
                targetLang = it
                AppSettings.defaultTargetLang = it
                showLanguage = false
            },
            onDismiss = { showLanguage = false }
        )
    }
}

@Composable
private fun apiKeySummary(): String {
    val n = listOf(ApiKeys.subdl, ApiKeys.openSubtitles, ApiKeys.subsource).count { it.isNotBlank() }
    return "$n / 3"
}

private fun formatSize(bytes: Long): String = "%d MB".format(bytes / (1024 * 1024))

@Composable
private fun SettingRow(
    title: String,
    value: String,
    onClick: (() -> Unit)?,
    trailing: @Composable (() -> Unit)? = null
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(SubFlowColors.Surface)
            .border(1.dp, SubFlowColors.Border, RoundedCornerShape(12.dp))
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(horizontal = 16.dp, vertical = 16.dp)
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(2.dp))
            Text(value, style = MaterialTheme.typography.labelSmall, color = SubFlowColors.TextSecondary)
        }
        if (trailing != null) trailing() else if (onClick != null) Text("›", color = SubFlowColors.Accent, fontSize = 20.sp)
    }
}

@Composable
private fun LanguagePickerDialog(current: String, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .background(SubFlowColors.Surface, RoundedCornerShape(12.dp))
                .border(1.dp, SubFlowColors.Border, RoundedCornerShape(12.dp))
                .padding(20.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text(stringResource(R.string.default_language), style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(12.dp))
            LangCatalog.supported.forEach { lang ->
                val active = lang.code == current
                Text(
                    lang.nativeName,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (active) SubFlowColors.Accent else SubFlowColors.TextPrimary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onPick(lang.code) }
                        .padding(vertical = 12.dp)
                )
            }
        }
    }
}

// free keys the user pastes to unlock the top sources
@Composable
private fun ApiKeysDialog(onDismiss: () -> Unit) {
    var subdl by remember { mutableStateOf(ApiKeys.subdl) }
    var openSubs by remember { mutableStateOf(ApiKeys.openSubtitles) }
    var subsource by remember { mutableStateOf(ApiKeys.subsource) }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .background(SubFlowColors.Surface, RoundedCornerShape(12.dp))
                .border(1.dp, SubFlowColors.Border, RoundedCornerShape(12.dp))
                .verticalScroll(rememberScrollState())
                .padding(20.dp)
        ) {
            Text(stringResource(R.string.api_keys), style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(10.dp))

            Box(
                Modifier
                    .fillMaxWidth()
                    .background(SubFlowColors.Accent.copy(alpha = 0.08f), RoundedCornerShape(10.dp))
                    .border(1.dp, SubFlowColors.Accent.copy(alpha = 0.35f), RoundedCornerShape(10.dp))
                    .padding(14.dp)
            ) {
                Text(
                    stringResource(R.string.api_keys_purpose),
                    style = MaterialTheme.typography.bodyMedium,
                    color = SubFlowColors.TextPrimary
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.api_keys_info),
                style = MaterialTheme.typography.labelSmall,
                color = SubFlowColors.TextSecondary
            )
            Spacer(Modifier.height(18.dp))

            KeyField("OpenSubtitles", "https://www.opensubtitles.com/consumers", stringResource(R.string.api_hint_os), openSubs) { openSubs = it }
            KeyField("SubDL", "https://subdl.com/panel/api", stringResource(R.string.api_hint_subdl), subdl) { subdl = it }
            KeyField("SubSource", "https://subsource.net/api-docs", stringResource(R.string.api_hint_subsource), subsource) { subsource = it }

            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.close), color = SubFlowColors.TextSecondary)
                }
                TextButton(onClick = {
                    ApiKeys.subdl = subdl
                    ApiKeys.openSubtitles = openSubs
                    ApiKeys.subsource = subsource
                    onDismiss()
                }) {
                    Text(stringResource(R.string.save), color = SubFlowColors.Accent)
                }
            }
        }
    }
}

@Composable
private fun KeyField(label: String, url: String, hint: String, value: String, onChange: (String) -> Unit) {
    val uriHandler = LocalUriHandler.current
    Column(Modifier.fillMaxWidth().padding(bottom = 14.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.clickable { runCatching { uriHandler.openUri(url) } }
        ) {
            Text(label, style = MaterialTheme.typography.titleMedium, color = SubFlowColors.Accent)
            Spacer(Modifier.width(6.dp))
            Text("↗", color = SubFlowColors.Accent, fontSize = 14.sp)
        }
        Text(hint, style = MaterialTheme.typography.labelSmall, color = SubFlowColors.TextSecondary)
        Spacer(Modifier.height(6.dp))
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodySmall.copy(color = SubFlowColors.TextPrimary),
            cursorBrush = SolidColor(SubFlowColors.Accent),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(6.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(SubFlowColors.Border))
    }
}

@Composable
private fun ThemePickerDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .background(SubFlowColors.Surface, RoundedCornerShape(12.dp))
                .border(1.dp, SubFlowColors.Border, RoundedCornerShape(12.dp))
                .verticalScroll(rememberScrollState())
                .padding(20.dp)
        ) {
            Text(stringResource(R.string.theme), style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(16.dp))
            Palettes.all.forEach { palette ->
                val active = palette.id == SubFlowColors.palette.id
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .background(
                            if (active) palette.accent.copy(alpha = 0.10f) else Color.Transparent,
                            RoundedCornerShape(10.dp)
                        )
                        .border(
                            1.dp,
                            if (active) palette.accent.copy(alpha = 0.6f) else SubFlowColors.Border,
                            RoundedCornerShape(10.dp)
                        )
                        .clickable {
                            SubFlowColors.apply(context, palette)
                            onDismiss()
                        }
                        .padding(horizontal = 14.dp, vertical = 12.dp)
                ) {
                    Box(
                        Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(palette.background)
                            .border(1.dp, palette.border, CircleShape)
                    ) {
                        Box(
                            Modifier.size(14.dp).align(Alignment.Center).clip(CircleShape).background(palette.accent)
                        )
                        Box(
                            Modifier.align(Alignment.BottomEnd).padding(end = 4.dp, bottom = 4.dp)
                                .size(7.dp).clip(CircleShape).background(palette.success)
                        )
                    }
                    Spacer(Modifier.width(14.dp))
                    Text(
                        palette.displayName,
                        fontFamily = CinzelFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        letterSpacing = 2.sp,
                        color = if (active) palette.accent else SubFlowColors.TextPrimary,
                        modifier = Modifier.weight(1f)
                    )
                    if (active) Text("✓", color = palette.accent, fontSize = 16.sp)
                }
            }
        }
    }
}


@Composable
private fun CrashLogDialog(text: String, onShare: () -> Unit, onClear: () -> Unit, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .background(SubFlowColors.Surface, RoundedCornerShape(12.dp))
                .border(1.dp, SubFlowColors.Border, RoundedCornerShape(12.dp))
                .padding(20.dp)
        ) {
            Text(stringResource(R.string.crash_log), style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(12.dp))
            Text(
                text.take(3000),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 300.dp)
                    .verticalScroll(rememberScrollState())
            )
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onClear) {
                    Text(stringResource(R.string.crash_log_clear), color = SubFlowColors.Error)
                }
                TextButton(onClick = onShare) {
                    Text(stringResource(R.string.crash_log_share), color = SubFlowColors.Accent)
                }
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.close), color = SubFlowColors.TextSecondary)
                }
            }
        }
    }
}

@Composable
private fun AboutDialog(onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .background(SubFlowColors.Surface, RoundedCornerShape(12.dp))
                .border(1.dp, SubFlowColors.Border, RoundedCornerShape(12.dp))
                .padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("SUBFLOW", style = MaterialTheme.typography.displayMedium)
            Spacer(Modifier.height(20.dp))
            Text("Original Concept by Irakawa", style = MaterialTheme.typography.bodyMedium, color = SubFlowColors.TextSecondary)
            Spacer(Modifier.height(6.dp))
            Text("Developed & Published by Null", style = MaterialTheme.typography.bodyMedium, color = SubFlowColors.TextSecondary)
            Spacer(Modifier.height(20.dp))
            Text("Version ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.labelSmall)
            Spacer(Modifier.height(16.dp))
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.close), color = SubFlowColors.Accent)
            }
        }
    }
}
