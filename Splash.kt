package com.yooitachiijod.glassfastboot

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/** Intro screen: glowing "FairyTeam", then the app fades in. */
@Composable
fun Root(vm: FlashViewModel) {
    var splash by rememberSaveable { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        delay(2800)
        splash = false
    }
    Crossfade(targetState = splash, animationSpec = tween(700), label = "root") { showSplash ->
        if (showSplash) FairySplash() else App(vm)
    }
}

@Composable
fun FairySplash() {
    var started by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { started = true }
    val appear by animateFloatAsState(if (started) 1f else 0f, tween(1000), label = "appear")

    val t = rememberInfiniteTransition(label = "glow")
    val pulse by t.animateFloat(
        0f, 1f,
        infiniteRepeatable(tween(1200, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "pulse"
    )

    Box(
        Modifier.fillMaxSize().background(
            Brush.verticalGradient(listOf(Color(0xFF070B1E), Color(0xFF160F3A), Color(0xFF062038)))
        ),
        contentAlignment = Alignment.Center
    ) {
        // soft halo behind the text
        Box(
            Modifier.size(340.dp).alpha(appear * (0.35f + 0.35f * pulse))
                .background(Brush.radialGradient(listOf(Violet.copy(alpha = .6f), Color.Transparent)), CircleShape)
        )
        // wide violet glow layer
        Text(
            "FairyTeam",
            modifier = Modifier.alpha(appear),
            style = TextStyle(
                color = Color.White, fontSize = 46.sp, fontWeight = FontWeight.Bold, letterSpacing = 3.sp,
                shadow = Shadow(Violet.copy(alpha = 0.5f + 0.5f * pulse), Offset.Zero, 40f + 50f * pulse)
            )
        )
        // crisp gradient text with a cyan glow on top
        Text(
            "FairyTeam",
            modifier = Modifier.alpha(appear),
            style = TextStyle(
                brush = Brush.linearGradient(listOf(Ice, Violet, Pink)),
                fontSize = 46.sp, fontWeight = FontWeight.Bold, letterSpacing = 3.sp,
                shadow = Shadow(Ice.copy(alpha = 0.6f + 0.4f * pulse), Offset.Zero, 14f + 18f * pulse)
            )
        )
    }
}
