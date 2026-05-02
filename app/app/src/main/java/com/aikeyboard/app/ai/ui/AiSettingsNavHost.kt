// SPDX-License-Identifier: GPL-3.0-only
package com.aikeyboard.app.ai.ui

import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument

object AiSettingsRoutes {
    const val PERSONAS_LIST = "personas/list"
    const val PERSONAS_EDIT = "personas/edit"
    const val ARG_PERSONA_ID = "personaId"

    fun editRoute(personaId: String? = null): String =
        if (personaId == null) "$PERSONAS_EDIT?$ARG_PERSONA_ID=" else "$PERSONAS_EDIT?$ARG_PERSONA_ID=$personaId"
}

@Composable
fun AiSettingsNavHost() {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = AiSettingsRoutes.PERSONAS_LIST) {
        composable(AiSettingsRoutes.PERSONAS_LIST) {
            PersonaListScreen(
                onCreate = { nav.navigate(AiSettingsRoutes.editRoute(null)) },
                onEdit = { id -> nav.navigate(AiSettingsRoutes.editRoute(id)) }
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
    }
}
