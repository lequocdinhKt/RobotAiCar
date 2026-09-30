package com.one.robotassistant.feature.navigation.planner

import com.one.robotassistant.feature.navigation.model.EnvironmentModel
import com.one.robotassistant.feature.navigation.model.NavigationPath
import com.one.robotassistant.feature.navigation.model.NavigationTarget

interface PathPlanner {
    suspend fun plan(
        target: NavigationTarget,
        environment: EnvironmentModel,
    ): NavigationPath

    fun isBlocked(
        path: NavigationPath,
        environment: EnvironmentModel,
    ): Boolean
}

// TODO(PHASE_2): replan whenever new depth/object data blocks the active path.
