package io.github.sebbejohansson.yggdrasil.boot

import android.content.Context
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import io.github.sebbejohansson.yggdrasil.ui.HudFonts
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.suspendCancellableCoroutine
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.coroutines.resume

// The desktop's timings (Boot.vue).
private const val LINE_MS = 190L
private const val HOLD_MS = 2_600L
private const val QUICK_LINE_MS = 28L
private const val QUICK_HOLD_MS = 650L

private val GLOW = Color(0xFF9CF0FF)
private val TEXT = Color(0xFFD7F8FF)
private val MUTED = Color(0xFF5F8B98)
private val MAGENTA = Color(0xFFE06CF0)
private val ORANGE = Color(0xFFF2A541)
private val CYAN = Color(0xFF4FD8F0)

private fun LineStatus.color() = when (this) {
    LineStatus.ONLINE -> Color(0xFF8FF0C8)
    LineStatus.DEGRADED -> ORANGE
    LineStatus.OFFLINE -> MAGENTA
    LineStatus.NO_SIGNAL -> MUTED
}

private enum class Phase { WAITING, RUNNING, DONE, CLOSING }

/**
 * The boot overlay. [BootKind.FULL] waits for a tap, then brings each line
 * online and speaks; any tap after that ends it. [BootKind.QUICK] runs in
 * about a second, see-through, and lets every touch through to the app.
 */
@Composable
fun BootOverlay(
    kind: BootKind,
    lines: List<BootLine>,
    speech: String,
    voice: Boolean,
    onStarted: () -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val still = remember {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
    val quick = kind == BootKind.QUICK
    var phase by remember { mutableStateOf(if (quick) Phase.RUNNING else Phase.WAITING) }
    var shown by remember { mutableIntStateOf(0) }
    val close by rememberUpdatedState(onClose)
    val alpha by animateFloatAsState(if (phase == Phase.CLOSING) 0f else 1f, tween(500), label = "boot-fade") {
        if (phase == Phase.CLOSING) close()
    }
    val speaker = remember { if (voice && !quick) BootVoice(context) else null }
    DisposableEffect(speaker) { onDispose { speaker?.release() } }

    LaunchedEffect(phase) {
        when (phase) {
            Phase.RUNNING -> {
                val step = if (still) 0L else if (quick) QUICK_LINE_MS else LINE_MS
                while (shown < lines.size) {
                    delay(step)
                    shown++
                }
                delay(step)
                phase = Phase.DONE
            }
            Phase.DONE -> {
                // As on the desktop: say it, then hold the verdict a moment.
                speaker?.let { withTimeoutOrNull(20_000) { it.speakAndWait(speech) } }
                delay(if (quick) QUICK_HOLD_MS else HOLD_MS)
                phase = Phase.CLOSING
            }
            else -> Unit
        }
    }

    val tap = Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
        when (phase) {
            Phase.WAITING -> {
                onStarted()
                phase = Phase.RUNNING
            }
            Phase.RUNNING, Phase.DONE -> {
                speaker?.stop()
                phase = Phase.CLOSING
            }
            Phase.CLOSING -> Unit
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .alpha(alpha)
            .background(
                Brush.radialGradient(
                    0f to Color(8, 30, 40).copy(alpha = if (quick) 0.72f else 0.94f),
                    1f to Color(3, 9, 13).copy(alpha = if (quick) 0.80f else 0.97f),
                ),
            )
            .then(if (quick) Modifier else tap)
            .semantics { contentDescription = "Yggdrasil boot sequence" },
        contentAlignment = Alignment.Center,
    ) {
        Scanlines(still)
        Rings(still, quick)
        if (phase == Phase.WAITING) {
            Prompt(still, onSkip = {
                onStarted()
                phase = Phase.CLOSING
            })
        } else {
            Hud(lines.take(shown), if (phase != Phase.RUNNING) BootLogic.verdict(lines) else null)
        }
    }
}

@Composable
private fun Scanlines(still: Boolean) {
    val sweep = rememberInfiniteTransition(label = "sweep")
    val y by sweep.animateFloat(0f, 1f, infiniteRepeatable(tween(3_200, easing = LinearEasing)), label = "sweep-y")
    Canvas(Modifier.fillMaxSize()) {
        val step = 3.dp.toPx()
        var line = 0f
        while (line < size.height) {
            drawRect(CYAN.copy(alpha = 0.04f), Offset(0f, line), Size(size.width, 1.dp.toPx()))
            line += step
        }
        if (!still) {
            val band = 120.dp.toPx()
            val top = -band + (size.height + band) * y
            drawRect(
                Brush.verticalGradient(0f to Color.Transparent, 0.5f to CYAN.copy(alpha = 0.10f), 1f to Color.Transparent, startY = top, endY = top + band),
                Offset(0f, top), Size(size.width, band),
            )
        }
    }
}

@Composable
private fun Rings(still: Boolean, quick: Boolean) {
    val spin = rememberInfiniteTransition(label = "rings")
    val base = if (quick) 2_000 else 9_000
    val a by spin.animateFloat(0f, 360f, infiniteRepeatable(tween(base, easing = LinearEasing)), label = "r1")
    val b by spin.animateFloat(360f, 0f, infiniteRepeatable(tween(base * 14 / 9, easing = LinearEasing)), label = "r2")
    val c by spin.animateFloat(0f, 360f, infiniteRepeatable(tween(base * 6 / 9, easing = LinearEasing)), label = "r3")
    Canvas(Modifier.fillMaxSize()) {
        val d = size.minDimension * 0.85f
        val center = Offset(size.width / 2, size.height / 2)
        val specs = listOf(
            Triple(1f, if (still) 0f else a, CYAN.copy(alpha = 0.55f)),
            Triple(0.84f, if (still) 0f else b, MAGENTA.copy(alpha = 0.45f)),
            Triple(0.64f, if (still) 0f else c, ORANGE.copy(alpha = 0.45f)),
        )
        specs.forEachIndexed { i, (k, angle, accent) ->
            val r = d / 2 * k
            val topLeft = Offset(center.x - r, center.y - r)
            val stroke = 1.dp.toPx()
            val dashed = if (i == 1) PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx())) else null
            drawCircle(CYAN.copy(alpha = 0.18f), r, center, style = Stroke(stroke, pathEffect = dashed))
            rotate(angle, center) {
                drawArc(accent, -135f, 90f, false, topLeft, Size(2 * r, 2 * r), style = Stroke(stroke * 1.5f, pathEffect = dashed))
            }
        }
    }
}

