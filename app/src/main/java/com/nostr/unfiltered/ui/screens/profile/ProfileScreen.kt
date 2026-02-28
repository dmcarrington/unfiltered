package com.nostr.unfiltered.ui.screens.profile

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.nostr.unfiltered.ui.components.FullscreenImageDialog
import com.nostr.unfiltered.ui.components.UserAvatar
import com.nostr.unfiltered.viewmodel.ProfileViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    pubkey: String,
    onBackClick: () -> Unit,
    onPostClick: (String) -> Unit = {},
    viewModel: ProfileViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    var selectedPostIndex by remember { mutableIntStateOf(-1) }

    // Amber signing launcher for follow/unfollow
    val amberLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            // Amber returns the signed event in various possible extras
            val signedEvent = result.data?.getStringExtra("event")
                ?: result.data?.getStringExtra("signature")
                ?: result.data?.getStringExtra("result")
            if (signedEvent != null) {
                viewModel.handleAmberSignedFollow(signedEvent)
            } else {
                viewModel.clearPendingFollowIntent()
            }
        } else {
            viewModel.clearPendingFollowIntent()
        }
    }

    // Launch Amber when a pending follow intent is set
    LaunchedEffect(uiState.pendingFollowIntent) {
        uiState.pendingFollowIntent?.let { intent ->
            amberLauncher.launch(intent)
        }
    }

    LaunchedEffect(pubkey) {
        viewModel.loadProfile(pubkey)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = uiState.metadata?.bestName ?: "Profile",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        if (uiState.isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
            ) {
                // Profile header
                ProfileHeader(
                    avatarUrl = uiState.metadata?.picture,
                    name = uiState.metadata?.bestName ?: uiState.npub.take(16) + "...",
                    nip05 = uiState.metadata?.nip05,
                    about = uiState.metadata?.about,
                    npub = uiState.npub,
                    postsCount = uiState.posts.size,
                    followingCount = uiState.followingCount,
                    isFollowing = uiState.isFollowing,
                    isMuted = uiState.isMuted,
                    isOwnProfile = uiState.isOwnProfile,
                    onFollowClick = { viewModel.toggleFollow() },
                    onMuteClick = { viewModel.toggleMute() }
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Posts grid
                if (uiState.posts.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "No posts yet",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        contentPadding = PaddingValues(2.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        itemsIndexed(uiState.posts) { index, post ->
                            AsyncImage(
                                model = post.imageUrl,
                                contentDescription = post.caption,
                                modifier = Modifier
                                    .aspectRatio(1f)
                                    .padding(2.dp)
                                    .clickable {
                                        selectedPostIndex = index
                                    },
                                contentScale = ContentScale.Crop
                            )
                        }
                    }
                }
            }
        }

        // Fullscreen Image Viewer Dialog
        if (selectedPostIndex >= 0) {
            FullscreenImageDialog(
                posts = uiState.posts,
                initialIndex = selectedPostIndex,
                onDismiss = { selectedPostIndex = -1 }
            )
        }
    }
}

@Composable
private fun ProfileHeader(
    avatarUrl: String?,
    name: String,
    nip05: String?,
    about: String?,
    npub: String,
    postsCount: Int,
    followingCount: Int,
    isFollowing: Boolean,
    isMuted: Boolean,
    isOwnProfile: Boolean,
    onFollowClick: () -> Unit,
    onMuteClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            UserAvatar(
                imageUrl = avatarUrl,
                size = 80.dp
            )

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = name,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    if (nip05 != null) {
                        Icon(
                            imageVector = Icons.Default.Verified,
                            contentDescription = "Verified",
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                nip05?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }

                Text(
                    text = npub.take(20) + "...",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Stats row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            StatItem(value = postsCount.toString(), label = "posts")
            if (isOwnProfile) {
                StatItem(value = followingCount.toString(), label = "following")
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // About
        about?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(12.dp))
        }

        // Follow button
        if (!isOwnProfile) {
            if (isFollowing) {
                OutlinedButton(
                    onClick = onFollowClick,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Following")
                }
            } else {
                Button(
                    onClick = onFollowClick,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Follow")
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Mute button
            OutlinedButton(
                onClick = onMuteClick,
                modifier = Modifier.fillMaxWidth(),
                colors = if (isMuted) {
                    ButtonDefaults.outlinedButtonColors()
                } else {
                    ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                },
                border = if (isMuted) {
                    ButtonDefaults.outlinedButtonBorder(true)
                } else {
                    BorderStroke(
                        1.dp,
                        MaterialTheme.colorScheme.error
                    )
                }
            ) {
                Text(if (isMuted) "Unmute" else "Mute")
            }
        }
    }
}

@Composable
private fun StatItem(value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.secondary
        )
    }
}
