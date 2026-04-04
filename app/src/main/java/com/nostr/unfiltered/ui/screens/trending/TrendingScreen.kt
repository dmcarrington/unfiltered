package com.nostr.unfiltered.ui.screens.trending

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.nostr.unfiltered.nostr.TrendingPost
import com.nostr.unfiltered.nostr.VelocityTrend
import com.nostr.unfiltered.ui.theme.*

/**
 * ZapBoost Trending Screen
 * Shows posts ranked by zap velocity (sats-per-hour)
 */
@Composable
fun TrendingScreen(
    viewModel: TrendingViewModel = hiltViewModel(),
    onPostClick: (String) -> Unit,
    onZapClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val trendingPosts by viewModel.trendingPosts.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    
    Box(modifier = modifier.fillMaxSize()) {
        if (isLoading && trendingPosts.isEmpty()) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center),
                color = WhitePrimary
            )
        } else if (trendingPosts.isEmpty()) {
            Text(
                text = "No trending posts yet\nBe the first to zap!",
                style = MaterialTheme.typography.bodyLarge,
                color = TextMuted,
                modifier = Modifier.align(Alignment.Center)
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 80.dp) // Space for nav bar
            ) {
                items(trendingPosts, key = { it.postId }) { post ->
                    TrendingPostCard(
                        post = post,
                        onPostClick = { onPostClick(post.postId) },
                        onZapClick = { onZapClick(post.postId) }
                    )
                }
            }
        }
    }
}

@Composable
fun TrendingPostCard(
    post: TrendingPost,
    onPostClick: () -> Unit,
    onZapClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clickable(onClick = onPostClick),
        colors = CardDefaults.cardColors(containerColor = BlackCard),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            // Velocity badge
            VelocityBadge(
                satsPerHour = post.satsPerHour,
                zapsPerHour = post.zapsPerHour,
                trend = post.velocityTrend
            )
            
            Spacer(modifier = Modifier.height(12.dp))
            
            // Post content placeholder (would be actual post in real implementation)
            Text(
                text = "Post ID: ${post.postId.take(16)}...",
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary
            )
            
            Spacer(modifier = Modifier.height(12.dp))
            
            // Zap button
            Button(
                onClick = onZapClick,
                colors = ButtonDefaults.buttonColors(containerColor = ZapGold),
                modifier = Modifier.align(Alignment.End)
            ) {
                Text(
                    text = "⚡ Zap",
                    color = BlackBackground,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
fun VelocityBadge(
    satsPerHour: Long,
    zapsPerHour: Int,
    trend: VelocityTrend,
    modifier: Modifier = Modifier
) {
    val (badgeColor, label) = when {
        satsPerHour >= 10_000 -> VelocityHigh to "🔥 ${satsPerHour / 1000}k sats/hr"
        satsPerHour >= 1_000 -> VelocityMedium to "⚡ ${satsPerHour / 1000}k sats/hr"
        else -> VelocityLow to "💧 ${satsPerHour} sats/hr"
    }
    
    val trendIcon = when (trend) {
        VelocityTrend.RISING -> "📈"
        VelocityTrend.FALLING -> "📉"
        else -> ""
    }
    
    Surface(
        color = badgeColor,
        shape = MaterialTheme.shapes.small,
        modifier = modifier.clip(MaterialTheme.shapes.small)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "$trendIcon $label",
                style = MaterialTheme.typography.labelMedium,
                color = BlackBackground,
                fontWeight = FontWeight.Bold
            )
        }
    }
}
