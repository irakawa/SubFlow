package com.subflow.ui.screens

import android.content.Context
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.subflow.BuildConfig
import com.subflow.R
import com.subflow.data.AppSettings
import com.subflow.ui.theme.CinzelFamily
import com.subflow.ui.theme.SubFlowColors
import kotlin.math.abs
import kotlin.math.sin
import kotlinx.coroutines.launch

// one-time launch tour, gated by a persisted flag
object Onboarding {
    private const val KEY = "onboarding_done"

    fun isDone(context: Context): Boolean =
        context.getSharedPreferences("subflow_prefs", Context.MODE_PRIVATE).getBoolean(KEY, false)

    fun markDone(context: Context) {
        context.getSharedPreferences("subflow_prefs", Context.MODE_PRIVATE)
            .edit().putBoolean(KEY, true).apply()
        // fresh install has nothing to catch up on
        AppSettings.lastSeenVersion = BuildConfig.VERSION_CODE
    }
}

private data class ObPage(val glyph: String, val titleRes: Int, val bodyRes: Int)

private val easeOut = CubicBezierEasing(0.16f, 1f, 0.3f, 1f)

@Composable
fun OnboardingScreen(onFinish: () -> Unit) {
    val context = LocalContext.current
    val pages = listOf(
        ObPage("🎬", R.string.ob1_title, R.string.ob1_body),
        ObPage("🌐", R.string.ob2_title, R.string.ob2_body),
        ObPage("🔑", R.string.ob3_title, R.string.ob3_body)
    )
    val pagerState = rememberPagerState { pages.size }
    val scope = rememberCoroutineScope()
    val time = rememberInfiniteTime()

    val finish = {
        Onboarding.markDone(context)
        onFinish()
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(SubFlowColors.Background)
    ) {
        FlowingBackdrop(time)

        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = finish) {
                    Text(stringResource(R.string.ob_skip), color = SubFlowColors.TextSecondary)
                }
            }

            HorizontalPager(state = pagerState, modifier = Modifier.weight(1f)) { page ->
                val offset = (pagerState.currentPage - page) + pagerState.currentPageOffsetFraction
                OnboardingPage(pages[page], offset, time)
            }

            PageIndicator(count = pages.size, current = pagerState.currentPage)

            Box(Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 28.dp), contentAlignment = Alignment.Center) {
                val last = pagerState.currentPage == pages.size - 1
                PressableButton(
                    text = stringResource(if (last) R.string.ob_start else R.string.ob_next),
                    onClick = {
                        if (last) finish()
                        else scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
                    }
                )
            }
        }
    }
}

@Composable
private fun OnboardingPage(page: ObPage, offset: Float, time: () -> Float) {
    // fade + parallax as the page slides through
    val visibility = (1f - abs(offset)).coerceIn(0f, 1f)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 36.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        HeroGlyph(
            time = time,
            modifier = Modifier
                .size(width = 220.dp, height = 132.dp)
                .graphicsLayer {
                    alpha = visibility
                    translationX = offset * size.width * 0.35f
                }
        )
        Spacer(Modifier.height(36.dp))

        Text(page.glyph, fontSize = 30.sp, modifier = Modifier.graphicsLayer { alpha = visibility })
        Spacer(Modifier.height(14.dp))
        ShimmerWordmark(time, Modifier.graphicsLayer { alpha = visibility })
        Spacer(Modifier.height(22.dp))

        // text parallax a bit faster than the hero for depth
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.graphicsLayer {
                alpha = visibility
                translationX = offset * size.width * 0.6f
            }
        ) {
            Text(
                stringResource(page.titleRes),
                style = MaterialTheme.typography.headlineMedium,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(14.dp))
            Text(
                stringResource(page.bodyRes),
                style = MaterialTheme.typography.bodyMedium,
                color = SubFlowColors.TextSecondary,
                textAlign = TextAlign.Center,
                lineHeight = 22.sp
            )
        }
    }
}

// faint subtitle bars drifting across the backdrop
@Composable
private fun FlowingBackdrop(time: () -> Float) {
    val accent = SubFlowColors.Accent
    // fixed params so the field is stable across recompositions
    val lines = remember {
        List(14) { i ->
            Triple(
                (i * 61 % 100) / 100f,        // y fraction
                0.3f + (i * 37 % 70) / 100f,  // drift speed
                0.25f + (i * 23 % 50) / 100f  // width fraction
            )
        }
    }
    Canvas(Modifier.fillMaxSize()) {
        val t = time()
        for ((yf, speed, wf) in lines) {
            val y = yf * size.height
            val lineW = wf * size.width * 0.5f
            val travel = (t * speed) % 1f
            val x = size.width - travel * (size.width + lineW)
            val alpha = 0.04f + 0.05f * ((sin(t * 6.283f * speed + yf * 10f) + 1f) / 2f)
            drawLine(
                color = accent.copy(alpha = alpha),
                start = Offset(x, y),
                end = Offset(x + lineW, y),
                strokeWidth = 3.dp.toPx(),
                cap = StrokeCap.Round
            )
        }
    }
}

