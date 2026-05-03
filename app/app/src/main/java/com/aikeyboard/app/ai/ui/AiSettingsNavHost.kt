// SPDX-License-Identifier: GPL-3.0-only
package com.aikeyboard.app.ai.ui

import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.aikeyboard.app.ai.ui.termux.TermuxBridgeRoute

object AiSettingsRoutes {
    const val HUB = "hub"
    const val PERSONAS_LIST = "personas/list"
    const val PERSONAS_EDIT = "personas/edit"
    const val KEYBOARD_CHROME = "keyboard/chrome"
    const val BACKENDS_LIST = "backends/list"
    const val BACKENDS_EDIT = "backends/edit"
    const val BACKENDS_TERMUX = "backends/termux"
    const val ARG_PERSONA_ID = "personaId"
    const val ARG_PROVIDER = "provider"

    fun editPersonaRoute(personaId: String? = null): String =
        if (personaId == null) "$PERSONAS_EDIT?$ARG_PERSONA_ID=" else "$PERSONAS_EDIT?$ARG_PERSONA_ID=$personaId"

    fun editBackendRoute(providerStorageKey: String): String =
        "$BACKENDS_EDIT/$providerStorageKey"
}

@Composable
fun AiSettingsNavHost() {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = AiSettingsRoutes.HUB) {
        composable(AiSettingsRoutes.HUB) {
            SettingsHubScreen(
                onOpenPersonas = { nav.navigate(AiSettingsRoutes.PERSONAS_LIST) },
                onOpenKeyboardChrome = { nav.navigate(AiSettingsRoutes.KEYBOARD_CHROME) },
                onOpenBackends = { nav.navigate(AiSettingsRoutes.BACKENDS_LIST) },
            )
        }
        composable(AiSettingsRoutes.PERSONAS_LIST) {
            PersonaListScreen(
                onBack = { nav.popBackStack() },
                onCreate = { nav.navigate(AiSettingsRoutes.editPersonaRoute(null)) },
                onEdit = { id -> nav.navigate(AiSettingsRoutes.editPersonaRoute(id)) },
            )
        }
        composable(
            route = "${AiSettingsRoutes.PERSONAS_EDIT}?${AiSettingsRoutes.ARG_PERSONA_ID}={${AiSettingsRoutes.ARG_PERSONA_ID}}",
            arguments = listOf(
                navArgument(AiSettingsRoutes.ARG_PERSONA_ID) {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                }
            )
        ) { backStackEntry ->
            val id = backStackEntry.arguments?.getString(AiSettingsRoutes.ARG_PERSONA_ID)
                ?.takeIf { it.isNotEmpty() }
            PersonaEditScreen(
                personaId = id,
                onDone = { nav.popBackStack() }
            )
        }
        composable(AiSettingsRoutes.KEYBOARD_CHROME) {
            KeyboardChromeScreen(onBack = { nav.popBackStack() })
        }
        composable(AiSettingsRoutes.BACKENDS_LIST) {
            BackendsScreen(
                onBack = { nav.popBackStack() },
                onEditProvider = { provider ->
                    nav.navigate(AiSettingsRoutes.editBackendRoute(provider.storageKey))
                },
                onOpenTermuxBridge = { nav.navigate(AiSettingsRoutes.BACKENDS_TERMUX) },
            )
        }
        composable(AiSettingsRoutes.BACKENDS_TERMUX) {
            TermuxBridgeRoute(onBack = { nav.popBackStack() })
        }
        composable(
            route = "${AiSettingsRoutes.BACKENDS_EDIT}/{${AiSettingsRoutes.ARG_PROVIDER}}",
            arguments = listOf(
                navArgument(AiSettingsRoutes.ARG_PROVIDER) { type = NavType.StringType }
            ),
        ) { backStackEntry ->
            val key = backStackEntry.arguments?.getString(AiSettingsRoutes.ARG_PROVIDER)
            BackendEditScreen(
                providerStorageKey = key,
                onDone = { nav.popBackStack() },
            )
        }
    }
}
