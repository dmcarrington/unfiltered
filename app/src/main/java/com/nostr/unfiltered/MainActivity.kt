package com.nostr.unfiltered

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.nostr.unfiltered.ui.navigation.UnfilteredNavGraph
import com.nostr.unfiltered.ui.theme.UnfilteredTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            UnfilteredTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    UnfilteredNavGraph()
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Handle Amber callback
        handleAmberCallback(intent)
    }

    private fun handleAmberCallback(intent: Intent) {
        val data = intent.data ?: return
        if (data.scheme == "nostr-unfiltered") {
            // Amber callback - extract result and pass to appropriate handler
            val signature = data.getQueryParameter("signature")
            val event = data.getQueryParameter("event")
            // These will be handled by the AuthViewModel
        }
    }
}
