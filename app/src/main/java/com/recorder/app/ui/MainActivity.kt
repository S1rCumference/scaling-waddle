package com.recorder.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.recorder.app.service.RecordingService
import com.recorder.app.ui.setup.SetupViewModel
import com.recorder.app.ui.setup.SetupWizard

/**
 * The inner-screen entry point. Draws the same [RecorderApp] as the cover screen; which
 * layout appears is decided by the window, and where you are is kept in [AppUiState].
 */
class MainActivity : ComponentActivity() {

    private val viewModel: RecorderViewModel by viewModels()
    private val setupViewModel: SetupViewModel by viewModels()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted ->
        if (granted[Manifest.permission.RECORD_AUDIO] == true) startUnlessConflict()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val setupComplete by viewModel.setupComplete.collectAsState()
            var rerunSetup by remember { mutableStateOf(false) }

            when {
                // Still reading the flag; showing nothing beats flashing the wizard.
                setupComplete == null -> Unit

                setupComplete == false || rerunSetup -> RecorderTheme {
                    SetupWizard(setupViewModel) {
                        rerunSetup = false
                        requestPermissionsThenRecord()
                    }
                }

                else -> {
                    LaunchedEffect(Unit) { requestPermissionsThenRecord() }
                    RecorderApp(viewModel, onRunSetup = {
                        setupViewModel.reload()
                        rerunSetup = true
                    })
                }
            }
        }
    }

    private fun requestPermissionsThenRecord() {
        val needed = buildList {
            if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED
            ) {
                add(Manifest.permission.RECORD_AUDIO)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (needed.isEmpty()) startUnlessConflict() else permissionLauncher.launch(needed.toTypedArray())
    }

    /**
     * Starts recording — unless another installed version of Recorder is holding the mic, in
     * which case the user is asked to stop that one first rather than one of them silently
     * recording nothing. Already recording means there is nothing to check.
     */
    private fun startUnlessConflict() {
        if (RecordingService.state.value == RecordingService.RecorderState.RECORDING) return
        if (viewModel.checkMicConflict()) return
        RecordingService.start(this)
    }
}
