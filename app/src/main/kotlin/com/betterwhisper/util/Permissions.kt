package com.betterwhisper.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat

fun hasMicPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.RECORD_AUDIO
    ) == PackageManager.PERMISSION_GRANTED

fun canDrawOverlays(context: Context): Boolean = Settings.canDrawOverlays(context)

/**
 * Checks the secure setting rather than the live service connection so the
 * setup screen can explain "enabled but not running yet".
 */
fun isAccessibilityServiceEnabled(context: Context): Boolean {
    val enabledServices = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    ) ?: return false

    val component = "${context.packageName}/com.betterwhisper.feature.bubble.FlowAccessibilityService"
    return enabledServices.split(':').any { it.equals(component, ignoreCase = true) } ||
        (enabledServices.contains(context.packageName) &&
            enabledServices.contains("FlowAccessibilityService"))
}

fun isIgnoringBatteryOptimizations(context: Context): Boolean {
    val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    return powerManager.isIgnoringBatteryOptimizations(context.packageName)
}
