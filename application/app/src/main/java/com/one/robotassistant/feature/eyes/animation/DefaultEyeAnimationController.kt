package com.one.robotassistant.feature.eyes.animation

import com.one.robotassistant.feature.eyes.model.EyeExpression
import com.one.robotassistant.feature.eyes.model.EyeState
import com.one.robotassistant.feature.eyes.model.EyeTarget
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class DefaultEyeAnimationController : EyeAnimationController {
    private val _state = MutableStateFlow(EyeState())
    override val state: StateFlow<EyeState> = _state.asStateFlow()

    override fun show(expression: EyeExpression) {
        _state.update { current ->
            current.copy(
                expression = expression,
                target = expression.defaultTarget(),
            )
        }
    }

    override fun lookAt(target: EyeTarget) {
        _state.update { current -> current.copy(target = target) }
    }

    override fun reset() {
        _state.value = EyeState()
    }
}

private fun EyeExpression.defaultTarget(): EyeTarget = when (this) {
    EyeExpression.THINKING -> EyeTarget(horizontal = 0.35f, vertical = -0.25f)
    EyeExpression.SPEAKING -> EyeTarget(horizontal = 0.22f, vertical = 0.72f)
    else -> EyeTarget.Center
}
