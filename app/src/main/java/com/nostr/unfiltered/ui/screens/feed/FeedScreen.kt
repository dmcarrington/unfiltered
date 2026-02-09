package com.nostr.unfiltered.ui.screens.feed

import android.app.Activity
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import com.nostr.unfiltered.R
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.nostr.unfiltered.nostr.models.MediaItem
import com.nostr.unfiltered.ui.components.PhotoCard
import com.nostr.unfiltered.viewmodel.FeedMode
import com.nostr.unfiltered.viewmodel.FeedViewModel
import com.nostr.unfiltered.viewmodel.NotificationsViewModel
import com.nostr.unfiltered.viewmodel.ZapState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedScreen(
    onProfileClick: (String) -> Unit,
    onSearchClick: () -> Unit,
    onCreatePostClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onWalletClick: () -> Unit,
    onNotificationsClick: () -> Unit,
    viewModel: FeedViewModel = hiltViewModel(),
    notificationsViewModel: NotificationsViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()
    val zapState by viewModel.zapState.collectAsState()
    val pendingLikeIntent by viewModel.pendingLikeIntent.collectAsState()
    val hasNewPosts by viewModel.hasNewPosts.collectAsState()
    val hasUnreadNotifications by notificationsViewModel.hasUnread.collectAsState()
    val listState = rememberLazyListState()

    // Amber like signing launcher
    val amberLikeLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            // Amber may return signed event under different extra keys
            val signedEvent = result.data?.getStringExtra("event")
                ?: result.data?.getStringExtra("signature")
                ?: result.data?.getStringExtra("result")
            if (signedEvent != null) {
                viewModel.handleAmberSignedLike(signedEvent)
            } else {
                viewModel.clearPendingLikeIntent()
            }
        } else {
            viewModel.clearPendingLikeIntent()
        }
    }

    // Launch Amber for like signing when needed
    LaunchedEffect(pendingLikeIntent) {
        pendingLikeIntent?.let { intent ->
            amberLikeLauncher.launch(intent)
        }
    }

    // Refresh zap status when screen appears
    LaunchedEffect(Unit) {
        viewModel.refreshZapStatus()
    }

    // Mark feed as read when screen first displays
    LaunchedEffect(Unit) {
        viewModel.markFeedAsRead()
    }

    // Handle zap state changes
    LaunchedEffect(zapState) {
        when (val state = zapState) {
            is ZapState.OpenWallet -> {
                try {
                    context.startActivity(state.intent)
                    viewModel.onWalletOpened()
                } catch (e: Exception) {
                    Toast.makeText(
                        context,
                        "Failed to open wallet: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                    viewModel.clearZapState()
                }
            }
            is ZapState.WalletOpened -> {
                Toast.makeText(
                    context,
                    "Invoice sent to wallet for ${state.amountSats} sats",
                    Toast.LENGTH_SHORT
                ).show()
                viewModel.clearZapState()
            }
            is ZapState.Success -> {
                Toast.makeText(
                    context,
                    "Zapped ${state.amountSats} sats!",
                    Toast.LENGTH_SHORT
                ).show()
                viewModel.clearZapState()
            }
            is ZapState.Error -> {
                Toast.makeText(
                    context,
                    "Zap failed: ${state.message}",
                    Toast.LENGTH_LONG
                ).show()
                viewModel.clearZapState()
            }
            else -> {}
        }
    }

    // Feed mode dropdown state
    var dropdownExpanded by remember { mutableStateOf(false) }

    // Fullscreen image viewer state
    var selectedMediaItems by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var selectedMediaIndex by remember { mutableStateOf(0) }

    // Coroutine scope for scroll animations
    val coroutineScope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Image(
                            painter = painterResource(id = R.drawable.banner),
                            contentDescription = "Unfiltered",
                            modifier = Modifier.height(32.dp),
                            contentScale = ContentScale.Fit
                        )

                        Spacer(modifier = Modifier.width(8.dp))

                        // Feed mode dropdown
                        Box {
                            TextButton(
                                onClick = { dropdownExpanded = true }
                            ) {
                                Text(
                                    text = when (uiState.feedMode) {
                                        FeedMode.FOLLOWING -> "Following"
                                        FeedMode.TRENDING -> "Trending"
                                    },
                                    style = MaterialTheme.typography.labelLarge
                                )
                                Icon(
                                    imageVector = Icons.Default.ArrowDropDown,
                                    contentDescription = "Select feed"
                                )
                            }

                            DropdownMenu(
                                expanded = dropdownExpanded,
                                onDismissRequest = { dropdownExpanded = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Following") },
                                    onClick = {
                                        viewModel.setFeedMode(FeedMode.FOLLOWING)
                                        dropdownExpanded = false
                                    },
                                    enabled = uiState.hasFollows
                                )
                                DropdownMenuItem(
                                    text = { Text("Trending") },
                                    onClick = {
                                        viewModel.setFeedMode(FeedMode.TRENDING)
                                        dropdownExpanded = false
                                    }
                                )
                            }
                        }
                    }
                },
                actions = {
                    IconButton(onClick = onSearchClick) {
                        Icon(Icons.Default.Search, contentDescription = "Search")
                    }
                    IconButton(onClick = onSettingsClick) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onCreatePostClick) {
                Icon(Icons.Default.Add, contentDescription = "Create post")
            }
        },
        bottomBar = {
            BottomAppBar {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    // Home - scroll to top
                    IconButton(
                        onClick = {
                            coroutineScope.launch {
                                listState.animateScrollToItem(0)
                            }
                            viewModel.markFeedAsRead()
                        }
                    ) {
                        Box {
                            Icon(Icons.Default.Home, contentDescription = "Home")
                            if (hasNewPosts) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .align(Alignment.TopEnd)
                                        .background(Color.Red, CircleShape)
                                )
                            }
                        }
                    }

                    // Wallet
                    IconButton(onClick = onWalletClick) {
                        Icon(Icons.Default.AccountBalanceWallet, contentDescription = "Wallet")
                    }

                    // Notifications
                    IconButton(onClick = onNotificationsClick) {
                        Box {
                            Icon(Icons.Default.Notifications, contentDescription = "Notifications")
                            if (hasUnreadNotifications) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .align(Alignment.TopEnd)
                                        .background(Color.Red, CircleShape)
                                )
                            }
                        }
                    }
                }
            }
        }
    ) { paddingValues ->
        PullToRefreshBox(
            isRefreshing = uiState.isRefreshing,
            onRefresh = { viewModel.refresh() },
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            when {
                // Loading initial content
                uiState.isLoading && uiState.posts.isEmpty() -> {
                    LoadingState()
                }

                // Not connected
                !uiState.isConnected && uiState.posts.isEmpty() -> {
                    DisconnectedState()
                }

                // Empty feed
                uiState.isEmpty -> {
                    EmptyFeedState(feedMode = uiState.feedMode)
                }

                // Show posts
                else -> {
                    LazyColumn(
                        state = listState,
                        contentPadding = PaddingValues(vertical = 8.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(
                            items = uiState.posts,
                            key = { it.id }
                        ) { post ->
                            PhotoCard(
                                post = post,
                                onProfileClick = { onProfileClick(post.authorPubkey) },
                                onLikeClick = { viewModel.likePost(post) },
                                onZapClick = { viewModel.initiateZap(post) },
                                onMediaClick = { index, _ ->
                                    // Use the post's media items if available, otherwise create a single-item list
                                    selectedMediaItems = if (post.mediaItems.isNotEmpty()) {
                                        post.mediaItems
                                    } else {
                                        listOf(MediaItem(
                                            url = post.imageUrl,
                                            dimensions = post.dimensions,
                                            altText = post.altText,
                                            isVideo = post.isVideo
                                        ))
                                    }
                                    selectedMediaIndex = index
                                },
                                showZapButton = uiState.canZap && !post.authorLud16.isNullOrEmpty()
                            )
                        }

                        // Loading indicator at bottom
                        if (uiState.isLoading) {
                            item {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Zap Amount Selection Dialog
    val currentZapState = zapState
    if (currentZapState is ZapState.SelectingAmount) {
        ZapAmountDialog(
            onDismiss = { viewModel.cancelZap() },
            onConfirm = { amount ->
                viewModel.sendZap(currentZapState.post, amount)
            }
        )
    }

    // Zap Processing Dialog
    if (currentZapState is ZapState.Processing) {
        AlertDialog(
            onDismissRequest = { },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Bolt,
                        contentDescription = null,
                        tint = Color(0xFFFFD700),
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Sending Zap")
                }
            },
            text = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    Spacer(modifier = Modifier.width(12.dp))
                    Text("Processing payment...")
                }
            },
            confirmButton = { }
        )
    }

    // Fullscreen Image Viewer Dialog
    if (selectedMediaItems.isNotEmpty()) {
        FullscreenImageDialog(
            mediaItems = selectedMediaItems,
            initialIndex = selectedMediaIndex,
            onDismiss = { selectedMediaItems = emptyList() }
        )
    }
}

@Composable
private fun ZapAmountDialog(
    onDismiss: () -> Unit,
    onConfirm: (Long) -> Unit
) {
    val amounts = listOf(21L, 100L, 500L, 1000L, 5000L, 10000L)
    var selectedAmount by remember { mutableStateOf(100L) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Bolt,
                    contentDescription = null,
                    tint = Color(0xFFFFD700),
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Send Zap")
            }
        },
        text = {
            Column {
                Text(
                    text = "Select amount (sats)",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.secondary
                )
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    amounts.take(3).forEach { amount ->
                        FilterChip(
                            selected = selectedAmount == amount,
                            onClick = { selectedAmount = amount },
                            label = { Text("$amount") },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    amounts.drop(3).forEach { amount ->
                        FilterChip(
                            selected = selectedAmount == amount,
                            onClick = { selectedAmount = amount },
                            label = { Text(if (amount >= 1000) "${amount/1000}k" else "$amount") },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(selectedAmount) }) {
                Icon(
                    imageVector = Icons.Default.Bolt,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text("Zap $selectedAmount sats")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
private fun LoadingState() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            CircularProgressIndicator()
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Loading feed...",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.secondary
            )
        }
    }
}

@Composable
private fun DisconnectedState() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(32.dp)
        ) {
            Icon(
                imageVector = Icons.Default.CloudOff,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.secondary
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Not connected",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Connecting to relays...",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.secondary,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun EmptyFeedState(feedMode: FeedMode = FeedMode.TRENDING) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(32.dp)
        ) {
            Icon(
                imageVector = Icons.Default.PhotoLibrary,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.secondary
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = if (feedMode == FeedMode.FOLLOWING)
                    "No posts from people you follow"
                else
                    "No photos yet",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = if (feedMode == FeedMode.FOLLOWING)
                    "Follow more users to see their posts here!"
                else
                    "Photos from the Nostr network will appear here.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.secondary,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun FullscreenImageDialog(
    mediaItems: List<MediaItem>,
    initialIndex: Int,
    onDismiss: () -> Unit
) {
    val pagerState = rememberPagerState(
        initialPage = initialIndex.coerceIn(0, (mediaItems.size - 1).coerceAtLeast(0)),
        pageCount = { mediaItems.size }
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
            // Swipeable image pager
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize()
            ) { page ->
                val item = mediaItems[page]
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onDismiss
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    AsyncImage(
                        model = item.url,
                        contentDescription = item.altText ?: "Image ${page + 1}",
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp),
                        contentScale = ContentScale.Fit
                    )
                }
            }

            // Page indicator (only show if multiple images)
            if (mediaItems.size > 1) {
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 32.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    repeat(mediaItems.size) { index ->
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .background(
                                    color = if (pagerState.currentPage == index)
                                        Color.White
                                    else
                                        Color.White.copy(alpha = 0.5f),
                                    shape = CircleShape
                                )
                        )
                    }
                }

                // Page counter text
                Text(
                    text = "${pagerState.currentPage + 1} / ${mediaItems.size}",
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 48.dp)
                        .background(
                            color = Color.Black.copy(alpha = 0.5f),
                            shape = CircleShape
                        )
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
        }
    }
}
