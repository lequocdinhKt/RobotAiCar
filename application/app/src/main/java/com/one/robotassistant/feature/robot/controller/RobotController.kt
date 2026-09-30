package com.one.robotassistant.feature.robot.controller

interface RobotController {
    suspend fun forward()
    suspend fun backward()
    suspend fun turnLeft()
    suspend fun turnRight()
    suspend fun rotate()
    suspend fun stop()
}
