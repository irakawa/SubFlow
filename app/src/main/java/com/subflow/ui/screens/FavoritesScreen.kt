package com.subflow.ui.screens

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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.subflow.R
import com.subflow.data.FavoriteEntry
import com.subflow.ui.SearchViewModel
import com.subflow.ui.theme.SubFlowColors

/** followed shows, one tap searches the next episode. */
@Composable
fun FavoritesScreen(viewModel: SearchViewModel, onBack: () -> Unit, onSearchStarted: () -> Unit) {
    val favorites by viewModel.favorites.collectAsState()

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
            Text(stringResource(R.string.favorites), style = MaterialTheme.typography.headlineMedium)
        }
        Spacer(Modifier.height(8.dp))

        if (favorites.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    stringResource(R.string.favorites_empty),
                    style = MaterialTheme.typography.titleMedium,
                    color = SubFlowColors.TextSecondary,
                    textAlign = TextAlign.Center
                )
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(favorites, key = { it.title }) { fav ->
                    FavoriteRow(
                        fav = fav,
                        onNext = { if (viewModel.searchNextEpisode(fav)) onSearchStarted() },
                        onRemove = { viewModel.unfollow(fav) }
                    )
                }
            }
        }
    }
}

@Composable
private fun FavoriteRow(fav: FavoriteEntry, onNext: () -> Unit, onRemove: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(SubFlowColors.Surface, RoundedCornerShape(12.dp))
            .border(1.dp, SubFlowColors.Border, RoundedCornerShape(12.dp))
            .padding(16.dp)
    ) {
        Text(fav.title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            "S%02d · E%02d".format(java.util.Locale.ROOT, fav.season, fav.lastEpisode) + " · " + fav.targetLang.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = SubFlowColors.TextSecondary
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            TextButton(onClick = onNext) {
                Text(
                    "▶ " + stringResource(R.string.favorite_next) +
                        " · E%02d".format(java.util.Locale.ROOT, fav.lastEpisode + 1),
                    color = SubFlowColors.Accent
                )
            }
            TextButton(onClick = onRemove) {
                Text(stringResource(R.string.close), color = SubFlowColors.TextSecondary, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}
