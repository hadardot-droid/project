package com.memoryreel.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.core.content.IntentCompat
import com.memoryreel.app.ui.AppScreen
import com.memoryreel.app.ui.MemoryTheme

class MainActivity : ComponentActivity() {

    private val vm: ReelViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handleShare(intent)
        setContent {
            MemoryTheme {
                // The interface is in Hebrew, so lay it out right-to-left on every phone.
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    AppScreen(vm)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    /** Photos shared into the app from the gallery ("Share" → זיכרונות). */
    private fun handleShare(intent: Intent?) {
        if (intent == null) return
        val uris: List<Uri> = when (intent.action) {
            Intent.ACTION_SEND ->
                listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
            Intent.ACTION_SEND_MULTIPLE ->
                IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
            else -> emptyList()
        }
        if (uris.isNotEmpty()) vm.addPhotos(uris)
    }
}