@Composable
private fun Prompt(still: Boolean, onSkip: () -> Unit) {
    val blink = rememberInfiniteTransition(label = "blink")
    val a by blink.animateFloat(1f, 0.35f, infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "press")
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            "YGGDRASIL",
            style = androidx.compose.ui.text.TextStyle(
                fontFamily = HudFonts.display, fontWeight = FontWeight.Bold, fontSize = 40.sp,
                letterSpacing = 0.4.em, color = TEXT, shadow = Shadow(CYAN.copy(alpha = 0.6f), blurRadius = 36f),
            ),
        )
        Spacer(Modifier.height(14.dp))
        Text(
            "TAP TO INITIALIZE",
            style = androidx.compose.ui.text.TextStyle(fontFamily = HudFonts.mono, fontSize = 13.sp, letterSpacing = 0.2.em, color = GLOW),
            modifier = Modifier.alpha(if (still) 1f else a),
        )
        Spacer(Modifier.height(28.dp))
        Text(
            "Skip it for today",
            style = androidx.compose.ui.text.TextStyle(fontFamily = HudFonts.mono, fontSize = 11.sp, color = MUTED),
            modifier = Modifier.clickable(onClick = onSkip).padding(12.dp),
        )
    }
}

private val clockFmt = DateTimeFormatter.ofPattern("HH:mm:ss")

