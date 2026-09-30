package com.one.robotassistant.feature.eyes.animation

import com.one.robotassistant.feature.eyes.model.EyeExpression
import com.one.robotassistant.feature.eyes.model.EyeState
import com.one.robotassistant.feature.eyes.model.EyeTarget
import kotlinx.coroutines.flow.StateFlow

interface EyeAnimationController {
    val state: StateFlow<EyeState>

    fun show(expression: EyeExpression)
    fun lookAt(target: EyeTarget)
    fun reset()
}
