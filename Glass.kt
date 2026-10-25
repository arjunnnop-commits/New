package com.yooitachiijod.glassfastboot

import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val Ice = Color(0xFF7DD3FC)
val Violet = Color(0xFFA78BFA)
val Pink = Color(0xFFF0ABFC)

@Composable
fun GlassTheme(content: @Composable () -> Unit) =
    MaterialTheme(colorScheme = darkColorScheme(primary = Ice), content = content)

@Composable
fun GlassBackground(content: @Composable BoxScope.() -> Unit) {
    val t = rememberInfiniteTransition(label = "bg")
    val a by t.animateFloat(0f, 1f, infiniteRepeatable(tween(9000, easing = LinearEasing), RepeatMode.Reverse), label = "a")
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF070B1E), Color(0xFF160F3A), Color(0xFF062038))))) {
        Blob(Violet, (-60 + 120 * a).dp, 40.dp, 300.dp)
        Blob(Ice, (180 - 140 * a).dp, 260.dp, 280.dp)
        Blob(Pink, (-40 + 80 * a).dp, 560.dp, 260.dp)
        content()
    }
}

@Composable
private fun Blob(c: Color, x: androidx.compose.ui.unit.Dp, y: androidx.compose.ui.unit.Dp, size: androidx.compose.ui.unit.Dp) {
    Box(
        Modifier.offset(x, y).size(size).blur(70.dp)
            .background(Brush.radialGradient(listOf(c.copy(alpha = .55f), Color.Transparent)), CircleShape)
    )
}

private val glassFill = Brush.linearGradient(listOf(Color.White.copy(.20f), Color.White.copy(.05f)))
private val glassEdge = Brush.linearGradient(listOf(Color.White.copy(.65f), Color.White.copy(.05f), Color.White.copy(.30f)))

@Composable
fun GlassCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(28.dp)
    Column(
        modifier.fillMaxWidth().clip(shape).background(glassFill).border(1.dp, glassEdge, shape).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp), content = content
    )
}

@Composable
fun GlassButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, accent: Boolean = false, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    val fill = if (accent) Brush.linearGradient(listOf(Ice.copy(.55f), Violet.copy(.55f))) else glassFill
    Box(
        modifier.alpha(if (enabled) 1f else .4f).clip(shape).background(fill).border(1.dp, glassEdge, shape)
            .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 18.dp, vertical = 11.dp),
        contentAlignment = Alignment.Center
    ) { Text(text, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium) }
}

@Composable
fun GlassChip(text: String, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Box(
        Modifier.clip(shape)
            .background(if (selected) Brush.linearGradient(listOf(Ice.copy(.5f), Violet.copy(.5f))) else glassFill)
            .border(1.dp, glassEdge, shape).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 7.dp)
    ) { Text(text, color = Color.White, fontSize = 12.sp) }
}

@Composable
fun GlassTabs(labels: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    val shape = RoundedCornerShape(50)
    Row(
        Modifier.fillMaxWidth().clip(shape).background(glassFill).border(1.dp, glassEdge, shape).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        labels.forEachIndexed { i, label ->
            val on = i == selected
            Box(
                Modifier.weight(1f).clip(shape)
                    .background(
                        if (on) Brush.linearGradient(listOf(Ice.copy(.5f), Violet.copy(.5f)))
                        else Brush.linearGradient(listOf(Color.Transparent, Color.Transparent))
                    )
                    .clickable { onSelect(i) }.padding(vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(label, color = Color.White, fontSize = 13.sp, maxLines = 1,
                    fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal)
            }
        }
    }
}
