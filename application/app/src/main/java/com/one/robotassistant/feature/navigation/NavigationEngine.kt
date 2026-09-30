package com.one.robotassistant.feature.navigation

import com.one.robotassistant.feature.navigation.model.EnvironmentModel
import com.one.robotassistant.feature.navigation.model.NavigationPath
import com.one.robotassistant.feature.navigation.model.NavigationState
import com.one.robotassistant.feature.navigation.model.NavigationTarget
import kotlinx.coroutines.flow.StateFlow

interface NavigationEngine {
    val state: StateFlow<NavigationState>
    val currentPath: StateFlow<NavigationPath?>

    suspend fun navigateTo(target: NavigationTarget)
    suspend fun updateEnvironment(environment: EnvironmentModel)
    suspend fun stop()
}

// TODO(PHASE_2): replace the debug path with sensor/depth-backed navigation.
