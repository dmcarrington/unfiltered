package com.nostr.unfiltered.ui.screens.auth

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.nostr.unfiltered.R
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.nostr.unfiltered.nostr.AmberCallbackResult
import com.nostr.unfiltered.nostr.KeyManager
import com.nostr.unfiltered.viewmodel.AuthViewModel

@Composable
fun AuthScreen(
    onAuthSuccess: () -> Unit,
    viewModel: AuthViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    // Amber activity launcher
    val amberLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        // Amber returns result via callback URL, handled in MainActivity
    }

    // Handle authentication success
    LaunchedEffect(uiState.isAuthenticated) {
        if (uiState.isAuthenticated && !uiState.showBackupWarning) {
            onAuthSuccess()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // App banner
            Image(
                painter = painterResource(id = R.drawable.banner),
                contentDescription = "Unfiltered",
                modifier = Modifier
                    .fillMaxWidth()
                    .height(80.dp),
                contentScale = ContentScale.Fit
            )

            Text(
                text = "Photo sharing on Nostr",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.secondary
            )

            Spacer(modifier = Modifier.height(48.dp))

            if (uiState.isLoading) {
                CircularProgressIndicator()
            } else {
                // Amber button (primary, if available)
                if (uiState.isAmberAvailable) {
                    Button(
                        onClick = {
                            val intent = viewModel.getAmberIntent()
                            amberLauncher.launch(intent)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Sign in with Amber")
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                }

                // Import nsec button
                OutlinedButton(
                    onClick = { viewModel.showNsecDialog() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Import nsec")
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Generate new account button
                TextButton(
                    onClick = { viewModel.generateNewAccount() }
                ) {
                    Text("Create new account")
                }

                // Error message
                uiState.error?.let { error ->
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = error,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }

        // Nsec import dialog
        if (uiState.showNsecDialog) {
            NsecImportDialog(
                onDismiss = { viewModel.hideNsecDialog() },
                onImport = { nsec ->
                    viewModel.importNsec(nsec)
                    viewModel.hideNsecDialog()
                },
                error = uiState.error
            )
        }

        // Backup warning dialog for new accounts
        if (uiState.showBackupWarning && uiState.nsecGenerated != null) {
            BackupWarningDialog(
                nsec = uiState.nsecGenerated!!,
                npub = uiState.npub ?: "",
                onDismiss = {
                    viewModel.dismissBackupWarning()
                    onAuthSuccess()
                }
            )
        }
    }
}

@Composable
private fun NsecImportDialog(
    onDismiss: () -> Unit,
    onImport: (String) -> Unit,
    error: String?
) {
    var nsec by remember { mutableStateOf("") }
    var showNsec by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Import nsec") },
        text = {
            Column {
                Text(
                    text = "Enter your Nostr private key (nsec)",
                    style = MaterialTheme.typography.bodyMedium
                )

                Spacer(modifier = Modifier.height(16.dp))

                OutlinedTextField(
                    value = nsec,
                    onValueChange = { nsec = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("nsec") },
                    placeholder = { Text("nsec1...") },
                    singleLine = true,
                    visualTransformation = if (showNsec) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        IconButton(onClick = { showNsec = !showNsec }) {
                            Icon(
                                imageVector = if (showNsec) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = if (showNsec) "Hide" else "Show"
                            )
                        }
                    }
                )

                error?.let {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onImport(nsec) },
                enabled = nsec.startsWith("nsec1") && nsec.length > 60
            ) {
                Text("Import")
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
private fun BackupWarningDialog(
    nsec: String,
    npub: String,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current

    AlertDialog(
        onDismissRequest = { /* Prevent dismissal without acknowledgment */ },
        icon = {
            Icon(
                imageVector = Icons.Outlined.Warning,
                contentDescription = "Warning",
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(48.dp)
            )
        },
        title = {
            Text(
                text = "Backup Your Keys!",
                textAlign = TextAlign.Center
            )
        },
        text = {
            Column {
                Text(
                    text = "Your private key (nsec) is the ONLY way to access your account. If you lose it, your account is gone forever.",
                    style = MaterialTheme.typography.bodyMedium
                )

                Spacer(modifier = Modifier.height(16.dp))

                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = "Your nsec (KEEP SECRET):",
                            style = MaterialTheme.typography.labelMedium
                        )
                        Text(
                            text = nsec.take(20) + "..." + nsec.takeLast(10),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Button(
                            onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(ClipData.newPlainText("nsec", nsec))
                                Toast.makeText(context, "nsec copied - store it safely!", Toast.LENGTH_LONG).show()
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.ContentCopy, contentDescription = null)
                            Spacer(modifier = Modifier.size(8.dp))
                            Text("Copy nsec")
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Card {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = "Your npub (public, shareable):",
                            style = MaterialTheme.typography.labelMedium
                        )
                        Text(
                            text = npub.take(20) + "..." + npub.takeLast(10),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) {
                Text("I've saved my nsec")
            }
        }
    )
}
