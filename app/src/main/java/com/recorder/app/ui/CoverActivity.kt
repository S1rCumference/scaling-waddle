package com.recorder.app.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The cover-screen entry point, launched onto the outer display when the phone is closed.
 *
 * It draws exactly the same [RecorderApp] as the inner screen, over the same database and
 * the same [AppUiState] — so it opens on the tab, group and scroll position you had on the
 * inside, and hands them back when you open the phone. It never touches the recording
 * service, so nothing about opening or closing the phone can interrupt recording.
 */
class CoverActivity : ComponentActivity() {

    private val viewModel: RecorderViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val setupComplete by viewModel.setupComplete.collectAsState()
            if (setupComplete == false) {
                Box(Modifier.fillMaxSize().background(Color.Black).padding(12.dp)) {
                    Text("Open the phone to finish setting up Recorder.", color = Color.White, fontSize = 16.sp)
                }
            } else if (setupComplete == true) {
                // Setup is only ever done on the inner screen.
                RecorderApp(viewModel, onRunSetup = {})
            }
        }
    }
}
