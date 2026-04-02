package com.nostr.unfiltered.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.nostr.unfiltered.nostr.models.PhotoPost

@Composable
fun FullscreenImageDialog(
    posts: List<PhotoPost>,
    initialIndex: Int,
    onDismiss: () -> Unit
) {
    // Flatten all media items across posts so swiping goes through each image
    // within a post before moving to the next post
    data class FlatMedia(val url: String, val altText: String?, val caption: String)

    val flatMedia = remember(posts) {
        posts.flatMap { post ->
            if (post.mediaItems.size > 1) {
                post.mediaItems.map { item ->
                    FlatMedia(item.url, item.altText, post.caption)
                }
            } else {
                listOf(FlatMedia(post.imageUrl, post.altText, post.caption))
            }
        }
    }

    // Calculate the initial page in the flat list based on the post index
    val initialPage = remember(posts, initialIndex) {
        var page = 0
        for (i in 0 until initialIndex.coerceIn(0, posts.size - 1)) {
            page += if (posts[i].mediaItems.size > 1) posts[i].mediaItems.size else 1
        }
        page.coerceIn(0, (flatMedia.size - 1).coerceAtLeast(0))
    }

    val pagerState = rememberPagerState(
        initialPage = initialPage,
        pageCount = { flatMedia.size }
    )

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.95f))
        ) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize()
            ) { page ->
                val media = flatMedia[page]
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    AsyncImage(
                        model = media.url,
                        contentDescription = media.altText ?: media.caption.ifEmpty { "Image ${page + 1}" },
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp),
                        contentScale = ContentScale.Fit
                    )
                }
            }

            // Back button
            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = 8.dp, top = 40.dp)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = Color.White,
                    modifier = Modifier.size(28.dp)
                )
            }

            // Page counter
            if (flatMedia.size > 1) {
                Text(
                    text = "${pagerState.currentPage + 1} / ${flatMedia.size}",
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 48.dp)
                        .background(
                            color = Color.Black.copy(alpha = 0.5f),
                            shape = androidx.compose.foundation.shape.CircleShape
                        )
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
        }
    }
}
