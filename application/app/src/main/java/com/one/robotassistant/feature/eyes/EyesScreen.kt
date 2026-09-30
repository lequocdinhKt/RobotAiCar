package com.one.robotassistant.feature.eyes

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.one.robotassistant.feature.eyes.model.EyeExpression
import com.one.robotassistant.feature.eyes.model.EyeState
import com.one.robotassistant.feature.eyes.model.EyeTarget
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlin.random.Random
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

private val ClockFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
private val DebugExpressions = listOf(
    EyeExpression.NORMAL,
    EyeExpression.HAPPY,
    EyeExpression.SAD,
    EyeExpression.CONFUSED,
    EyeExpression.DIZZY,
)

@Composable
fun EyesRoute(
    viewModel: EyesViewModel,
    modifier: Modifier = Modifier,
) {
    val eyeState by viewModel.eyeState.collectAsStateWithLifecycle()
    val visibleResponse by viewModel.visibleResponse.collectAsStateWithLifecycle()
    val debugControlsVisible by viewModel.debugControlsVisible.collectAsStateWithLifecycle()
    EyesScreen(
        eyeState = eyeState,
        visibleResponse = visibleResponse,
        debugControlsVisible = debugControlsVisible,
        onToggleDebug = viewModel::toggleDebugControls,
        onExpressionSelected = viewModel::showExpression,
        modifier = modifier,
    )
}

