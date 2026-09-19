package io.github.mangi.eta.ui.app

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.mangi.eta.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun StartupBrandingOverlay(
    contentReady: Boolean,
    onDismissed: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val surface = MiuixTheme.colorScheme.surface
    val dark = 0.299f * surface.red + 0.587f * surface.green + 0.114f * surface.blue < 0.5f
    val background = if (dark) Color(0xFF131314) else Color(0xFFFFFFFF)
    val textAlpha = if (dark) 0.52f else 0.46f

    val overlayAlpha = remember { Animatable(1f) }
    val contentAlpha = remember { Animatable(0f) }
    val contentRise = remember { Animatable(14f) }
    var minDisplayDone by remember { mutableStateOf(false) }
    var dismissed by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        launch {
            delay(120)
            contentAlpha.animateTo(1f, tween(320))
        }
        launch {
            contentRise.animateTo(
                0f,
                spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMediumLow,
                ),
            )
        }
        delay(900)
        minDisplayDone = true
    }

    LaunchedEffect(contentReady, minDisplayDone) {
        if (contentReady && minDisplayDone && !dismissed) {
            dismissed = true
            overlayAlpha.animateTo(0f, tween(340))
            onDismissed()
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .alpha(overlayAlpha.value)
            .background(background),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .alpha(contentAlpha.value)
                .offset(y = contentRise.value.dp),
        ) {
            Image(
                painter = painterResource(R.drawable.ic_launcher_foreground),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(112.dp),
            )
            Spacer(modifier = Modifier.height(14.dp))
            Text(
                text = "Made by Ccat",
                fontSize = 13.sp,
                letterSpacing = 1.2.sp,
                textAlign = TextAlign.Center,
                style = TextStyle(color = MiuixTheme.colorScheme.onSurface),
                modifier = Modifier.alpha(textAlpha),
            )
        }
    }
}
