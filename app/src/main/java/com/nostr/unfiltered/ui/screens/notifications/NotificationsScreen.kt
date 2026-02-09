package com.nostr.unfiltered.ui.screens.notifications

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.AlternateEmail
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.nostr.unfiltered.nostr.models.Notification
import com.nostr.unfiltered.nostr.models.NotificationType
import com.nostr.unfiltered.viewmodel.NotificationsViewModel

data class PostPreviewData(
    val imageUrl: String?,
    val content: String?,
    val authorName: String?
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationsScreen(
    onBackClick: () -> Unit,
    viewModel: NotificationsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    var selectedPreview by remember { mutableStateOf<PostPreviewData?>(null) }
    var selectedTabIndex by remember { mutableIntStateOf(0) }

    // Mark as read when screen is displayed
    LaunchedEffect(Unit) {
        viewModel.markAsRead()
    }

    // Post preview dialog
    selectedPreview?.let { preview ->
        PostPreviewDialog(
            imageUrl = preview.imageUrl,
            content = preview.content,
            authorName = preview.authorName,
            onDismiss = { selectedPreview = null }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Notifications", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            val tabLabels = listOf(
                "Following (${uiState.followingNotifications.size})",
                "Others (${uiState.otherNotifications.size})"
            )

            TabRow(selectedTabIndex = selectedTabIndex) {
                tabLabels.forEachIndexed { index, label ->
                    Tab(
                        selected = selectedTabIndex == index,
                        onClick = { selectedTabIndex = index },
                        text = { Text(label) }
                    )
                }
            }

            val notifications = when (selectedTabIndex) {
                0 -> uiState.followingNotifications
                else -> uiState.otherNotifications
            }

            if (notifications.isEmpty()) {
                EmptyNotificationsState(
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                NotificationsList(
                    notifications = notifications,
                    viewModel = viewModel,
                    onShowPreview = { selectedPreview = it }
                )
            }
        }
    }
}

@Composable
private fun NotificationsList(
    notifications: List<Notification>,
    viewModel: NotificationsViewModel,
    onShowPreview: (PostPreviewData) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize()
    ) {
        items(
            items = notifications,
            key = { it.id }
        ) { notification ->
            NotificationItem(
                notification = notification,
                onClick = {
                    if (notification.type != NotificationType.ZAP && notification.type != NotificationType.FOLLOW) {
                        val imageUrl = notification.targetPostImageUrl
                            ?: viewModel.getPostImageUrl(notification.targetPostId)
                        val content = notification.targetPostContent

                        if (content != null || imageUrl != null) {
                            onShowPreview(
                                PostPreviewData(
                                    imageUrl = imageUrl,
                                    content = content,
                                    authorName = notification.actorName
                                )
                            )
                        }
                    }
                }
            )
            HorizontalDivider()
        }
    }
}

@Composable
private fun PostPreviewDialog(
    imageUrl: String?,
    content: String?,
    authorName: String?,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.9f))
                .clickable(onClick = onDismiss),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (authorName != null) {
                    Text(
                        text = authorName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )
                }

                if (content != null) {
                    Text(
                        text = content,
                        style = MaterialTheme.typography.bodyLarge,
                        color = Color.White,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = if (imageUrl != null) 16.dp else 0.dp)
                    )
                }

                if (imageUrl != null) {
                    AsyncImage(
                        model = imageUrl,
                        contentDescription = "Post image",
                        modifier = Modifier.fillMaxWidth(),
                        contentScale = ContentScale.Fit
                    )
                }
            }
        }
    }
}

@Composable
private fun NotificationItem(
    notification: Notification,
    onClick: () -> Unit
) {
    val isClickable = notification.type != NotificationType.ZAP && notification.type != NotificationType.FOLLOW

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (isClickable) Modifier.clickable(onClick = onClick)
                else Modifier
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Avatar
        Box(
            modifier = Modifier.size(48.dp),
            contentAlignment = Alignment.Center
        ) {
            if (notification.actorAvatar != null) {
                AsyncImage(
                    model = notification.actorAvatar,
                    contentDescription = "Avatar",
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape),
                    contentScale = ContentScale.Crop
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = (notification.actorName?.firstOrNull() ?: "?").toString().uppercase(),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Type indicator badge
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(
                        when (notification.type) {
                            NotificationType.REACTION -> Color(0xFFE91E63)
                            NotificationType.ZAP -> Color(0xFFFFD700)
                            NotificationType.MENTION -> Color(0xFF2196F3)
                            NotificationType.FOLLOW -> Color(0xFF4CAF50)
                        }
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = when (notification.type) {
                        NotificationType.REACTION -> Icons.Default.Favorite
                        NotificationType.ZAP -> Icons.Default.Bolt
                        NotificationType.MENTION -> Icons.Default.AlternateEmail
                        NotificationType.FOLLOW -> Icons.Default.PersonAdd
                    },
                    contentDescription = null,
                    modifier = Modifier.size(12.dp),
                    tint = Color.White
                )
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        // Content
        Column(
            modifier = Modifier.weight(1f)
        ) {
            Text(
                text = notification.actorName ?: notification.actorPubkey.take(12) + "...",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(2.dp))

            Text(
                text = when (notification.type) {
                    NotificationType.REACTION -> "liked your post"
                    NotificationType.ZAP -> {
                        val amount = notification.zapAmount
                        if (amount != null) "zapped $amount sats" else "zapped your post"
                    }
                    NotificationType.MENTION -> "mentioned you"
                    NotificationType.FOLLOW -> "followed you"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(2.dp))

            Text(
                text = formatRelativeTime(notification.timestamp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline
            )
        }
    }
}

@Composable
private fun EmptyNotificationsState(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = Icons.Default.Notifications,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.secondary
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "No notifications yet",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "When someone reacts to or zaps your posts, you'll see it here",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.secondary
            )
        }
    }
}

private fun formatRelativeTime(timestamp: Long): String {
    val now = System.currentTimeMillis() / 1000
    val diff = now - timestamp

    return when {
        diff < 60 -> "just now"
        diff < 3600 -> "${diff / 60}m ago"
        diff < 86400 -> "${diff / 3600}h ago"
        diff < 604800 -> "${diff / 86400}d ago"
        else -> "${diff / 604800}w ago"
    }
}