@Composable
fun EyesScreen(
    eyeState: EyeState,
    visibleResponse: String,
    debugControlsVisible: Boolean,
    onToggleDebug: () -> Unit,
    onExpressionSelected: (EyeExpression) -> Unit,
    microphoneLabel: String = "MIC ĐANG NGHE",
    statusText: String = "LINH: ĐANG NGHE",
    onMicrophoneRequested: () -> Unit = {},
    onCameraRequested: () -> Unit = {},
    onDebugCommandSubmitted: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var currentTime by remember { mutableStateOf(LocalTime.now()) }
    var controlsVisible by remember { mutableStateOf(false) }
    val hasResponse = visibleResponse.isNotBlank()

    LaunchedEffect(Unit) {
        while (isActive) {
            delay(1_000)
            currentTime = LocalTime.now()
        }
    }
    LaunchedEffect(controlsVisible, debugControlsVisible) {
        if (controlsVisible && !debugControlsVisible) {
            delay(3_500)
            controlsVisible = false
        }
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(hasResponse) {
                detectTapGestures(onTap = { if (!hasResponse) controlsVisible = !controlsVisible })
            },
    ) {
        val normalGroupWidth = minOf(maxWidth - 48.dp, 540.dp)
        val responseGroupWidth = minOf(maxWidth * 0.42f, 240.dp)
        val groupWidth by animateDpAsState(
            targetValue = if (hasResponse) responseGroupWidth else normalGroupWidth,
            animationSpec = tween(520),
            label = "eyeGroupWidth",
        )
        val groupHeight by animateDpAsState(
            targetValue = if (hasResponse) 54.dp else minOf(maxHeight * 0.15f, 108.dp),
            animationSpec = tween(520),
            label = "eyeGroupHeight",
        )
        val baseX = if (hasResponse) 20.dp else (maxWidth - groupWidth) / 2
        val baseY = if (hasResponse) 64.dp else (maxHeight - groupHeight) / 2
        val gazeX = if (hasResponse) 0.dp else 24.dp * eyeState.target.horizontal
        val gazeY = if (hasResponse) 0.dp else 13.dp * eyeState.target.vertical
        val eyesX by animateDpAsState(baseX + gazeX, tween(420), label = "eyesX")
        val eyesY by animateDpAsState(baseY + gazeY, tween(420), label = "eyesY")

        Text(
            text = currentTime.format(ClockFormatter),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 18.dp),
            color = Color.White,
            fontSize = 22.sp,
            fontWeight = FontWeight.Medium,
        )

        Row(
            modifier = Modifier
                .offset(x = eyesX, y = eyesY)
                .width(groupWidth)
                .height(groupHeight),
            horizontalArrangement = Arrangement.spacedBy(if (hasResponse) 14.dp else 24.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RobotEye(
                expression = eyeState.expression,
                isLeftEye = true,
                modifier = Modifier.weight(1f).fillMaxSize(),
            )
            RobotEye(
                expression = eyeState.expression,
                isLeftEye = false,
                modifier = Modifier.weight(1f).fillMaxSize(),
            )
        }

        if (eyeState.expression == EyeExpression.DIZZY) {
            DizzySpinner(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(104.dp),
            )
        }

        AnimatedVisibility(
            visible = hasResponse,
            modifier = Modifier
                .align(Alignment.Center)
                .padding(start = 24.dp, end = 24.dp, top = 130.dp, bottom = 28.dp),
            enter = fadeIn(tween(260)) + scaleIn(tween(360), initialScale = 0.94f),
            exit = fadeOut(tween(180)) + scaleOut(tween(180), targetScale = 0.96f),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.92f)
                    .heightIn(min = 130.dp, max = maxHeight * 0.64f)
                    .border(2.dp, Color.White, RoundedCornerShape(22.dp))
                    .padding(horizontal = 24.dp, vertical = 20.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                Text(
                    text = visibleResponse,
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    color = Color.White,
                    fontSize = 23.sp,
                    lineHeight = 32.sp,
                )
            }
        }

        AnimatedVisibility(
            visible = controlsVisible && !hasResponse,
            modifier = Modifier.fillMaxSize(),
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            Box(Modifier.fillMaxSize().padding(18.dp)) {
                OutlinedButton(
                    onClick = onToggleDebug,
                    modifier = Modifier.align(Alignment.TopStart).size(width = 54.dp, height = 38.dp),
                    contentPadding = PaddingValues(0.dp),
                ) { Text("•••", color = Color.White) }

                OutlinedButton(onClick = onCameraRequested, modifier = Modifier.align(Alignment.TopEnd)) {
                    Text("CAMERA", color = Color.White)
                }

                Column(modifier = Modifier.align(Alignment.BottomStart)) {
                    Text(statusText, color = Color.White, fontSize = 11.sp, maxLines = 2)
                    OutlinedButton(onClick = onMicrophoneRequested) {
                        Text(microphoneLabel, color = Color.White, fontSize = 11.sp)
                    }
                }

                if (debugControlsVisible) {
                    ExpressionDebugControls(
                        selected = eyeState.expression,
                        onSelected = onExpressionSelected,
                        onCommandSubmitted = onDebugCommandSubmitted,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(horizontal = 60.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ExpressionDebugControls(
    selected: EyeExpression,
    onSelected: (EyeExpression) -> Unit,
    onCommandSubmitted: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var command by remember { mutableStateOf("") }
    Column(modifier = modifier.background(Color.Black.copy(alpha = 0.94f)).padding(8.dp)) {
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            DebugExpressions.forEach { expression ->
                OutlinedButton(onClick = { onSelected(expression) }) {
                    Text(
                        expression.name,
                        color = Color.White,
                        fontSize = 10.sp,
                        fontWeight = if (expression == selected) FontWeight.Black else FontWeight.Normal,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = command,
                onValueChange = { command = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                label = { Text("Debug command") },
            )
            OutlinedButton(onClick = {
                if (command.isNotBlank()) {
                    onCommandSubmitted(command)
                    command = ""
                }
            }) { Text("GỬI", color = Color.White) }
        }
    }
}

@Composable
private fun RobotEye(
    expression: EyeExpression,
    isLeftEye: Boolean,
    modifier: Modifier = Modifier,
) {
    var blinkClosed by remember { mutableStateOf(false) }
    LaunchedEffect(expression) {
        while (isActive) {
            delay(Random.nextLong(2_200L, 4_800L))
            blinkClosed = true
            delay(105)
            blinkClosed = false
        }
    }
    val blinkScale by animateFloatAsState(if (blinkClosed) 0.08f else 1f, tween(90), label = "blink")
    val motion = rememberInfiniteTransition(label = "eyePulse")
    val speakingPulse by motion.animateFloat(
        initialValue = 0.94f,
        targetValue = 1.04f,
        animationSpec = infiniteRepeatable(tween(520), RepeatMode.Reverse),
        label = "speakingPulse",
    )

    Canvas(modifier) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val widthScale = when (expression) {
            EyeExpression.CONFUSED -> if (isLeftEye) 0.72f else 0.96f
            EyeExpression.SPEAKING -> speakingPulse
            else -> 0.92f
        }
        val heightScale = when (expression) {
            EyeExpression.HAPPY -> 0.42f
            EyeExpression.SAD -> 0.64f
            EyeExpression.CONFUSED -> if (isLeftEye) 0.58f else 0.88f
            EyeExpression.DIZZY -> 0.16f
            EyeExpression.THINKING -> if (isLeftEye) 0.72f else 0.92f
            EyeExpression.LISTENING -> 1f
            EyeExpression.SPEAKING -> 0.76f * speakingPulse
            EyeExpression.NORMAL -> 0.82f
        } * blinkScale
        val eyeWidth = size.width * widthScale
        val eyeHeight = (size.height * heightScale).coerceAtLeast(3f)
        val tilt = when (expression) {
            EyeExpression.SAD -> if (isLeftEye) 8f else -8f
            EyeExpression.CONFUSED -> if (isLeftEye) -5f else 5f
            EyeExpression.THINKING -> if (isLeftEye) -3f else 3f
            else -> 0f
        }
        rotate(tilt, center) {
            drawRoundRect(
                color = Color.White,
                topLeft = Offset(center.x - eyeWidth / 2f, center.y - eyeHeight / 2f),
                size = Size(eyeWidth, eyeHeight),
                cornerRadius = CornerRadius(eyeHeight * 0.34f),
            )
        }
    }
}

@Composable
private fun DizzySpinner(modifier: Modifier = Modifier) {
    val motion = rememberInfiniteTransition(label = "dizzySpinner")
    val angle by motion.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(900)),
        label = "dizzyAngle",
    )
    Canvas(modifier) {
        val stroke = size.minDimension * 0.1f
        drawArc(
            color = Color.White,
            startAngle = angle,
            sweepAngle = 245f,
            useCenter = false,
            style = Stroke(width = stroke, cap = StrokeCap.Round),
        )
        drawCircle(
            color = Color.White.copy(alpha = 0.28f),
            radius = size.minDimension * 0.32f,
            style = Stroke(width = stroke * 0.45f),
        )
    }
}
