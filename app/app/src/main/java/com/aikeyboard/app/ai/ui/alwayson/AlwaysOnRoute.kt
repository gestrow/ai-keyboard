// SPDX-License-Identifier: GPL-3.0-only
package com.aikeyboard.app.ai.ui.alwayson

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.aikeyboard.app.ai.a11y.AlwaysOnProxy
import com.aikeyboard.app.ai.a11y.ReadRespondConsentActivity
import com.aikeyboard.app.ai.storage.SecureStorage
import com.aikeyboard.app.latin.R

@Composable
fun AlwaysOnRoute(
    onBack: () -> Unit,
    storage: SecureStorage,
) {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(storage.isAlwaysOnEnabled()) }
    var a11yEnabled by remember { mutableStateOf(false) }
    var notifGranted by remember { mutableStateOf(false) }
    var consented by remember { mutableStateOf(storage.isReadRespondConsented()) }
    var resumeCount by remember { mutableIntStateOf(0) }

    // ON_RESUME refresh — re-check permission state every time the user comes
    // back from System Settings, the consent activity, or the notification
    // permission prompt. Same precedent as BackendsScreen.kt's resumeCount.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) resumeCount++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val notSupportedMsg = stringResource(R.string.ai_always_on_not_supported_play)

    val notifPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        notifGranted = granted
        if (granted && a11yEnabled && consented) {
            // All gates satisfied; flip on.
            storage.setAlwaysOnEnabled(true)
            AlwaysOnProxy.start(context)
            enabled = true
        }
    }

    LaunchedEffect(resumeCount) {
        a11yEnabled = isAccessibilityServiceEnabled(context)
        notifGranted = if (Build.VERSION.SDK_INT >= 33) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        } else true
        consented = storage.isReadRespondConsented()
        enabled = storage.isAlwaysOnEnabled()
    }

    AlwaysOnScreen(
        enabled = enabled,
        a11yEnabled = a11yEnabled,
        notifGranted = notifGranted,
        consented = consented,
        supported = AlwaysOnProxy.isSupported(),
        onBack = onBack,
        onToggle = { newValue ->
            // FIRST gate: play flavor cannot run the service. Toast and return
            // before any storage write — otherwise the switch flips on with no
            // running service, persisting alwaysOnEnabled = true falsely.
            if (newValue && !AlwaysOnProxy.isSupported()) {
                Toast.makeText(context, notSupportedMsg, Toast.LENGTH_SHORT).show()
                return@AlwaysOnScreen
            }
            if (!newValue) {
                storage.setAlwaysOnEnabled(false)
                AlwaysOnProxy.stop(context)
                enabled = false
                return@AlwaysOnScreen
            }
            // Trying to flip ON. Walk the gates in order.
            if (!a11yEnabled) {
                context.startActivity(
                    Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                return@AlwaysOnScreen
            }
            if (!consented) {
                context.startActivity(
                    Intent(context, ReadRespondConsentActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                return@AlwaysOnScreen
            }
            if (Build.VERSION.SDK_INT >= 33 && !notifGranted) {
                notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                return@AlwaysOnScreen
            }
            storage.setAlwaysOnEnabled(true)
            AlwaysOnProxy.start(context)
            enabled = true
        },
        onOpenA11ySettings = {
            context.startActivity(
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        },
        onGrantNotif = {
            if (Build.VERSION.SDK_INT >= 33) {
                notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        },
        onReviewConsent = {
            context.startActivity(
                Intent(context, ReadRespondConsentActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        },
    )
}

private fun isAccessibilityServiceEnabled(context: Context): Boolean {
    // Best-effort: query Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES.
    // String-contains check on our service component name is the standard
    // pattern (no public API for "is my service enabled"). The service FQN
    // is hardcoded as a string because src/main/ cannot reference the
    // fdroid-only ScreenReaderService class directly.
    val target = ComponentName(
        context.packageName,
        "com.aikeyboard.app.a11y.ScreenReaderService",
    ).flattenToString()
    val setting = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
    ) ?: return false
    return setting.split(':').any { it == target }
}