// waveform settling into three subtitle lines with a sweeping scan highlight
@Composable
private fun HeroGlyph(time: () -> Float, modifier: Modifier = Modifier) {
    val accent = SubFlowColors.Accent
    val primary = SubFlowColors.TextPrimary
    Canvas(modifier) {
        val t = time()
        val w = size.width
        val h = size.height
        val cx = w / 2f
        val barCount = 7
        val barGap = 13.dp.toPx()
        val barW = 6.dp.toPx()
        val waveTop = h * 0.30f

        // each bar phase-shifted
        val totalBarSpan = (barCount - 1) * barGap
        for (i in 0 until barCount) {
            val phase = t * 6.283f + i * 0.7f
            val amp = 0.35f + 0.65f * ((sin(phase) + 1f) / 2f)
            val barH = waveTop * amp
            val x = cx - totalBarSpan / 2f + i * barGap
            drawLine(
                color = accent.copy(alpha = 0.85f),
                start = Offset(x, waveTop - barH / 2f),
                end = Offset(x, waveTop + barH / 2f),
                strokeWidth = barW,
                cap = StrokeCap.Round
            )
        }

        // three subtitle lines
        val lineY = floatArrayOf(h * 0.66f, h * 0.80f, h * 0.94f)
        val left = cx - w * 0.30f
        val lineStroke = 7.dp.toPx()
        drawLine(accent, Offset(left, lineY[0]), Offset(left + w * 0.34f, lineY[0]), lineStroke, StrokeCap.Round)
        drawLine(primary, Offset(left, lineY[1]), Offset(left + w * 0.60f, lineY[1]), lineStroke, StrokeCap.Round)
        val flowEnd = left + w * 0.46f
        drawLine(accent.copy(alpha = 0.9f), Offset(left, lineY[2]), Offset(flowEnd, lineY[2]), lineStroke, StrokeCap.Round)

        // scan highlight sweeping across
        val scanX = left + ((t * 1.4f) % 1f) * (w * 0.62f)
        drawLine(
            brush = Brush.verticalGradient(
                colors = listOf(Color.Transparent, primary.copy(alpha = 0.5f), Color.Transparent),
                startY = lineY[0] - 12.dp.toPx(),
                endY = lineY[2] + 12.dp.toPx()
            ),
            start = Offset(scanX, lineY[0] - 12.dp.toPx()),
            end = Offset(scanX, lineY[2] + 12.dp.toPx()),
            strokeWidth = 2.dp.toPx()
        )
    }
}

// wordmark with a highlight band sweeping across the letters
@Composable
private fun ShimmerWordmark(time: () -> Float, modifier: Modifier = Modifier) {
    val accent = SubFlowColors.Accent
    val band = 520f
    val x = (time() % 1f) * band * 3f - band
    val brush = Brush.linearGradient(
        colors = listOf(accent.copy(alpha = 0.55f), SubFlowColors.TextPrimary, accent.copy(alpha = 0.55f)),
        start = Offset(x, 0f),
        end = Offset(x + band, 0f)
    )
    Text(
        text = "SUBFLOW",
        modifier = modifier,
        style = TextStyle(
            brush = brush,
            fontFamily = CinzelFamily,
            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
            fontSize = 30.sp,
            letterSpacing = 6.sp
        )
    )
}

// active dot stretches into a pill
@Composable
private fun PageIndicator(count: Int, current: Int) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
        repeat(count) { i ->
            val active = i == current
            val w by animateDpAsState(if (active) 22.dp else 7.dp, tween(300, easing = easeOut), label = "dotW")
            Box(
                Modifier
                    .padding(horizontal = 4.dp)
                    .size(width = w, height = 7.dp)
                    .clip(CircleShape)
                    .background(if (active) SubFlowColors.Accent else SubFlowColors.Border)
            )
        }
    }
}

// shared 0..1 clock, returned as a lambda so callers read it in draw scope
@Composable
private fun rememberInfiniteTime(): () -> Float {
    val transition = rememberInfiniteTransition(label = "clock")
    val t = transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(4200, easing = LinearEasing), RepeatMode.Restart),
        label = "clock"
    )
    return { t.value }
}
