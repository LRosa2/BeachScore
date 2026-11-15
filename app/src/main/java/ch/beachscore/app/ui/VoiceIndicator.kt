package ch.beachscore.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ch.beachscore.app.R
import ch.beachscore.app.voice.Heard
import ch.beachscore.app.voice.MicKind
import ch.beachscore.app.voice.RejectReason
import ch.beachscore.app.voice.Verdict
import ch.beachscore.app.voice.VoiceStatus
import java.util.Locale

private val Pill = Color(0xB3000000)
private val Accepted = Color(0xFF7CFC8A)
private val Rejected = Color(0xFFFFC14D)
private val Failed = Color(0xFFFF6B6B)

/**
 * Small pill at the top centre, between the two set indicators and above the digits: a mic
 * glyph whose look shows the voice state, and (if enabled) the last heard phrase with its lowest
 * word confidence and verdict. It is not a touch target, so taps there still score.
 */
@Composable
fun VoiceIndicator(voice: VoiceUi, modifier: Modifier = Modifier) {
    val status = voice.status
    if (status == VoiceStatus.Off) return
    val description = stringResource(
        when (status) {
            VoiceStatus.Listening -> R.string.mic_listening
            VoiceStatus.Muted -> R.string.mic_muted
            is VoiceStatus.Preparing -> R.string.mic_preparing
            else -> R.string.mic_failed
        },
    )
    Row(
        modifier
            .padding(top = 10.dp)
            .background(Pill, RoundedCornerShape(50))
            .padding(horizontal = 12.dp, vertical = 5.dp)
            .semantics { contentDescription = description }
            .testTag("voice_indicator"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        MicGlyph(
            color = when (status) {
                VoiceStatus.Listening -> Color.White
                VoiceStatus.Muted -> Color.White.copy(alpha = 0.35f)
                is VoiceStatus.Preparing -> Rejected
                else -> Failed
            },
            struckThrough = status is VoiceStatus.Failed || status == VoiceStatus.Muted,
        )
        val mic = voice.activeMicrophone
        if (mic != null && (status == VoiceStatus.Listening || status == VoiceStatus.Muted)) {
            // Which microphone is live, so it can be checked at the court: "USB" vs "Phone".
            Text(stringResource(micKindText(mic.kind)), color = Color.White.copy(alpha = 0.85f), fontSize = 13.sp, modifier = Modifier.testTag("mic_in_use"))
        }
        if (status is VoiceStatus.Preparing) {
            Text("${(status.progress * 100).toInt()}%", color = Color.White, fontSize = 15.sp)
        }
        AnimatedVisibility(voice.lastHeard != null, enter = fadeIn(), exit = fadeOut()) {
            voice.lastHeard?.let { HeardText(it) }
        }
    }
}

@Composable
private fun HeardText(heard: Heard) {
    val verdict = heard.verdict
    val (mark, color, label) = when (verdict) {
        is Verdict.Accepted -> Triple("✓", Accepted, stringResource(R.string.heard_ok))
        is Verdict.Rejected -> Triple("✗", Rejected, stringResource(reasonText(verdict.reason)))
    }
    val confidence = heard.minConfidence?.let { String.format(Locale.ROOT, "%.2f", it) } ?: "–"
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("heard_line")) {
        Text("“${heard.display}”", color = Color.White, fontSize = 15.sp)
        Text(confidence, color = Color.White.copy(alpha = 0.7f), fontSize = 15.sp)
        Text("$mark $label", color = color, fontSize = 15.sp, fontWeight = FontWeight.Bold)
    }
}

internal fun reasonText(reason: RejectReason): Int = when (reason) {
    RejectReason.UNKNOWN -> R.string.reason_unknown
    RejectReason.MULTIPLE -> R.string.reason_multiple
    RejectReason.IN_SPEECH -> R.string.reason_in_speech
    RejectReason.LOW_CONFIDENCE -> R.string.reason_low_confidence
    RejectReason.ECHO_GUARD -> R.string.reason_echo_guard
    RejectReason.LOCKED -> R.string.reason_locked
    RejectReason.SET_WON -> R.string.reason_set_won
    RejectReason.NOTHING_TO_UNDO -> R.string.reason_nothing_to_undo
    RejectReason.INCOMPLETE -> R.string.reason_incomplete
    RejectReason.PAUSE_TOO_LONG -> R.string.reason_pause_too_long
}

/** A simple microphone drawn on a canvas (no icon library needed). */
@Composable
private fun MicGlyph(color: Color, struckThrough: Boolean) {
    Canvas(Modifier.size(18.dp)) {
        val w = size.width
        val h = size.height
        val stroke = w * 0.11f
        drawRoundRect(color, topLeft = Offset(w * 0.33f, 0f), size = Size(w * 0.34f, h * 0.6f), cornerRadius = CornerRadius(w * 0.17f))
        drawArc(color, 0f, 180f, useCenter = false, topLeft = Offset(w * 0.17f, h * 0.25f), size = Size(w * 0.66f, h * 0.5f), style = Stroke(stroke))
        drawLine(color, Offset(w / 2, h * 0.75f), Offset(w / 2, h * 0.95f), stroke)
        if (struckThrough) drawLine(color, Offset(w * 0.1f, h * 0.05f), Offset(w * 0.9f, h * 0.95f), stroke)
    }
}

fun micKindText(kind: MicKind): Int = when (kind) {
    MicKind.BUILTIN -> R.string.mic_kind_phone
    MicKind.USB -> R.string.mic_kind_usb
    MicKind.WIRED_HEADSET -> R.string.mic_kind_headset
    MicKind.OTHER -> R.string.mic_kind_other
}

/** "USB · Wireless Mic Rx"; the type alone when the device has no product name. */
@Composable
fun micLabel(kind: MicKind, productName: String): String {
    val type = stringResource(micKindText(kind))
    return if (productName.isBlank()) type else "$type · ${productName.trim()}"
}
