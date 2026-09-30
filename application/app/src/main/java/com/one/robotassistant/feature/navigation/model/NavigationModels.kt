package com.one.robotassistant.feature.navigation.model

data class Point2D(val x: Float, val y: Float)

sealed interface NavigationTarget {
    data object User : NavigationTarget
    data object CurrentPoint : NavigationTarget
    data class Position(val point: Point2D) : NavigationTarget
}

data class Obstacle(
    val center: Point2D,
    val radiusMeters: Float,
)

data class NavigationPath(
    val points: List<Point2D>,
    val generatedAtMillis: Long,
)

data class EnvironmentModel(
    val robotPosition: Point2D,
    val obstacles: List<Obstacle> = emptyList(),
)

enum class NavigationState {
    IDLE,
    PLANNING,
    NAVIGATING,
    BLOCKED,
    ARRIVED,
    ERROR,
}
