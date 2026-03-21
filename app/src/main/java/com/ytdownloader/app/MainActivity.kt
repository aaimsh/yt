package com.ytdownloader.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.ytdownloader.app.ui.theme.YTDownloaderTheme
import com.ytdownloader.app.ui.screens.MainScreen

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val sharedUrl = handleIntent(intent)

        setContent {
            YTDownloaderTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen(initialUrl = sharedUrl)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val url = handleIntent(intent)
        if (url != null) {
            // Re-compose with new URL
            setContent {
                YTDownloaderTheme {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background
                    ) {
                        MainScreen(initialUrl = url)
                    }
                }
            }
        }
    }

    private fun handleIntent(intent: Intent?): String? {
        if (intent == null) return null

        return when (intent.action) {
            Intent.ACTION_SEND -> {
                intent.getStringExtra(Intent.EXTRA_TEXT)?.let { text ->
                    extractYouTubeUrl(text)
                }
            }
            Intent.ACTION_VIEW -> {
                intent.data?.toString()
            }
            else -> null
        }
    }

    private fun extractYouTubeUrl(text: String): String? {
        val urlPattern = Regex(
            "(https?://)?(www\\.)?(youtube\\.com/watch\\?v=|youtu\\.be/|youtube\\.com/shorts/|m\\.youtube\\.com/watch\\?v=)[\\w-]+"
        )
        val match = urlPattern.find(text)
        return match?.value ?: if (text.startsWith("http")) text else null
    }
}
