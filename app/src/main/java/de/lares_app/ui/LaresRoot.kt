/*
 * Lares – blocks distracting apps and websites; loosening needs a guardian code.
 * Copyright (C) 2026 Lares contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package de.lares_app.ui

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument

private const val TRANSITION_MS = 250

private object Routes {
    const val HOME = "home"
    private const val SETTINGS_BASE = "settings"
    const val ARG_HIGHLIGHT = SettingsViewModel.ARG_HIGHLIGHT
    const val SETTINGS = "$SETTINGS_BASE?$ARG_HIGHLIGHT={$ARG_HIGHLIGHT}"

    fun settings(highlight: SettingsHighlight? = null) =
        if (highlight == null) SETTINGS_BASE else "$SETTINGS_BASE?$ARG_HIGHLIGHT=${highlight.name}"
    const val CHANGE_CODE = "change_code"
    const val PRIVACY = "privacy"
    const val LIBRARIES = "libraries"
    const val LICENSE = "license"
    private const val CHANNEL_BASE = "channel"
    const val CHANNEL = "$CHANNEL_BASE?${ChannelEditorViewModel.ARG_CHANNEL_ID}={${ChannelEditorViewModel.ARG_CHANNEL_ID}}"

    fun channel(id: String?) = if (id == null) CHANNEL_BASE else "$CHANNEL_BASE?${ChannelEditorViewModel.ARG_CHANNEL_ID}=$id"
    private const val ADD_RULE_BASE = "add_rule"
    const val ADD_RULE = "$ADD_RULE_BASE?${AddRuleViewModel.ARG_GROUP_ID}={${AddRuleViewModel.ARG_GROUP_ID}}"

    fun addRule(groupId: Long? = null) =
        if (groupId == null) ADD_RULE_BASE else "$ADD_RULE_BASE?${AddRuleViewModel.ARG_GROUP_ID}=$groupId"
}

/** Shows setup until the unlock method is configured, then the main navigation. */
@Composable
fun LaresRoot(main: MainViewModel = hiltViewModel()) {
    val gate by main.gate.collectAsStateWithLifecycle()
    when (val g = gate) {
        AppGate.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        is AppGate.Setup -> SetupScreen(mode = g.mode)
        AppGate.Home -> {
            val nav = rememberNavController()
            NavHost(
                nav,
                startDestination = Routes.HOME,
                // Short, subtle transitions (the library default is a 700 ms cross-fade, which feels laggy).
                enterTransition = { slideInHorizontally(tween(TRANSITION_MS)) { it / 10 } + fadeIn(tween(TRANSITION_MS)) },
                exitTransition = { fadeOut(tween(TRANSITION_MS / 2)) },
                popEnterTransition = { fadeIn(tween(TRANSITION_MS)) },
                popExitTransition = { slideOutHorizontally(tween(TRANSITION_MS)) { it / 10 } + fadeOut(tween(TRANSITION_MS)) },
            ) {
                composable(Routes.HOME) {
                    HomeScreen(
                        onAddRule = { groupId -> nav.navigate(Routes.addRule(groupId)) },
                        onOpenSettings = { highlight -> nav.navigate(Routes.settings(highlight)) },
                    )
                }
                composable(
                    Routes.SETTINGS,
                    arguments = listOf(
                        navArgument(Routes.ARG_HIGHLIGHT) {
                            type = NavType.StringType
                            defaultValue = ""
                        },
                    ),
                ) {
                    // The highlight argument is read by SettingsViewModel (SavedStateHandle).
                    SettingsScreen(
                        onBack = { nav.popBackStack() },
                        onChangeCode = { nav.navigate(Routes.CHANGE_CODE) },
                        onOpenChannel = { id -> nav.navigate(Routes.channel(id)) },
                        onOpenPrivacy = { nav.navigate(Routes.PRIVACY) },
                        onOpenLicense = { nav.navigate(Routes.LICENSE) },
                        onOpenLibraries = { nav.navigate(Routes.LIBRARIES) },
                    )
                }
                composable(Routes.CHANGE_CODE) {
                    SetupScreen(
                        mode = SetupMode.CHANGE,
                        onDone = { nav.popBackStack() },
                        onCancel = { nav.popBackStack() },
                    )
                }
                composable(Routes.PRIVACY) { PrivacyScreen(onBack = { nav.popBackStack() }) }
                composable(Routes.LIBRARIES) { LibrariesScreen(onBack = { nav.popBackStack() }) }
                composable(Routes.LICENSE) { LicenseTextScreen(onBack = { nav.popBackStack() }) }
                composable(
                    Routes.CHANNEL,
                    arguments = listOf(
                        navArgument(ChannelEditorViewModel.ARG_CHANNEL_ID) {
                            type = NavType.StringType
                            defaultValue = ""
                        },
                    ),
                ) { ChannelEditorScreen(onDone = { nav.popBackStack() }) }
                composable(
                    Routes.ADD_RULE,
                    arguments = listOf(
                        navArgument(AddRuleViewModel.ARG_GROUP_ID) {
                            type = NavType.LongType
                            defaultValue = -1L
                        },
                    ),
                ) {
                    AddRuleScreen(
                        onDone = { nav.popBackStack() },
                        onGroupCreated = { groupId ->
                            // Replace this screen with "add to <new group>".
                            nav.navigate(Routes.addRule(groupId)) { popUpTo(Routes.HOME) }
                        },
                    )
                }
            }
        }
    }
}
