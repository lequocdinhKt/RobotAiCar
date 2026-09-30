package com.one.robotassistant

import com.one.robotassistant.feature.assistant.intent.LocalIntentRouter
import com.one.robotassistant.feature.assistant.intent.OneIntent
import com.one.robotassistant.feature.eyes.model.EyeExpression
import com.one.robotassistant.feature.robot.command.RobotCommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalIntentRouterTest {
    private val router = LocalIntentRouter()

    @Test
    fun routesVietnameseMovementLocally() {
        assertEquals(
            OneIntent.RobotMovement(RobotCommand.TURN_LEFT),
            router.route("Linh rẽ trái đi"),
        )
    }

    @Test
    fun routesTargetNavigationBeforeDirectForward() {
        assertTrue(router.route("tiến lại gần tôi") is OneIntent.Navigation)
    }

    @Test
    fun routesEmotionLocally() {
        assertEquals(
            OneIntent.Emotion(EyeExpression.DIZZY),
            router.route("Linh chóng mặt đi"),
        )
    }

    @Test
    fun leavesNormalQuestionForAi() {
        assertTrue(router.route("Hôm nay thời tiết thế nào?") is OneIntent.Conversation)
    }

}
