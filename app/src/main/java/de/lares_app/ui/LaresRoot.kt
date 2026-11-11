/*
 * Lares – blocks distracting apps and websites; loosening needs a guardian code.
 * Copyright (C) 2026 Lares contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package de.lares_app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController

private object Routes {
    const val HOME = "home"
    const val ADD_RULE = "add_rule"
}

/** Shows setup until the unlock method is configured, then the main navigation. */
@Composable
fun LaresRoot(main: MainViewModel = hiltViewModel()) {
    val gate by main.gate.collectAsStateWithLifecycle()
    when (val g = gate) {
        AppGate.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        is AppGate.Setup -> SetupScreen(reSetup = g.reSetup)
        AppGate.Home -> {
            val nav = rememberNavController()
            NavHost(nav, startDestination = Routes.HOME) {
                composable(Routes.HOME) { HomeScreen(onAddRule = { nav.navigate(Routes.ADD_RULE) }) }
                composable(Routes.ADD_RULE) { AddRuleScreen(onDone = { nav.popBackStack() }) }
            }
        }
    }
}
