package com.one.robotassistant.feature.robot.transport

import com.one.robotassistant.feature.robot.command.RobotCommandRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class FakeRobotTransport : RobotTransport {
    private val _connectionState = MutableStateFlow(RobotConnectionState.DISCONNECTED)
    override val connectionState: StateFlow<RobotConnectionState> = _connectionState.asStateFlow()

    val sentCommands = mutableListOf<RobotCommandRequest>()

    override suspend fun connect() {
        _connectionState.value = RobotConnectionState.CONNECTED
    }

    override suspend fun disconnect() {
        _connectionState.value = RobotConnectionState.DISCONNECTED
    }

    override suspend fun send(command: RobotCommandRequest) {
        check(connectionState.value == RobotConnectionState.CONNECTED) {
            "Fake transport must be connected before sending a command"
        }
        sentCommands += command
    }
}
