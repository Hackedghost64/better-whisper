package com.betterwhisper

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.betterwhisper.core.transcribe.PhononTranscribeEngine
import com.betterwhisper.ui.BetterWhisperTheme
import com.betterwhisper.ui.SetupScreen

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Warm the model off the main thread; the setup screen observes
        // PhononTranscribeEngine.isLoadedFlow.
        Thread {
            PhononTranscribeEngine.instance.ensureLoaded(applicationContext)
        }.start()

        setContent {
            BetterWhisperTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    SetupScreen()
                }
            }
        }
    }
}
