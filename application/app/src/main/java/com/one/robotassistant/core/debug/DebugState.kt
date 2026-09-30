package com.one.robotassistant.core.debug

import com.one.robotassistant.core.model.OneState
import com.one.robotassistant.feature.assistant.intent.OneIntentType
import com.one.robotassistant.feature.eyes.model.EyeExpression
import com.one.robotassistant.feature.robot.command.RobotCommand

data class DebugState(
    val oneState: OneState = OneState.SLEEPING,
    val microphone: ComponentState = ComponentState.OFF,
    val lastSpeech: String? = null,
    val lastIntent: OneIntentType? = null,
    val lastRobotCommand: RobotCommand? = null,
    val ai: AiDebugState = AiDebugState.IDLE,
    val eyes: EyeExpression = EyeExpression.NORMAL,
    val camera: ComponentState = ComponentState.OFF,
    val detector: ComponentState = ComponentState.OFF,
    val navigation: NavigationDebugState = NavigationDebugState.IDLE,
    val esp32: Esp32DebugState = Esp32DebugState.DISCONNECTED,
)

enum class ComponentState { OFF, STARTING, RUNNING, LISTENING, ERROR }
enum class AiDebugState { IDLE, THINKING, ERROR }
enum class NavigationDebugState { IDLE, PLANNING, NAVIGATING, BLOCKED, ERROR }
enum class Esp32DebugState { DISCONNECTED, CONNECTING, CONNECTED, DEBUG, ERROR }
