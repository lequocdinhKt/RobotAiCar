package com.one.robotassistant.feature.navigation.overlay

import com.one.robotassistant.feature.navigation.model.NavigationPath
import com.one.robotassistant.feature.navigation.model.NavigationTarget
import com.one.robotassistant.feature.navigation.model.Obstacle
import com.one.robotassistant.feature.navigation.model.Point2D

data class NavigationOverlayModel(
    val robotPosition: Point2D,
    val target: NavigationTarget,
    val path: NavigationPath,
    val obstacles: List<Obstacle>,
)
