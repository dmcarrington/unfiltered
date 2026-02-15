package com.nostr.unfiltered.ui.screens.profile

import android.app.Activity
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.nostr.unfiltered.nostr.AmberCallbackResult
import com.nostr.unfiltered.nostr.KeyManager
import com.nostr.unfiltered.ui.components.UserAvatar
import com.nostr.unfiltered.viewmodel.ProfileEditViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileEditScreen(
    onBackClick: () -> Unit,
    onSaveSuccess: () -> Unit,
    viewModel: ProfileEditViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()

    // Launcher for Amber signing
    val amberLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        result.data?.let { data ->
            // Amber may return signed event under different extra keys
            val signedEvent = data.getStringExtra("event")
                ?: data.getStringExtra("signature")
                ?: data.getStringExtra("result")
            if (signedEvent != null) {
                viewModel.handleAmberSignedEvent(signedEvent)
            } else {
                viewModel.clearAmberSigningRequest()
                Toast.makeText(context, "Signing cancelled", Toast.LENGTH_SHORT).show()
            }
        } ?: run {
            viewModel.clearAmberSigningRequest()
        }
    }

    // Launcher for gallery image picker (profile picture upload)
    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        uri?.let { viewModel.uploadProfilePicture(context, it) }
    }

    // Launcher for Blossom upload Amber signing
    val blossomAmberLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val signedEvent = result.data?.getStringExtra("event")
                ?: result.data?.getStringExtra("signature")
                ?: result.data?.getStringExtra("result")
            if (signedEvent != null) {
                viewModel.handleBlossomAmberSignedEvent(signedEvent)
            } else {
                viewModel.clearBlossomAmberIntent()
            }
        } else {
            viewModel.clearBlossomAmberIntent()
        }
    }

    // Launch Blossom Amber signing when needed
    LaunchedEffect(uiState.pendingBlossomAmberIntent) {
        uiState.pendingBlossomAmberIntent?.let { intent ->
            try {
                blossomAmberLauncher.launch(intent)
            } catch (e: Exception) {
                Toast.makeText(context, "Failed to launch Amber: ${e.message}", Toast.LENGTH_LONG).show()
                viewModel.clearBlossomAmberIntent()
            }
        }
    }

    // Launch Amber when needed
    LaunchedEffect(uiState.needsAmberSigning) {
        uiState.needsAmberSigning?.let { intent ->
            try {
                amberLauncher.launch(intent)
            } catch (e: Exception) {
                Toast.makeText(context, "Failed to launch Amber: ${e.message}", Toast.LENGTH_LONG).show()
                viewModel.clearAmberSigningRequest()
            }
        }
    }

    // Handle save success
    LaunchedEffect(uiState.saveSuccess) {
        if (uiState.saveSuccess) {
            Toast.makeText(context, "Profile saved!", Toast.LENGTH_SHORT).show()
            viewModel.clearSaveSuccess()
            onSaveSuccess()
        }
    }

    // Handle errors
    LaunchedEffect(uiState.error) {
        uiState.error?.let { error ->
            Toast.makeText(context, error, Toast.LENGTH_LONG).show()
            viewModel.clearError()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Edit Profile", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                actions = {
                    if (uiState.isSaving) {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .size(24.dp)
                                .padding(end = 16.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        IconButton(
                            onClick = { viewModel.saveProfile() },
                            enabled = uiState.hasChanges && !uiState.isLoading
                        ) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = "Save",
                                tint = if (uiState.hasChanges) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                            )
                        }
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
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Loading profile...",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp)
            ) {
                // Avatar preview (tap to upload)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 16.dp)
                        .clickable {
                            photoPickerLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        },
                    contentAlignment = Alignment.Center
                ) {
                    if (uiState.isUploadingPicture) {
                        CircularProgressIndicator(modifier = Modifier.size(100.dp))
                    } else {
                        UserAvatar(
                            imageUrl = uiState.picture.takeIf { it.isNotBlank() },
                            size = 100.dp
                        )
                    }
                }

                // Basic Info Section
                SectionHeader("Basic Info")

                ProfileTextField(
                    value = uiState.displayName,
                    onValueChange = { viewModel.updateDisplayName(it) },
                    label = "Display Name",
                    placeholder = "Your display name",
                    leadingIcon = Icons.Default.Person
                )

                ProfileTextField(
                    value = uiState.name,
                    onValueChange = { viewModel.updateName(it) },
                    label = "Username",
                    placeholder = "username (lowercase, no spaces)",
                    leadingIcon = Icons.Default.AccountCircle
                )

                ProfileTextField(
                    value = uiState.about,
                    onValueChange = { viewModel.updateAbout(it) },
                    label = "About",
                    placeholder = "Tell people about yourself",
                    leadingIcon = Icons.Default.Info,
                    singleLine = false,
                    maxLines = 4
                )

                Spacer(modifier = Modifier.height(24.dp))

                // Images Section
                SectionHeader("Images")

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ProfileTextField(
                        value = uiState.picture,
                        onValueChange = { viewModel.updatePicture(it) },
                        label = "Profile Picture URL",
                        placeholder = "https://example.com/avatar.jpg",
                        leadingIcon = Icons.Default.AccountCircle,
                        modifier = Modifier.weight(1f)
                    )
                    if (uiState.isUploadingPicture) {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .padding(start = 8.dp)
                                .size(24.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        IconButton(
                            onClick = {
                                photoPickerLauncher.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                )
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Default.FileUpload,
                                contentDescription = "Upload profile picture"
                            )
                        }
                    }
                }

                ProfileTextField(
                    value = uiState.banner,
                    onValueChange = { viewModel.updateBanner(it) },
                    label = "Banner Image URL",
                    placeholder = "https://example.com/banner.jpg",
                    leadingIcon = Icons.Default.Image
                )

                Spacer(modifier = Modifier.height(24.dp))

                // Verification & Payments Section
                SectionHeader("Verification & Payments")

                ProfileTextField(
                    value = uiState.nip05,
                    onValueChange = { viewModel.updateNip05(it) },
                    label = "NIP-05 Identifier",
                    placeholder = "you@yourdomain.com",
                    leadingIcon = Icons.Default.Verified,
                    supportingText = "Verifies your identity on Nostr"
                )

                ProfileTextField(
                    value = uiState.lud16,
                    onValueChange = { viewModel.updateLud16(it) },
                    label = "Lightning Address",
                    placeholder = "you@getalby.com",
                    leadingIcon = Icons.Default.Bolt,
                    iconTint = Color(0xFFFFD700),
                    supportingText = "Required to receive zaps (tips)"
                )

                Spacer(modifier = Modifier.height(24.dp))

                // Links Section
                SectionHeader("Links")

                ProfileTextField(
                    value = uiState.website,
                    onValueChange = { viewModel.updateWebsite(it) },
                    label = "Website",
                    placeholder = "https://yourwebsite.com",
                    leadingIcon = Icons.Default.Link
                )

                Spacer(modifier = Modifier.height(32.dp))

                // Help text
                Text(
                    text = "Changes will be published to all connected relays when you tap the checkmark.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary
                )

                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(vertical = 8.dp)
    )
}

@Composable
private fun ProfileTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String,
    leadingIcon: ImageVector,
    iconTint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    supportingText: String? = null,
    singleLine: Boolean = true,
    maxLines: Int = 1,
    modifier: Modifier = Modifier
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = { Text(placeholder) },
        leadingIcon = {
            Icon(
                imageVector = leadingIcon,
                contentDescription = null,
                tint = iconTint
            )
        },
        supportingText = supportingText?.let {
            { Text(it, style = MaterialTheme.typography.bodySmall) }
        },
        singleLine = singleLine,
        maxLines = maxLines,
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    )
}
