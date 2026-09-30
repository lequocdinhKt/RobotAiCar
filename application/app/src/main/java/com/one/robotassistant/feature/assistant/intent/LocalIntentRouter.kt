package com.one.robotassistant.feature.assistant.intent

import com.one.robotassistant.feature.eyes.model.EyeExpression
import com.one.robotassistant.feature.navigation.model.NavigationTarget
import com.one.robotassistant.feature.robot.command.RobotCommand
import java.text.Normalizer
import java.util.Locale

class LocalIntentRouter : IntentRouter {
    override fun route(text: String): OneIntent {
        val command = normalizeVietnamese(text)

        if (command.containsAny(
                "tien lai gan toi",
                "lai gan toi",
                "chay toi day",
                "di toi vi tri nay",
                "toi day",
            )
        ) {
            return OneIntent.Navigation(NavigationTarget.User)
        }

        emotion(command)?.let { return OneIntent.Emotion(it) }
        movement(command)?.let { return OneIntent.RobotMovement(it) }
        return OneIntent.Conversation(text.trim())
    }

    private fun emotion(command: String): EyeExpression? = when {
        command.containsAny("binh thuong lai", "tro lai binh thuong") -> EyeExpression.NORMAL
        command.containsAny("vui len", "vui di") -> EyeExpression.HAPPY
        command.containsAny("buon di", "buon len") -> EyeExpression.SAD
        command.containsAny("thac mac", "boi roi") -> EyeExpression.CONFUSED
        command.containsAny("chong mat", "quay cuong") -> EyeExpression.DIZZY
        else -> null
    }

    private fun movement(command: String): RobotCommand? = when {
        command.isClearCommand("dung", "dung lai", "stop") -> RobotCommand.STOP
        command.isClearCommand("lui", "lui lai", "di lui") -> RobotCommand.BACKWARD
        command.isClearCommand("re trai", "quay trai", "trai") -> RobotCommand.TURN_LEFT
        command.isClearCommand("re phai", "quay phai", "phai") -> RobotCommand.TURN_RIGHT
        command.isClearCommand("xoay", "xoay vong", "quay vong") -> RobotCommand.ROTATE
        command.isClearCommand("tien", "tien len", "di tien") -> RobotCommand.FORWARD
        else -> null
    }
}

fun normalizeVietnamese(value: String): String = Normalizer.normalize(
    value.lowercase(Locale.forLanguageTag("vi-VN")),
    Normalizer.Form.NFD,
)
    .replace("đ", "d")
    .replace(Regex("\\p{M}+"), "")
    .replace(Regex("[^a-z0-9 ]"), " ")
    .replace(Regex("\\s+"), " ")
    .trim()

private fun String.containsAny(vararg phrases: String): Boolean = phrases.any(::contains)

private fun String.isClearCommand(vararg commands: String): Boolean = commands.any { command ->
    this == command ||
        startsWith("linh $command") ||
        startsWith("one $command") ||
        startsWith("$command di") ||
        startsWith("$command nao")
}
