package com.shoosman.bookapp.ui.common

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import kotlin.math.sin
import kotlin.random.Random

private class Piece(val x: Float, val delay: Float, val speed: Float, val drift: Float, val size: Float, val spin: Float, val color: Color)

/** Конфетти поверх экрана. Каждое изменение [trigger] (кроме 0) — новый залп. */
@Composable
fun Confetti(trigger: Int) {
    if (trigger == 0) return
    val colors = listOf(
        MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.primaryContainer,
        Color(0xFFFFC94D), Color(0xFF7FD6C2), Color(0xFFFF8CBF), Color(0xFFB79CFF),
    )
    val pieces = remember(trigger) {
        List(90) {
            Piece(
                x = Random.nextFloat(), delay = Random.nextFloat() * 0.25f, speed = 0.6f + Random.nextFloat() * 0.6f,
                drift = (Random.nextFloat() - 0.5f) * 0.2f, size = 6f + Random.nextFloat() * 10f,
                spin = (Random.nextFloat() - 0.5f) * 1440f, color = colors.random(),
            )
        }
    }
    val t = remember(trigger) { Animatable(0f) }
    var visible by remember(trigger) { mutableStateOf(true) }
    LaunchedEffect(trigger) {
        t.animateTo(1f, tween(2600, easing = LinearEasing))
        visible = false
    }
    if (!visible) return
    Popup(properties = PopupProperties(focusable = false, clippingEnabled = false)) {
        Canvas(Modifier.fillMaxSize()) {
            val p = t.value
            for (c in pieces) {
                val local = ((p - c.delay) / (1f - c.delay)).coerceIn(0f, 1f)
                if (local <= 0f) continue
                val y = -40f + local * c.speed * size.height * 1.3f
                val x = (c.x + c.drift * local + sin(local * 12f + c.x * 10f) * 0.02f) * size.width
                val alpha = if (local > 0.85f) (1f - local) / 0.15f else 1f
                rotate(c.spin * local, Offset(x, y)) {
                    drawRect(c.color.copy(alpha = alpha), Offset(x - c.size / 2, y - c.size / 4), Size(c.size, c.size / 2))
                }
            }
        }
    }
}
