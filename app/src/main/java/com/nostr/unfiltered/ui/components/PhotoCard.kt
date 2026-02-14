package com.nostr.unfiltered.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.nostr.unfiltered.nostr.models.MediaItem
import com.nostr.unfiltered.nostr.models.PhotoPost

@Composable
fun PhotoCard(
    post: PhotoPost,
    onProfileClick: () -> Unit,
    onLikeClick: () -> Unit,
    onZapClick: () -> Unit = {},
    onImageClick: () -> Unit = {},
    onMediaClick: (index: Int, url: String) -> Unit = { _, _ -> onImageClick() },
    showZapButton: Boolean = false,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Column {
            // Header with user info
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onProfileClick() }
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                UserAvatar(
                    imageUrl = post.authorAvatar,
                    size = 40.dp
                )

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = post.authorName ?: post.authorNpub.take(16) + "...",
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )

                        if (post.authorNip05 != null) {
                            Icon(
                                imageVector = Icons.Default.Verified,
                                contentDescription = "Verified",
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }

                    post.authorNip05?.let { nip05 ->
                        Text(
                            text = nip05,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.secondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Text(
                    text = post.relativeTime,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary
                )
            }

            // Media content - single or multiple
            if (post.hasMultipleMedia) {
                // Grid layout for multiple images
                MediaGrid(
                    mediaItems = post.mediaItems,
                    onMediaClick = onMediaClick
                )
            } else {
                // Single image or video
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(if (!post.isVideo) Modifier.clickable { onMediaClick(0, post.imageUrl) } else Modifier)
                ) {
                    if (post.isVideo) {
                        VideoPlayer(
                            videoUrl = post.imageUrl,
                            aspectRatio = post.dimensions?.aspectRatio ?: (16f / 9f),
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        AsyncImage(
                            model = post.imageUrl,
                            contentDescription = post.altText ?: post.caption,
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(post.dimensions?.aspectRatio ?: 1f),
                            contentScale = ContentScale.Crop
                        )
                    }
                }
            }

            // Actions row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onLikeClick) {
                    Icon(
                        imageVector = if (post.isLiked) Icons.Filled.Favorite
                        else Icons.Outlined.FavoriteBorder,
                        contentDescription = if (post.isLiked) "Unlike" else "Like",
                        tint = if (post.isLiked) Color.Red
                        else MaterialTheme.colorScheme.onSurface
                    )
                }

                if (post.likeCount > 0) {
                    Text(
                        text = "${post.likeCount}",
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                Spacer(modifier = Modifier.weight(1f))

                // Zap button with amount
                if (showZapButton) {
                    IconButton(onClick = onZapClick) {
                        Icon(
                            imageVector = if (post.isZapped) Icons.Filled.Bolt
                            else Icons.Outlined.Bolt,
                            contentDescription = if (post.isZapped) "Zapped" else "Zap",
                            tint = if (post.isZapped) Color(0xFFFFD700)
                            else MaterialTheme.colorScheme.onSurface
                        )
                    }

                    if (post.zapAmount > 0) {
                        Text(
                            text = formatZapAmount(post.zapAmount),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }

            // Caption
            if (post.caption.isNotEmpty()) {
                Text(
                    text = post.caption,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

/**
 * Grid layout for displaying multiple media items
 */
@Composable
private fun MediaGrid(
    mediaItems: List<MediaItem>,
    onMediaClick: (index: Int, url: String) -> Unit
) {
    val itemCount = mediaItems.size

    when {
        itemCount == 2 -> {
            // Two images side by side
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                mediaItems.forEachIndexed { index, item ->
                    MediaGridItem(
                        item = item,
                        modifier = Modifier
                            .weight(1f)
                            .aspectRatio(1f),
                        onClick = { onMediaClick(index, item.url) }
                    )
                }
            }
        }
        itemCount == 3 -> {
            // First image large on left, two smaller on right
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f),
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                MediaGridItem(
                    item = mediaItems[0],
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxSize(),
                    onClick = { onMediaClick(0, mediaItems[0].url) }
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    MediaGridItem(
                        item = mediaItems[1],
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        onClick = { onMediaClick(1, mediaItems[1].url) }
                    )
                    MediaGridItem(
                        item = mediaItems[2],
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        onClick = { onMediaClick(2, mediaItems[2].url) }
                    )
                }
            }
        }
        itemCount >= 4 -> {
            // 2x2 grid (show first 4, with count overlay if more)
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    MediaGridItem(
                        item = mediaItems[0],
                        modifier = Modifier
                            .weight(1f)
                            .aspectRatio(1f),
                        onClick = { onMediaClick(0, mediaItems[0].url) }
                    )
                    MediaGridItem(
                        item = mediaItems[1],
                        modifier = Modifier
                            .weight(1f)
                            .aspectRatio(1f),
                        onClick = { onMediaClick(1, mediaItems[1].url) }
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    MediaGridItem(
                        item = mediaItems[2],
                        modifier = Modifier
                            .weight(1f)
                            .aspectRatio(1f),
                        onClick = { onMediaClick(2, mediaItems[2].url) }
                    )
                    Box(modifier = Modifier.weight(1f)) {
                        MediaGridItem(
                            item = mediaItems[3],
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(1f),
                            onClick = { onMediaClick(3, mediaItems[3].url) }
                        )
                        // Show count overlay if more than 4 images
                        if (itemCount > 4) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .aspectRatio(1f)
                                    .background(Color.Black.copy(alpha = 0.5f))
                                    .clickable { onMediaClick(3, mediaItems[3].url) },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "+${itemCount - 4}",
                                    style = MaterialTheme.typography.headlineMedium,
                                    color = Color.White
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Single item in the media grid
 */
@Composable
private fun MediaGridItem(
    item: MediaItem,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier.clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        if (item.isVideo) {
            // Video thumbnail with play icon
            AsyncImage(
                model = item.url,
                contentDescription = item.altText ?: "Video",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
            // Play icon overlay
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.6f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = "Video",
                    modifier = Modifier.size(24.dp),
                    tint = Color.White
                )
            }
        } else {
            AsyncImage(
                model = item.url,
                contentDescription = item.altText ?: "Image",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        }
    }
}

private fun formatZapAmount(sats: Long): String {
    return when {
        sats >= 1_000_000 -> {
            val millions = sats / 1_000_000
            val hundredThousands = (sats % 1_000_000) / 100_000
            if (hundredThousands > 0) "${millions}.${hundredThousands}m" else "${millions}m"
        }
        sats >= 1_000 -> {
            val thousands = sats / 1_000
            val hundreds = (sats % 1_000) / 100
            if (hundreds > 0) "${thousands}.${hundreds}k" else "${thousands}k"
        }
        else -> "$sats"
    }
}
