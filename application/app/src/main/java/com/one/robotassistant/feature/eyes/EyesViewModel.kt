package com.one.robotassistant.feature.eyes

import androidx.lifecycle.ViewModel
import com.one.robotassistant.feature.eyes.animation.DefaultEyeAnimationController
import com.one.robotassistant.feature.eyes.animation.EyeAnimationController
import com.one.robotassistant.feature.eyes.model.EyeExpression
import com.one.robotassistant.feature.eyes.model.EyeState
import com.one.robotassistant.feature.eyes.model.EyeTarget
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class EyesViewModel(
    private val controller: EyeAnimationController = DefaultEyeAnimationController(),
) : ViewModel() {
    val eyeState: StateFlow<EyeState> = controller.state

    private val _visibleResponse = MutableStateFlow("")
    val visibleResponse: StateFlow<String> = _visibleResponse.asStateFlow()

    private val _debugControlsVisible = MutableStateFlow(false)
    val debugControlsVisible: StateFlow<Boolean> = _debugControlsVisible.asStateFlow()

    fun showExpression(expression: EyeExpression) {
        controller.show(expression)
    }

    fun lookAt(target: EyeTarget) {
        controller.lookAt(target)
    }

    fun reset() {
        _visibleResponse.value = ""
        controller.reset()
    }

    fun toggleDebugControls() {
        _debugControlsVisible.value = !_debugControlsVisible.value
    }
}
