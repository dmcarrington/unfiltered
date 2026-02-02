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
import com.nostr.unfiltered.nostr.KeyManager
import com.nostr.unfiltered.ui.navigation.UnfilteredNavGraph
import com.nostr.unfiltered.ui.theme.UnfilteredTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var keyManager: KeyManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Handle Amber callback if app was launched via deep link
        handleAmberCallback(intent)

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
        setIntent(intent)
        // Handle Amber callback when returning from Amber app
        handleAmberCallback(intent)
    }

    private fun handleAmberCallback(intent: Intent) {
        val data = intent.data ?: return
        if (data.scheme == "nostr-unfiltered") {
            val result = keyManager.parseAmberCallback(data)
            keyManager.setPendingAmberCallback(result)
        }
    }
}
