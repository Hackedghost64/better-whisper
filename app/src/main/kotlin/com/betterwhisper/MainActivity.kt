package com.betterwhisper

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.betterwhisper.core.audio.AudioCaptureEngine
import com.betterwhisper.core.cleaner.TranscriptCleaner
import com.betterwhisper.core.transcribe.PhononTranscribeEngine
import com.betterwhisper.feature.bubble.DictationEventBus
import com.betterwhisper.feature.bubble.FlowAccessibilityService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Thread {
            PhononTranscribeEngine.instance.ensureLoaded(applicationContext)
        }.start()

        setContent {
            BetterWhisperTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color(0xFF0F1219)
                ) {
                    SetupAndTestScreen()
                }
            }
        }
    }
}

@SuppressLint("BatteryLife")
@Composable
fun SetupAndTestScreen() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    var hasMicPermission by remember { mutableStateOf(checkMicPermission(context)) }
    var canDrawOverlay by remember { mutableStateOf(checkOverlayPermission(context)) }
    var isAccessibilityEnabled by remember { mutableStateOf(isAccessibilityServiceEnabled(context)) }
    var isBatteryUnrestricted by remember { mutableStateOf(isIgnoringBatteryOptimizations(context)) }

    val isModelLoaded by PhononTranscribeEngine.instance.isLoadedFlow.collectAsState()
    val isServiceConnected by FlowAccessibilityService.isConnected.collectAsState()
    val latestDictation by DictationEventBus.latestEvent.collectAsState()

    val refreshPermissions = {
        hasMicPermission = checkMicPermission(context)
        canDrawOverlay = checkOverlayPermission(context)
        isAccessibilityEnabled = isAccessibilityServiceEnabled(context) || isServiceConnected
        isBatteryUnrestricted = isIgnoringBatteryOptimizations(context)
    }

    // Re-check permissions immediately whenever user returns from Settings (ON_RESUME)
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                refreshPermissions()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // Also poll every 500ms while screen is open so permission states update live without restart
    LaunchedEffect(Unit) {
        while (true) {
            refreshPermissions()
            delay(500)
        }
    }

    val micLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasMicPermission = granted
    }

    var testInputText by remember { mutableStateOf("") }
    var statusMessage by remember { mutableStateOf("") }
    var isDirectRecording by remember { mutableStateOf(false) }
    val directAudioEngine = remember { AudioCaptureEngine() }

    // Automatically append any dictation that arrives from the floating Flow bubble
    LaunchedEffect(latestDictation?.timestamp) {
        val ev = latestDictation ?: return@LaunchedEffect
        if (ev.cleanedText.isNotBlank()) {
            if (!testInputText.endsWith(ev.cleanedText)) {
                testInputText += ev.cleanedText
            }
            statusMessage = "Transcribed in ${ev.decodeMs} ms"
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

        // Engine Status Badge
        Surface(
            color = if (isModelLoaded) Color(0xFF10B981).copy(alpha = 0.15f) else Color(0xFFF59E0B).copy(alpha = 0.15f),
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
                    text = if (isModelLoaded) "Phonon-2 Engine Active (164 MB On-Device)" else "Loading Phonon-2 model weights...",
                    fontSize = 12.5.sp,
                    color = if (isModelLoaded) Color(0xFF10B981) else Color(0xFFF59E0B),
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

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
            isGranted = canDrawOverlay,
            onAction = {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${context.packageName}")
                )
                context.startActivity(intent)
            }
        )

        Spacer(modifier = Modifier.height(8.dp))

        PermissionItem(
            title = "2. Enable Accessibility Service",
            description = "Detects text field focus and pastes dictations automatically.",
            isGranted = isAccessibilityEnabled,
            onAction = {
                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                context.startActivity(intent)
            }
        )

        Spacer(modifier = Modifier.height(8.dp))

        PermissionItem(
            title = "3. Microphone Access",
            description = "Captures 16 kHz audio only while the bubble is active.",
            isGranted = hasMicPermission,
            onAction = {
                micLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        )

        Spacer(modifier = Modifier.height(8.dp))

        PermissionItem(
            title = "4. Unrestricted Battery",
            description = "Prevents Android from putting the Flow bubble service to sleep.",
            isGranted = isBatteryUnrestricted,
            onAction = {
                val intent = Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:${context.packageName}")
                )
                context.startActivity(intent)
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
            placeholder = { Text("Tap here to bring up the Flow bubble, or tap Test Voice below...", color = Color.Gray) },
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

        // Direct in-app test button that invokes Module 2 -> Module 3 -> Module 4 directly
        Button(
            onClick = {
                if (!hasMicPermission) {
                    micLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    return@Button
                }
                if (!isDirectRecording) {
                    isDirectRecording = true
                    statusMessage = "Listening... (tap Stop & Transcribe when done)"
                    directAudioEngine.startCapture()
                } else {
                    isDirectRecording = false
                    statusMessage = "Transcribing..."
                    val pcm = directAudioEngine.stopAndGetPcm()
                    scope.launch {
                        val res = withContext(Dispatchers.IO) {
                            PhononTranscribeEngine.instance.ensureLoaded(context.applicationContext)
                            val t0 = System.currentTimeMillis()
                            val raw = PhononTranscribeEngine.instance.transcribeBuffer(pcm)
                            val ms = System.currentTimeMillis() - t0
                            val cleaned = TranscriptCleaner.default.clean(raw, testInputText)
                            Triple(raw, cleaned, ms)
                        }
                        if (res.second.isNotBlank()) {
                            testInputText += res.second
                            statusMessage = "Wrote sentence in ${res.third} ms"
                        } else {
                            statusMessage = "No speech detected (${res.third} ms)"
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
fun PermissionItem(
    title: String,
    description: String,
    isGranted: Boolean,
    onAction: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF161B22)),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .padding(14.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (isGranted) Icons.Default.CheckCircle else Icons.Default.Warning,
                contentDescription = null,
                tint = if (isGranted) Color(0xFF10B981) else Color(0xFFF59E0B),
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White
                )
                Text(
                    text = description,
                    fontSize = 12.sp,
                    color = Color.White.copy(alpha = 0.6f)
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            if (!isGranted) {
                Button(
                    onClick = onAction,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3B82F6)),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text("Grant", fontSize = 12.sp)
                }
            } else {
                Text(
                    text = "Active",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF10B981)
                )
            }
        }
    }
}

fun checkMicPermission(context: Context): Boolean {
    return ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.RECORD_AUDIO
    ) == PackageManager.PERMISSION_GRANTED
}

fun checkOverlayPermission(context: Context): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        Settings.canDrawOverlays(context)
    } else true
}

fun isAccessibilityServiceEnabled(context: Context): Boolean {
    val enabledServices = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    ) ?: return false
    return enabledServices.contains(context.packageName) &&
            enabledServices.contains("FlowAccessibilityService")
}

fun isIgnoringBatteryOptimizations(context: Context): Boolean {
    val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    return pm.isIgnoringBatteryOptimizations(context.packageName)
}

@Composable
fun BetterWhisperTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color(0xFF3B82F6),
            secondary = Color(0xFF10B981),
            surface = Color(0xFF161B22),
            background = Color(0xFF0F1219)
        ),
        content = content
    )
}
