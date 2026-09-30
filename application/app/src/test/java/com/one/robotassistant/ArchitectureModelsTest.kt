package com.one.robotassistant

import com.one.robotassistant.feature.camera.detection.NormalizedRect
import com.one.robotassistant.feature.eyes.animation.DefaultEyeAnimationController
import com.one.robotassistant.feature.eyes.model.EyeExpression
import com.one.robotassistant.feature.robot.command.RobotCommand
import com.one.robotassistant.feature.robot.command.RobotCommandRequest
import com.one.robotassistant.feature.robot.transport.FakeRobotTransport
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ArchitectureModelsTest {
    @Test
    fun normalizedRectRejectsCoordinatesOutsideFrame() {
        assertThrows(IllegalArgumentException::class.java) {
            NormalizedRect(left = -0.1f, top = 0f, right = 1f, bottom = 1f)
        }
    }

    @Test
    fun stopCommandAlwaysHasZeroSpeed() {
        assertEquals(0f, RobotCommandRequest.stop().speed)
        assertThrows(IllegalArgumentException::class.java) {
            RobotCommandRequest(RobotCommand.STOP, speed = 0.5f)
        }
    }

    @Test
    fun fakeTransportRecordsOnlyConnectedCommands() = runTest {
        val transport = FakeRobotTransport()
        val command = RobotCommandRequest(RobotCommand.FORWARD)

        var rejectedWhileDisconnected = false
        try {
            transport.send(command)
        } catch (_: IllegalStateException) {
            rejectedWhileDisconnected = true
        }
        assertTrue(rejectedWhileDisconnected)
        transport.connect()
        transport.send(command)

        assertEquals(listOf(command), transport.sentCommands)
    }

    @Test
    fun speakingExpressionLooksDownTowardResponse() {
        val controller = DefaultEyeAnimationController()

        controller.show(EyeExpression.SPEAKING)

        assertEquals(EyeExpression.SPEAKING, controller.state.value.expression)
        assertTrue(controller.state.value.target.vertical > 0.5f)
    }
}
