package com.edgehybrid.agent.infobox

import androidx.activity.ComponentActivity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Handles Android system text selection ("PROCESS_TEXT") and share targets ("SEND").
 *
 * When the user selects text or long-presses a hyperlink anywhere on the phone,
 * "Add to Info Box" appears in the system context toolbar alongside Copy and Share.
 * This activity ingests the content into [InfoBoxStore] and finishes instantaneously.
 */
@AndroidEntryPoint
class AddToInfoBoxActivity : ComponentActivity() {

    @Inject
    lateinit var infoBoxStore: InfoBoxStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val capturedText = when (intent.action) {
            Intent.ACTION_PROCESS_TEXT -> {
                intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
            }
            Intent.ACTION_SEND -> {
                intent.getStringExtra(Intent.EXTRA_TEXT)
                    ?: intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
            }
            else -> null
        }

        val preferredTitle = intent.getStringExtra(Intent.EXTRA_SUBJECT)
            ?: intent.getStringExtra(Intent.EXTRA_TITLE)

        val callerPackage = runCatching { callingPackage }.getOrNull()

        if (!capturedText.isNullOrBlank()) {
            CoroutineScope(Dispatchers.Main).launch {
                val item = infoBoxStore.addFromText(
                    rawText = capturedText,
                    sourceApp = callerPackage,
                    preferredTitle = preferredTitle
                )
                val preview = if (item.title.length > 28) item.title.take(25) + "..." else item.title
                Toast.makeText(
                    applicationContext,
                    "📥 Added to Info Box: $preview",
                    Toast.LENGTH_SHORT
                ).show()
                finish()
            }
        } else {
            finish()
        }
    }
}
