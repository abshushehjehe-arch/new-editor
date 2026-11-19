package com.example.ui.components

import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.widget.VideoView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.R
import com.example.domain.model.ExportProgressState
import com.example.domain.model.ParsedClip
import com.example.domain.parser.GeminiTimestampParser
import com.example.ui.localization.UiCopy
import com.example.ui.theme.LosslessMint
import com.example.ui.theme.RazorVermilion
import com.example.ui.theme.StudioBorder
import com.example.ui.theme.StudioCarbon
import com.example.ui.theme.StudioObsidian
import com.example.ui.theme.StudioSlate
import com.example.ui.theme.TimecodeAmber
import com.example.ui.theme.TimecodeStyle
import com.example.ui.viewmodel.PreviewTarget
import kotlinx.coroutines.delay

@Composable
fun EditClipDialog(
    clip: ParsedClip,
    copy: UiCopy,
    onDismiss: () -> Unit,
    onSave: (ParsedClip) -> Unit
) {
    var title by remember(clip.id) { mutableStateOf(clip.title) }
    var startText by remember(clip.id) {
        mutableStateOf(GeminiTimestampParser.formatDurationMs(clip.startMs, forceHours = true))
    }
    var endText by remember(clip.id) {
        mutableStateOf(GeminiTimestampParser.formatDurationMs(clip.endMs, forceHours = true))
    }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    fun nudgeTime(current: String, deltaMs: Long): String {
        val ms = GeminiTimestampParser.parseTimecodeToMs(current) ?: 0L
        return GeminiTimestampParser.formatDurationMs((ms + deltaMs).coerceAtLeast(0L), forceHours = true)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = StudioCarbon,
        title = {
            Text(
                text = copy.editClipTitle,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text(copy.clipNameLabel) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("edit_clip_title_input")
                )

                // Start Time + Nudge buttons
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(
                        value = startText,
                        onValueChange = {
                            startText = it
                            errorMsg = null
                        },
                        label = { Text(copy.startTimeLabel) },
                        singleLine = true,
                        textStyle = TimecodeStyle.copy(color = MaterialTheme.colorScheme.onSurface),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("edit_clip_start_input")
                    )
                    NudgeRow(
                        onNudge = { delta ->
                            startText = nudgeTime(startText, delta)
                            errorMsg = null
                        }
                    )
                }

                // End Time + Nudge buttons
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(
                        value = endText,
                        onValueChange = {
                            endText = it
                            errorMsg = null
                        },
                        label = { Text(copy.endTimeLabel) },
                        singleLine = true,
                        textStyle = TimecodeStyle.copy(color = MaterialTheme.colorScheme.onSurface),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("edit_clip_end_input")
                    )
                    NudgeRow(
                        onNudge = { delta ->
                            endText = nudgeTime(endText, delta)
                            errorMsg = null
                        }
                    )
                }

                if (errorMsg != null) {
                    Text(
                        text = errorMsg!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val sMs = GeminiTimestampParser.parseTimecodeToMs(startText)
                    val eMs = GeminiTimestampParser.parseTimecodeToMs(endText)
                    if (sMs == null || eMs == null || eMs <= sMs) {
                        errorMsg = copy.invalidTimeRangeError
                    } else {
                        onSave(
                            clip.copy(
                                title = title.trim().ifBlank { "Clip ${clip.index}" },
                                startMs = sMs,
                                endMs = eMs,
                                isSingleTimestampInferred = false
                            )
                        )
                    }
                },
                modifier = Modifier.testTag("save_edit_clip_button")
            ) {
                Text(copy.saveChanges)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(copy.dismiss)
            }
        }
    )
}

