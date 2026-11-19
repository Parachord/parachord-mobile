package com.parachord.android.ui.screens.discover

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import org.koin.androidx.compose.koinViewModel

/** Placeholder until the rewritten screen lands (Task 6 replaces this file). */
@Composable
fun RecommendationsScreen(
    onBack: () -> Unit,
    onNavigateToArtist: (String) -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: RecommendationsViewModel = koinViewModel(),
) {
    val page by viewModel.page.collectAsState()
    Text("Recommendations: ${page.sections.size} sections", modifier = modifier)
}