@Composable
private fun Hud(lines: List<BootLine>, verdict: BootLogic.Verdict?) {
    val mono = androidx.compose.ui.text.TextStyle(fontFamily = HudFonts.mono, color = GLOW)
    Column(Modifier.fillMaxWidth(0.88f)) {
        Row(
            Modifier.fillMaxWidth().drawBehind {
                drawRect(CYAN.copy(alpha = 0.35f), Offset(0f, size.height - 1.dp.toPx()), Size(size.width, 1.dp.toPx()))
            }.padding(bottom = 6.dp),
        ) {
            Text("YGGDRASIL · SYSTEMS CHECK", style = mono.copy(fontSize = 11.sp, letterSpacing = 0.18.em), modifier = Modifier.weight(1f))
            Text(LocalTime.now().format(clockFmt), style = mono.copy(fontSize = 11.sp, letterSpacing = 0.18.em))
        }
        Spacer(Modifier.height(8.dp))
        LineGroup.entries.forEach { group ->
            val these = lines.filter { it.group == group }
            if (these.isNotEmpty()) {
                Text(group.title, style = mono.copy(fontSize = 10.sp, letterSpacing = 0.24.em, color = MUTED), modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
                these.forEach { line ->
                    Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(vertical = 1.dp)) {
                        Text(line.label, style = mono.copy(fontSize = 13.sp))
                        Spacer(Modifier.width(8.dp))
                        Canvas(Modifier.weight(1f).height(10.dp)) {
                            drawLine(
                                CYAN.copy(alpha = 0.3f), Offset(0f, size.height - 2.dp.toPx()), Offset(size.width, size.height - 2.dp.toPx()),
                                1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(1.dp.toPx(), 3.dp.toPx())),
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(line.status.word, style = mono.copy(fontSize = 13.sp, color = line.status.color()))
                    }
                }
            }
        }
        if (verdict != null) {
            val c = if (verdict.calm) TEXT else MAGENTA
            Text(
                verdict.words,
                style = androidx.compose.ui.text.TextStyle(
                    fontFamily = HudFonts.display, fontWeight = FontWeight.Bold, fontSize = 24.sp, letterSpacing = 0.22.em,
                    color = c, textAlign = TextAlign.Center,
                    shadow = Shadow((if (verdict.calm) CYAN else MAGENTA).copy(alpha = 0.5f), blurRadius = 28f),
                ),
                modifier = Modifier.fillMaxWidth().padding(top = 22.dp),
            )
        }
    }
}

/**
 * The voice: Android's text to speech, and only a voice that runs on this
 * phone, so nothing you hear was sent anywhere. No such voice, no voice.
 */
class BootVoice(context: Context) {
    /** Completes with whether a local voice is there to speak. */
    private val ready = CompletableDeferred<Boolean>()
    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        ready.complete(status == TextToSpeech.SUCCESS && pickVoice())
    }

    private fun pickVoice(): Boolean {
        val local = tts.voices.orEmpty().filter {
            it.locale.language == "en" && !it.isNetworkConnectionRequired &&
                TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in it.features.orEmpty()
        }
        val voice = local.firstOrNull { it.locale.country == "GB" } ?: local.firstOrNull { it.locale == Locale.US } ?: local.firstOrNull()
            ?: return false
        tts.voice = voice
        tts.setSpeechRate(0.95f)
        return true
    }

    /** Speaks [text] and returns when it's said; at once when there's no local voice. */
    suspend fun speakAndWait(text: String) {
        val ok = withTimeoutOrNull(3_000) { ready.await() } ?: false
        if (!ok) return
        suspendCancellableCoroutine { cont ->
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit
                override fun onDone(utteranceId: String?) { if (cont.isActive) cont.resume(Unit) }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) { if (cont.isActive) cont.resume(Unit) }
                override fun onError(utteranceId: String?, errorCode: Int) { if (cont.isActive) cont.resume(Unit) }
            })
            if (tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "boot") != TextToSpeech.SUCCESS) cont.resume(Unit)
            cont.invokeOnCancellation { tts.stop() }
        }
    }

    fun stop() {
        tts.stop()
    }

    fun release() {
        tts.stop()
        tts.shutdown()
    }
}
