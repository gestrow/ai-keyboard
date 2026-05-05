// SPDX-License-Identifier: GPL-3.0-only
package com.aikeyboard.app.ai.ui

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

class AiSettingsActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Phase 8: tile taps deep-link directly into the always-on screen by
        // passing the route name as an extra. Falls back to the hub when
        // launched normally (gear icon, app launcher).
        val startRoute = intent.getStringExtra(EXTRA_DEEP_LINK_ROUTE)
            ?: AiSettingsRoutes.HUB
        setContent {
            AiSettingsTheme {
                Surface(color = MaterialTheme.colorScheme.background) {
                    AiSettingsNavHost(startDestination = startRoute)
                }
            }
        }
    }

    companion object {
        const val EXTRA_DEEP_LINK_ROUTE = "deep_link_route"
    }
}

@Composable
private fun AiSettingsTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val colors = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else {
        if (dark) darkColorScheme() else lightColorScheme()
    }
    MaterialTheme(colorScheme = colors, content = content)
}
