package com.one.robotassistant.feature.robot.transport

import com.one.robotassistant.feature.robot.command.RobotCommandRequest
import kotlinx.coroutines.flow.StateFlow

interface RobotTransport {
    val connectionState: StateFlow<RobotConnectionState>

    suspend fun connect()
    suspend fun disconnect()
    suspend fun send(command: RobotCommandRequest)
}

enum class RobotConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    ERROR,
}