@Composable
private fun NudgeRow(onNudge: (Long) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        listOf("-5s" to -5_000L, "-1s" to -1_000L, "+1s" to 1_000L, "+5s" to 5_000L).forEach { (lbl, delta) ->
            OutlinedButton(
                onClick = { onNudge(delta) },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(8.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp, vertical = 4.dp)
            ) {
                Text(text = lbl, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

/**
 * In-app segment video preview player that seeks directly into multi-hour videos
 * and auto-stops at the clip's end timestamp.
 */
@Composable
fun ClipPreviewDialog(
    target: PreviewTarget,
    copy: UiCopy,
    onDismiss: () -> Unit
) {
    var mediaPlayerRef by remember { mutableStateOf<MediaPlayer?>(null) }
    var videoViewRef by remember { mutableStateOf<VideoView?>(null) }
    var isPlaying by remember { mutableStateOf(false) }
    var currentPosMs by remember(target) { mutableLongStateOf(target.startMs) }
    var playbackError by remember { mutableStateOf<String?>(null) }

    fun seekPrecise(ms: Long) {
        val mp = mediaPlayerRef
        if (mp != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatching { mp.seekTo(ms, MediaPlayer.SEEK_CLOSEST) }
        } else {
            runCatching { videoViewRef?.seekTo(ms.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()) }
        }
        currentPosMs = ms
    }

    LaunchedEffect(isPlaying, target) {
        while (isPlaying) {
            val pos = runCatching { videoViewRef?.currentPosition?.toLong() }.getOrNull()
            if (pos != null) {
                currentPosMs = pos
                if (target.endMs > target.startMs && pos >= target.endMs) {
                    runCatching { videoViewRef?.pause() }
                    isPlaying = false
                }
            }
            delay(200L)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            runCatching { videoViewRef?.stopPlayback() }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            shape = RoundedCornerShape(22.dp),
            color = StudioCarbon,
            tonalElevation = 8.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, StudioBorder, RoundedCornerShape(22.dp))
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = copy.previewClipTitle,
                            style = MaterialTheme.typography.labelMedium,
                            color = TimecodeAmber
                        )
                        Text(
                            text = target.title,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = stringResource(R.string.content_desc_close)
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color.Black),
                    contentAlignment = Alignment.Center
                ) {
                    if (playbackError != null) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.padding(16.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.ErrorOutline,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error
                            )
                            Text(
                                text = playbackError!!,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        AndroidView(
                            factory = { ctx ->
                                VideoView(ctx).apply {
                                    videoViewRef = this
                                    setVideoURI(Uri.parse(target.videoUriString))
                                    setOnPreparedListener { mp ->
                                        mediaPlayerRef = mp
                                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                            runCatching { mp.seekTo(target.startMs, MediaPlayer.SEEK_CLOSEST) }
                                        } else {
                                            runCatching { seekTo(target.startMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()) }
                                        }
                                        start()
                                        isPlaying = true
                                    }
                                    setOnCompletionListener {
                                        isPlaying = false
                                    }
                                    setOnErrorListener { _, _, _ ->
                                        playbackError = "Preview codec unsupported on this device — lossless trim still works."
                                        true
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }

                // Live segment progress bar + timecodes
                val segDuration = (target.endMs - target.startMs).coerceAtLeast(1L)
                val segFraction = ((currentPosMs - target.startMs).toFloat() / segDuration.toFloat()).coerceIn(0f, 1f)

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    LinearProgressIndicator(
                        progress = { segFraction },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp)),
                        color = RazorVermilion,
                        trackColor = StudioSlate
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = GeminiTimestampParser.formatDurationMs(currentPosMs, forceHours = target.endMs >= 3_600_000L),
                            style = TimecodeStyle,
                            color = TimecodeAmber
                        )
                        Text(
                            text = "${GeminiTimestampParser.formatDurationMs(target.startMs, forceHours = target.endMs >= 3_600_000L)} → ${GeminiTimestampParser.formatDurationMs(target.endMs, forceHours = target.endMs >= 3_600_000L)}",
                            style = TimecodeStyle,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            seekPrecise(target.startMs)
                            runCatching { videoViewRef?.start() }
                            isPlaying = true
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Rounded.Replay, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(copy.replaySegment)
                    }
                    Button(
                        onClick = {
                            if (isPlaying) {
                                runCatching { videoViewRef?.pause() }
                                isPlaying = false
                            } else {
                                if (currentPosMs >= target.endMs - 250L) {
                                    seekPrecise(target.startMs)
                                }
                                runCatching { videoViewRef?.start() }
                                isPlaying = true
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(if (isPlaying) copy.pause else copy.play)
                    }
                }
            }
        }
    }
}

@Composable
fun BatchExportDialog(
    state: ExportProgressState,
    copy: UiCopy,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
    onOpenClipsTab: () -> Unit
) {
    if (state is ExportProgressState.Idle) return

    Dialog(
        onDismissRequest = {
            if (state !is ExportProgressState.Running) onDismiss()
        },
        properties = DialogProperties(dismissOnBackPress = state !is ExportProgressState.Running, dismissOnClickOutside = false)
    ) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = StudioCarbon,
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, StudioBorder, RoundedCornerShape(24.dp))
        ) {
            Column(
                modifier = Modifier.padding(22.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                when (state) {
                    is ExportProgressState.Running -> {
                        Box(
                            modifier = Modifier
                                .size(54.dp)
                                .clip(CircleShape)
                                .background(RazorVermilion.copy(alpha = 0.16f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.ContentCut,
                                contentDescription = null,
                                tint = RazorVermilion,
                                modifier = Modifier.size(28.dp)
                            )
                        }

                        Text(
                            text = copy.exportingTitle,
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )

                        Text(
                            text = copy.exportingClipCounter(state.currentIndex, state.totalCount),
                            style = TimecodeStyle,
                            color = TimecodeAmber
                        )

                        Text(
                            text = state.currentClipTitle,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )

                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            LinearProgressIndicator(
                                progress = { state.overallProgress },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(10.dp)
                                    .clip(RoundedCornerShape(5.dp)),
                                color = RazorVermilion,
                                trackColor = StudioObsidian
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = if (state.modeLabel.contains("9:16")) {
                                        copy.exportingHd916Note
                                    } else {
                                        copy.exportingLosslessNote
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = LosslessMint,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    text = "${(state.overallProgress * 100).toInt()}%",
                                    style = TimecodeStyle,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }

                        OutlinedButton(
                            onClick = onCancel,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("cancel_export_button")
                        ) {
                            Text(copy.cancelExport)
                        }
                    }

                    is ExportProgressState.Completed -> {
                        Box(
                            modifier = Modifier
                                .size(58.dp)
                                .clip(CircleShape)
                                .background(LosslessMint.copy(alpha = 0.16f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.CheckCircle,
                                contentDescription = null,
                                tint = LosslessMint,
                                modifier = Modifier.size(34.dp)
                            )
                        }
                        Text(
                            text = copy.exportDoneTitle,
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = copy.exportDoneSummary(state.exportedCount, state.outputFolderLabel),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (state.failedCount > 0) {
                            Text(
                                text = copy.exportPartialFailures(state.failedCount),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            OutlinedButton(
                                onClick = onDismiss,
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(copy.dismiss)
                            }
                            Button(
                                onClick = {
                                    onDismiss()
                                    onOpenClipsTab()
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = LosslessMint, contentColor = Color(0xFF00241A)),
                                modifier = Modifier
                                    .weight(1.3f)
                                    .testTag("view_exported_clips_button")
                            ) {
                                Text(copy.viewExportedClips)
                            }
                        }
                    }

                    is ExportProgressState.Cancelled -> {
                        Text(
                            text = copy.cancelExport,
                            style = MaterialTheme.typography.titleLarge
                        )
                        Text(
                            text = "${state.exportedCount} clips saved before cancel.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                            Text(copy.dismiss)
                        }
                    }

                    is ExportProgressState.Error -> {
                        Icon(
                            imageVector = Icons.Rounded.ErrorOutline,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(36.dp)
                        )
                        Text(
                            text = state.message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                        Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                            Text(copy.dismiss)
                        }
                    }

                    ExportProgressState.Idle -> Unit
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PaddingSettingsDialog(
    currentPaddingSeconds: Int,
    currentSettings: com.example.domain.model.ClipEditSettings,
    copy: UiCopy,
    onSelectPadding: (Int) -> Unit,
    onUpdateSettings: ((com.example.domain.model.ClipEditSettings) -> com.example.domain.model.ClipEditSettings) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = StudioCarbon,
        icon = {
            Icon(Icons.Rounded.Tune, contentDescription = null, tint = TimecodeAmber)
        },
        title = {
            Text(text = copy.paddingSettingTitle, style = MaterialTheme.typography.titleLarge)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = copy.exportPresetTitle,
                    style = MaterialTheme.typography.labelMedium,
                    color = TimecodeAmber
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    com.example.domain.model.ExportAspectRatio.entries.forEach { ratio ->
                        FilterChip(
                            selected = currentSettings.aspectRatio == ratio,
                            onClick = { onUpdateSettings { it.copy(aspectRatio = ratio) } },
                            label = { Text(copy.aspectRatioLabel(ratio), style = MaterialTheme.typography.labelSmall) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = RazorVermilion,
                                selectedLabelColor = Color.White
                            )
                        )
                    }
                }

                Text(
                    text = copy.editorSubtitlePosSection,
                    style = MaterialTheme.typography.labelMedium,
                    color = TimecodeAmber
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    com.example.domain.model.SubtitlePosition.entries.forEach { pos ->
                        FilterChip(
                            selected = currentSettings.subtitlePosition == pos,
                            onClick = { onUpdateSettings { it.copy(subtitlePosition = pos) } },
                            label = { Text(copy.subtitlePositionLabel(pos), style = MaterialTheme.typography.labelSmall) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = TimecodeAmber,
                                selectedLabelColor = Color(0xFF1F1600)
                            )
                        )
                    }
                }

                Text(
                    text = copy.paddingSettingDesc,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(0, 1, 2, 3, 5).forEach { sec ->
                        FilterChip(
                            selected = currentPaddingSeconds == sec,
                            onClick = { onSelectPadding(sec) },
                            label = {
                                Text(
                                    text = if (sec == 0) "0s (Exact)" else "±${sec}s",
                                    style = TimecodeStyle
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = RazorVermilion,
                                selectedLabelColor = Color.White
                            )
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) {
                Text(copy.gotIt)
            }
        }
    )
}

@Composable
fun GeminiGuideDialog(
    copy: UiCopy,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = StudioCarbon,
        icon = {
            Icon(Icons.Rounded.AutoAwesome, contentDescription = null, tint = TimecodeAmber)
        },
        title = {
            Text(text = copy.guideTitle, style = MaterialTheme.typography.titleLarge)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf(copy.guideStep1, copy.guideStep2, copy.guideStep3, copy.guideStep4).forEach { step ->
                    Text(
                        text = step,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) {
                Text(copy.gotIt)
            }
        }
    )
}
