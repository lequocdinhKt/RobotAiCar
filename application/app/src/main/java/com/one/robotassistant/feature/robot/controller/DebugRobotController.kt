package com.one.robotassistant.feature.robot.controller

import com.one.robotassistant.core.util.OneLogger
import com.one.robotassistant.feature.robot.command.RobotCommand
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class DebugRobotController(
    private val logger: OneLogger,
) : RobotController {
    private val _lastCommand = MutableStateFlow<RobotCommand?>(null)
    val lastCommand: StateFlow<RobotCommand?> = _lastCommand.asStateFlow()

    override suspend fun forward() = execute(RobotCommand.FORWARD)
    override suspend fun backward() = execute(RobotCommand.BACKWARD)
    override suspend fun turnLeft() = execute(RobotCommand.TURN_LEFT)
    override suspend fun turnRight() = execute(RobotCommand.TURN_RIGHT)
    override suspend fun rotate() = execute(RobotCommand.ROTATE)
    override suspend fun stop() = execute(RobotCommand.STOP)

    private fun execute(command: RobotCommand) {
        _lastCommand.value = command
        logger.debug("ROBOT", "COMMAND=$command STATUS=DEBUG_SIMULATION")
    }
}

suspend fun RobotController.execute(command: RobotCommand) {
    when (command) {
        RobotCommand.FORWARD -> forward()
        RobotCommand.BACKWARD -> backward()
        RobotCommand.TURN_LEFT -> turnLeft()
        RobotCommand.TURN_RIGHT -> turnRight()
        RobotCommand.ROTATE -> rotate()
        RobotCommand.STOP -> stop()
    }
}

fun RobotCommand.spokenConfirmation(): String = when (this) {
    RobotCommand.FORWARD -> "Tôi đang tiến lên."
    RobotCommand.BACKWARD -> "Tôi đang lùi lại."
    RobotCommand.TURN_LEFT -> "Tôi đang rẽ trái."
    RobotCommand.TURN_RIGHT -> "Tôi đang rẽ phải."
    RobotCommand.ROTATE -> "Tôi đang xoay vòng."
    RobotCommand.STOP -> "Tôi đã dừng lại."
}
