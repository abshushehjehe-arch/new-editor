package com.example.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.domain.model.ParsedClip
import com.example.domain.parser.GeminiTimestampParser
import com.example.ui.theme.LosslessMint
import com.example.ui.theme.RazorVermilion
import com.example.ui.theme.StudioBorder
import com.example.ui.theme.StudioGraphite
import com.example.ui.theme.StudioInkSubtle
import com.example.ui.theme.StudioObsidian
import com.example.ui.theme.TimecodeAmber
import com.example.ui.theme.TimecodeStyle
import kotlin.math.sin

/**
 * Interactive multi-hour video timeline visualizer showing every parsed Gemini clip
 * across a 5h–12h+ timeline with proportional ruler ticks and razor markers.
 */
@Composable
fun MultiHourTimelineBar(
    clips: List<ParsedClip>,
    totalDurationMs: Long,
    highlightedClipId: String?,
    label: String,
    onClipTapped: (ParsedClip) -> Unit,
    modifier: Modifier = Modifier
) {
    val effectiveDurationMs = remember(clips, totalDurationMs) {
        val maxClipEnd = clips.maxOfOrNull { it.endMs } ?: 0L
        maxOf(totalDurationMs, maxClipEnd, 60_000L)
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(StudioObsidian)
            .border(1.dp, StudioBorder, RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = GeminiTimestampParser.formatDurationMs(effectiveDurationMs, forceHours = effectiveDurationMs >= 3_600_000L),
                style = TimecodeStyle,
                color = TimecodeAmber
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp)
                .clip(RoundedCornerShape(8.dp))
                .pointerInput(clips, effectiveDurationMs) {
                    detectTapGestures { offset ->
                        if (size.width <= 0) return@detectTapGestures
                        val ratio = (offset.x / size.width.toFloat()).coerceIn(0f, 1f)
                        val tappedMs = (ratio * effectiveDurationMs.toDouble()).toLong()
                        val nearest = clips.minByOrNull { clip ->
                            when {
                                tappedMs in clip.startMs..clip.endMs -> 0L
                                tappedMs < clip.startMs -> clip.startMs - tappedMs
                                else -> tappedMs - clip.endMs
                            }
                        }
                        if (nearest != null) {
                            onClipTapped(nearest)
                        }
                    }
                }
        ) {
            val w = size.width
            val h = size.height

            // Subtle dark track rail
            drawRoundRect(
                color = StudioGraphite.copy(alpha = 0.65f),
                topLeft = Offset(0f, 6f),
                size = Size(w, h - 12f),
                cornerRadius = CornerRadius(12f, 12f)
            )

            // Decorative subtle waveform bars across the track
            val barCount = 56
            val step = w / barCount
            for (i in 0 until barCount) {
                val x = i * step + step * 0.35f
                val amp = (0.22f + 0.32f * kotlin.math.abs(sin(i * 0.65f))).coerceIn(0.15f, 0.55f)
                val barH = (h - 18f) * amp
                drawLine(
                    color = StudioInkSubtle.copy(alpha = 0.25f),
                    start = Offset(x, (h - barH) / 2f),
                    end = Offset(x, (h + barH) / 2f),
                    strokeWidth = (step * 0.34f).coerceAtLeast(1.5f)
                )
            }

            // Ruler tick marks along the top edge
            val tickCount = 8
            for (t in 0..tickCount) {
                val tx = (t.toFloat() / tickCount.toFloat()) * w
                drawLine(
                    color = StudioBorder,
                    start = Offset(tx, 0f),
                    end = Offset(tx, if (t % 2 == 0) 7f else 4f),
                    strokeWidth = 1.5f
                )
            }

            // Draw each parsed clip segment
            clips.forEach { clip ->
                val startFrac = (clip.startMs.toDouble() / effectiveDurationMs.toDouble()).toFloat().coerceIn(0f, 1f)
                val endFrac = (clip.endMs.toDouble() / effectiveDurationMs.toDouble()).toFloat().coerceIn(startFrac, 1f)
                val x1 = startFrac * w
                // Ensure even a 30s clip on a 12-hour timeline remains clearly visible (>= 7px wide)
                val rawWidth = (endFrac - startFrac) * w
                val segWidth = maxOf(rawWidth, 8f).coerceAtMost(w - x1)

                val isHighlighted = clip.id == highlightedClipId
                val fillBrush = when {
                    clip.isOutOfBounds -> Brush.horizontalGradient(
                        listOf(Color(0xFFFF6B7A).copy(alpha = 0.45f), Color(0xFFFF6B7A).copy(alpha = 0.7f))
                    )
                    !clip.isSelected -> Brush.horizontalGradient(
                        listOf(StudioInkSubtle.copy(alpha = 0.35f), StudioInkSubtle.copy(alpha = 0.45f))
                    )
                    isHighlighted -> Brush.horizontalGradient(
                        listOf(LosslessMint, TimecodeAmber)
                    )
                    else -> Brush.horizontalGradient(
                        listOf(RazorVermilion, TimecodeAmber)
                    )
                }

                drawRoundRect(
                    brush = fillBrush,
                    topLeft = Offset(x1, if (isHighlighted) 3f else 8f),
                    size = Size(segWidth, if (isHighlighted) h - 6f else h - 16f),
                    cornerRadius = CornerRadius(6f, 6f)
                )

                // Razor start-cut vertical indicator
                if (clip.isSelected && !clip.isOutOfBounds) {
                    drawLine(
                        color = Color.White.copy(alpha = 0.9f),
                        start = Offset(x1, 4f),
                        end = Offset(x1, h - 4f),
                        strokeWidth = 2f
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "00:00:00",
                style = MaterialTheme.typography.labelSmall,
                color = StudioInkSubtle
            )
            Text(
                text = GeminiTimestampParser.formatDurationMs(effectiveDurationMs / 2L, forceHours = effectiveDurationMs >= 3_600_000L),
                style = MaterialTheme.typography.labelSmall,
                color = StudioInkSubtle
            )
            Text(
                text = GeminiTimestampParser.formatDurationMs(effectiveDurationMs, forceHours = effectiveDurationMs >= 3_600_000L),
                style = MaterialTheme.typography.labelSmall,
                color = StudioInkSubtle
            )
        }
    }
}

/**
 * Loads and displays an async video keyframe preview at a given timestamp.
 */
@Composable
fun VideoFrameThumbnail(
    uriString: String?,
    timestampMs: Long,
    loadThumbnail: suspend (String, Long) -> Bitmap?,
    modifier: Modifier = Modifier
) {
    var bitmap by remember(uriString, timestampMs) { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(uriString, timestampMs) {
        bitmap = if (!uriString.isNullOrBlank()) {
            loadThumbnail(uriString, timestampMs)
        } else {
            null
        }
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(StudioObsidian)
            .border(1.dp, StudioBorder.copy(alpha = 0.7f), RoundedCornerShape(10.dp)),
        contentAlignment = Alignment.Center
    ) {
        val currentBitmap = bitmap
        if (currentBitmap != null) {
            Image(
                bitmap = currentBitmap.asImageBitmap(),
                contentDescription = stringResource(R.string.content_desc_clip_thumb),
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Icon(
                imageVector = Icons.Rounded.Movie,
                contentDescription = stringResource(R.string.content_desc_clip_thumb),
                tint = StudioInkSubtle,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}
