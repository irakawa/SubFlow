package com.subflow.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.subflow.R
import com.subflow.data.Stats
import com.subflow.ui.theme.SubFlowColors

@Composable
fun StatsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    // snapshot on entry, counters only move during a search
    val found = remember { Stats.found }
    val searches = remember { Stats.searches }
    val hours = remember { Stats.estHoursSaved }
    val topType = remember { Stats.topType() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(SubFlowColors.Background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 12.dp)) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back), tint = SubFlowColors.TextSecondary)
            }
            Text(stringResource(R.string.stats), style = MaterialTheme.typography.headlineMedium)
        }
        Spacer(Modifier.height(12.dp))

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatTile(found.toString(), stringResource(R.string.stat_found), Modifier.weight(1f))
            StatTile(searches.toString(), stringResource(R.string.stat_searches), Modifier.weight(1f))
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatTile(stringResource(R.string.stat_hours, hours), stringResource(R.string.stat_time_saved), Modifier.weight(1f))
            StatTile(
                topType?.let { stringResource(it.labelRes) } ?: stringResource(R.string.stat_none),
                stringResource(R.string.stat_top_type),
                Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun StatTile(value: String, label: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .background(SubFlowColors.Surface, RoundedCornerShape(12.dp))
            .border(1.dp, SubFlowColors.Border, RoundedCornerShape(12.dp))
            .padding(vertical = 22.dp, horizontal = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(value, style = MaterialTheme.typography.displayMedium, color = SubFlowColors.Accent, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = SubFlowColors.TextSecondary, textAlign = TextAlign.Center)
    }
}
