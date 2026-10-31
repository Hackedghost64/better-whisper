package com.betterwhisper.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.betterwhisper.core.audio.AudioCaptureEngine
import com.betterwhisper.core.cleaner.TranscriptCleaner
import com.betterwhisper.core.intent.SpeechIntentResolver
import com.betterwhisper.core.transcribe.PhononTranscribeEngine
import com.betterwhisper.feature.bubble.DictationEventBus
import com.betterwhisper.feature.bubble.FlowAccessibilityService
import com.betterwhisper.util.canDrawOverlays
import com.betterwhisper.util.hasMicPermission
import com.betterwhisper.util.isAccessibilityServiceEnabled
import com.betterwhisper.util.isIgnoringBatteryOptimizations
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@SuppressLint("BatteryLife")
@Composable
fun SetupScreen() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    var hasMic by remember { mutableStateOf(hasMicPermission(context)) }
    var hasOverlay by remember { mutableStateOf(canDrawOverlays(context)) }
    var hasAccessibility by remember { mutableStateOf(isAccessibilityServiceEnabled(context)) }
    var batteryUnrestricted by remember { mutableStateOf(isIgnoringBatteryOptimizations(context)) }

    val isModelLoaded by PhononTranscribeEngine.instance.isLoadedFlow.collectAsState()
    val isServiceConnected by FlowAccessibilityService.isConnected.collectAsState()
    val latestDictation by DictationEventBus.latestEvent.collectAsState()

    val refreshPermissions = {
        hasMic = hasMicPermission(context)
        hasOverlay = canDrawOverlays(context)
        hasAccessibility = isAccessibilityServiceEnabled(context) || isServiceConnected
        batteryUnrestricted = isIgnoringBatteryOptimizations(context)
    }

    // Re-check immediately when the user comes back from system Settings.
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                refreshPermissions()
                delay(500)
            }
        }
    }

    val micLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasMic = granted }

    var testInputText by remember { mutableStateOf("") }
    var statusMessage by remember { mutableStateOf("") }
    var isDirectRecording by remember { mutableStateOf(false) }
    val directAudioEngine = remember { AudioCaptureEngine() }

    DisposableEffect(directAudioEngine) {
        onDispose {
            // Never leave the microphone held by a destroyed activity.
            directAudioEngine.release()
        }
    }

    LaunchedEffect(latestDictation?.timestamp) {
        val event = latestDictation ?: return@LaunchedEffect
        // When the accessibility service typed into this very screen, the text
        // field already has the transcript; appending again would duplicate it.
        val typedIntoThisScreen = event.injected && event.targetPackage == context.packageName
        if (!typedIntoThisScreen &&
            event.cleanedText.isNotBlank() &&
            !testInputText.endsWith(event.cleanedText)
        ) {
            testInputText += event.cleanedText
        }
        statusMessage = when {
            !event.injected -> "Copied to clipboard (target app changed)"
            else -> "Transcribed in ${event.decodeMs} ms"
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Better Whisper",
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )
        Text(
            text = "Wispr Flow for Android (100% Offline)",
            fontSize = 13.sp,
            color = Color(0xFF60A5FA),
            fontWeight = FontWeight.Medium
        )

        Spacer(modifier = Modifier.height(14.dp))

        EngineStatusBadge(isModelLoaded)

        Spacer(modifier = Modifier.height(18.dp))

        Text(
            text = "PERMISSIONS STATUS (LIVE)",
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White.copy(alpha = 0.5f),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(8.dp))

        PermissionItem(
            title = "1. Display Over Other Apps",
            description = "Displays the Flow bubble above your keyboard when typing.",
            isGranted = hasOverlay,
            onAction = {
                context.startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:${context.packageName}")
                    )
                )
            }
        )

        Spacer(modifier = Modifier.height(8.dp))

        PermissionItem(
            title = "2. Enable Accessibility Service",
            description = "Detects text field focus and pastes dictations automatically.",
            isGranted = hasAccessibility,
            onAction = {
                context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        )

        Spacer(modifier = Modifier.height(8.dp))

        PermissionItem(
            title = "3. Microphone Access",
            description = "Captures 16 kHz audio only while the bubble is active.",
            isGranted = hasMic,
            onAction = { micLauncher.launch(Manifest.permission.RECORD_AUDIO) }
        )

        Spacer(modifier = Modifier.height(8.dp))

        PermissionItem(
            title = "4. Unrestricted Battery",
            description = "Prevents Android from putting the Flow bubble service to sleep.",
            isGranted = batteryUnrestricted,
            onAction = {
                context.startActivity(
                    Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:${context.packageName}")
                    )
                )
            }
        )

        Spacer(modifier = Modifier.height(22.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "TEST PLAYGROUND",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White.copy(alpha = 0.5f)
            )
            if (statusMessage.isNotBlank()) {
                Text(
                    text = statusMessage,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFF10B981)
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(
            value = testInputText,
            onValueChange = { testInputText = it },
            placeholder = {
                Text(
                    "Tap here to bring up the Flow bubble, or tap Test Voice below...",
                    color = Color.Gray
                )
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(130.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = Color(0xFF161B22),
                unfocusedContainerColor = Color(0xFF161B22),
                focusedBorderColor = Color(0xFF3B82F6),
                unfocusedBorderColor = Color(0xFF30363D),
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White
            ),
            shape = RoundedCornerShape(12.dp)
        )

        Spacer(modifier = Modifier.height(12.dp))

        Button(
            onClick = {
                if (!hasMic) {
                    micLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    return@Button
                }
                if (!isDirectRecording) {
                    isDirectRecording = true
                    statusMessage = "Listening... (tap Stop & Transcribe when done)"
                    if (!directAudioEngine.startCapture()) {
                        isDirectRecording = false
                        statusMessage = "Microphone unavailable"
                    }
                } else {
                    isDirectRecording = false
                    statusMessage = "Transcribing..."
                    val pcm = directAudioEngine.stopAndGetPcm()
                    scope.launch {
                        val result = withContext(Dispatchers.IO) {
                            if (!PhononTranscribeEngine.instance.ensureLoaded(context.applicationContext)) {
                                return@withContext null
                            }
                            SpeechIntentResolver.init(context.applicationContext)
                            val startedAt = System.currentTimeMillis()
                            val raw = PhononTranscribeEngine.instance.transcribeBuffer(pcm)
                            val cleaned = SpeechIntentResolver.resolve(raw, testInputText.ifBlank { null })
                            val ms = System.currentTimeMillis() - startedAt
                            Triple(raw, cleaned, ms)
                        }
                        statusMessage = if (result == null) {
                            "Phonon-2 model not loaded"
                        } else if (result.second.isNotBlank()) {
                            val prefix = if (testInputText.isNotBlank() && !testInputText.endsWith(" ") && !result.second.startsWith(" ")) " " else ""
                            testInputText += prefix + result.second
                            "Resolved in ${result.third} ms"
                        } else {
                            "No speech detected (${result.third} ms)"
                        }
                    }
                }
            },
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isDirectRecording) Color(0xFFEF4444) else Color(0xFF3B82F6)
            ),
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
        ) {
            Icon(
                imageVector = if (isDirectRecording) Icons.Default.Stop else Icons.Default.Mic,
                contentDescription = null
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = if (isDirectRecording) "Stop & Transcribe Now" else "Test Voice Dictation",
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
private fun EngineStatusBadge(isModelLoaded: Boolean) {
    Surface(
        color = if (isModelLoaded) Color(0xFF10B981).copy(alpha = 0.15f)
        else Color(0xFFF59E0B).copy(alpha = 0.15f),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (isModelLoaded) Icons.Default.CheckCircle else Icons.Default.Warning,
                contentDescription = null,
                tint = if (isModelLoaded) Color(0xFF10B981) else Color(0xFFF59E0B),
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = if (isModelLoaded) "Phonon-2 Engine Active (164 MB On-Device)"
                else "Loading Phonon-2 model weights...",
                fontSize = 12.5.sp,
                color = if (isModelLoaded) Color(0xFF10B981) else Color(0xFFF59E0B),
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}
