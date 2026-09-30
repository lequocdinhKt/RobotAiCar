package com.one.robotassistant.feature.assistant.intent

import com.one.robotassistant.feature.eyes.model.EyeExpression
import com.one.robotassistant.feature.navigation.model.NavigationTarget
import com.one.robotassistant.feature.robot.command.RobotCommand

sealed interface OneIntent {
    data class Conversation(val text: String) : OneIntent
    data class RobotMovement(val command: RobotCommand) : OneIntent
    data class Emotion(val expression: EyeExpression) : OneIntent
    data class Navigation(val target: NavigationTarget) : OneIntent
}

enum class OneIntentType {
    CONVERSATION,
    ROBOT_MOVEMENT,
    EMOTION,
    NAVIGATION,
}

val OneIntent.type: OneIntentType
    get() = when (this) {
        is OneIntent.Conversation -> OneIntentType.CONVERSATION
        is OneIntent.RobotMovement -> OneIntentType.ROBOT_MOVEMENT
        is OneIntent.Emotion -> OneIntentType.EMOTION
        is OneIntent.Navigation -> OneIntentType.NAVIGATION
    }

interface IntentRouter {
    fun route(text: String): OneIntent
}
