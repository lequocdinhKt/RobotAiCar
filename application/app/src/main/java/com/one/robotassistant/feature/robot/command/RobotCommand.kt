package com.one.robotassistant.feature.robot.command

enum class RobotCommand {
    FORWARD,
    BACKWARD,
    TURN_LEFT,
    TURN_RIGHT,
    ROTATE,
    STOP,
}

data class RobotCommandRequest(
    val command: RobotCommand,
    val speed: Float = DEFAULT_SPEED,
) {
    init {
        require(speed in MIN_SPEED..MAX_SPEED) { "speed must be normalized to 0..1" }
        require(command != RobotCommand.STOP || speed == 0f) { "STOP must use speed 0" }
    }

    companion object {
        const val MIN_SPEED = 0f
        const val MAX_SPEED = 1f
        const val DEFAULT_SPEED = 0.5f

        fun stop() = RobotCommandRequest(RobotCommand.STOP, speed = 0f)
    }
}
