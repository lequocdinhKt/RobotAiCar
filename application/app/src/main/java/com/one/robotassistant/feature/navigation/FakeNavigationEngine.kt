package com.one.robotassistant.feature.navigation

import com.one.robotassistant.feature.navigation.model.EnvironmentModel
import com.one.robotassistant.feature.navigation.model.NavigationPath
import com.one.robotassistant.feature.navigation.model.NavigationState
import com.one.robotassistant.feature.navigation.model.NavigationTarget
import com.one.robotassistant.feature.navigation.model.Point2D
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class FakeNavigationEngine : NavigationEngine {
    private val _state = MutableStateFlow(NavigationState.IDLE)
    override val state: StateFlow<NavigationState> = _state.asStateFlow()

    private val _currentPath = MutableStateFlow<NavigationPath?>(null)
    override val currentPath: StateFlow<NavigationPath?> = _currentPath.asStateFlow()

    private var environment = EnvironmentModel(robotPosition = Point2D(0.5f, 0.9f))

    override suspend fun navigateTo(target: NavigationTarget) {
        _state.value = NavigationState.PLANNING
        val targetPoint = when (target) {
            NavigationTarget.CurrentPoint -> environment.robotPosition
            NavigationTarget.User -> Point2D(0.5f, 0.15f)
            is NavigationTarget.Position -> target.point
        }
        _currentPath.value = NavigationPath(
            points = listOf(environment.robotPosition, targetPoint),
            generatedAtMillis = System.currentTimeMillis(),
        )
        _state.value = NavigationState.NAVIGATING
    }

    override suspend fun updateEnvironment(environment: EnvironmentModel) {
        this.environment = environment
    }

    override suspend fun stop() {
        _currentPath.value = null
        _state.value = NavigationState.IDLE
    }
}
