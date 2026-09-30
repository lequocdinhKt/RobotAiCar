package com.one.robotassistant.feature.eyes.model

enum class EyeExpression {
    NORMAL,
    HAPPY,
    SAD,
    CONFUSED,
    DIZZY,
    THINKING,
    LISTENING,
    SPEAKING,
}

data class EyeTarget(
    val horizontal: Float = 0f,
    val vertical: Float = 0f,
) {
    init {
        require(horizontal in -1f..1f) { "horizontal must be normalized to -1..1" }
        require(vertical in -1f..1f) { "vertical must be normalized to -1..1" }
    }

    companion object {
        val Center = EyeTarget()
    }
}

data class EyeState(
    val expression: EyeExpression = EyeExpression.NORMAL,
    val target: EyeTarget = EyeTarget.Center,
    val isBlinking: Boolean = false,
)
