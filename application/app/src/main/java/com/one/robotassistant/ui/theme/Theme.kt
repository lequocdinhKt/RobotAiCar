package com.one.robotassistant.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val OneColorScheme = darkColorScheme(
    primary = Color.White,
    onPrimary = Color.Black,
    background = Color.Black,
    onBackground = Color.White,
    surface = Color.Black,
    onSurface = Color.White,
    outline = Color.White,
)

@Composable
fun OneTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = OneColorScheme,
        typography = Typography(),
        content = content,
    )
}
