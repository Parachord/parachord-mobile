package com.parachord.android.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

@Composable
fun SwipeableTabLayout(
    tabs: List<String>,
    modifier: Modifier = Modifier,
    counts: List<Int>? = null,
    initialPage: Int = 0,
    /** A page to move to (a link inside one page to another); [onPageRequestHandled] clears it. */
    requestedPage: Int? = null,
    onPageRequestHandled: () -> Unit = {},
    content: @Composable (page: Int) -> Unit,
) {
    val pagerState = rememberPagerState(initialPage = initialPage, pageCount = { tabs.size })
    val scope = rememberCoroutineScope()
    androidx.compose.runtime.LaunchedEffect(requestedPage) {
        if (requestedPage != null) {
            pagerState.animateScrollToPage(requestedPage)
            onPageRequestHandled()
        }
    }

    val tabBarBackground = MaterialTheme.colorScheme.surface
    val activeColor = MaterialTheme.colorScheme.onSurface
    val inactiveColor = MaterialTheme.colorScheme.onSurfaceVariant

    Column(modifier = modifier) {
        ScrollableTabRow(
            selectedTabIndex = pagerState.currentPage,
            modifier = Modifier.fillMaxWidth(),
            containerColor = tabBarBackground,
            edgePadding = 16.dp,
            indicator = { tabPositions ->
                if (pagerState.currentPage < tabPositions.size) {
                    TabRowDefaults.PrimaryIndicator(
                        modifier = Modifier.tabIndicatorOffset(tabPositions[pagerState.currentPage]),
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            },
        ) {
            tabs.forEachIndexed { index, title ->
                val selected = pagerState.currentPage == index
                val count = counts?.getOrNull(index)
                val label = if (count != null && count > 0) {
                    "${title.uppercase()} ($count)"
                } else {
                    title.uppercase()
                }
                Tab(
                    selected = selected,
                    onClick = { scope.launch { pagerState.animateScrollToPage(index) } },
                    modifier = Modifier.height(48.dp),
                    text = {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelLarge.copy(
                                fontWeight = FontWeight.Light,
                                letterSpacing = 0.1.em,
                                fontSize = 11.sp,
                            ),
                            color = if (selected) activeColor else inactiveColor,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                )
            }
        }
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.weight(1f),
        ) { page ->
            content(page)
        }
    }
}
