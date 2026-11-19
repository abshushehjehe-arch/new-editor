package com.example.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.HighQuality
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material.icons.rounded.MovieFilter
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material.icons.rounded.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.example.domain.model.AppLanguage
import com.example.domain.model.ClipEditSettings
import com.example.domain.model.ExportAspectRatio
import com.example.domain.model.ExportResolution
import com.example.domain.model.SubtitleCue
import com.example.domain.model.SubtitlePosition
import com.example.domain.model.SubtitleStylePreset
import com.example.domain.parser.SubtitleParser
import com.example.ui.localization.UiCopy
import com.example.ui.theme.LosslessMint
import com.example.ui.theme.RazorVermilion
import com.example.ui.theme.StudioBorder
import com.example.ui.theme.StudioCarbon
import com.example.ui.theme.StudioObsidian
import com.example.ui.theme.StudioSlate
import com.example.ui.theme.TimecodeAmber
import com.example.ui.theme.TimecodeAmberContainer
import com.example.ui.theme.TimecodeStyle
import com.example.ui.viewmodel.ClipEditorSession
import kotlinx.coroutines.delay

/**
 * Full-screen 9:16 Vertical HD & Dynamic Subtitle Editor with live ExoPlayer preview,
 * Top/Center/Bottom subtitle placement, caption styles, SRT/VTT & Transcript import,
 * and on-device SpeechRecognizer live voice captioning.
 */
@OptIn(UnstableApi::class)
@Composable
fun ClipEditorScreen(
    session: ClipEditorSession,
    language: AppLanguage,
    copy: UiCopy,
    onClose: () -> Unit,
    onUpdateSettings: ((ClipEditSettings) -> ClipEditSettings) -> Unit,
    onImportSubtitleText: (String) -> Unit,
    onImportSubtitleFile: (android.net.Uri) -> Unit,
    onAddOrUpdateCue: (SubtitleCue) -> Unit,
    onAppendSpokenText: (String, Long) -> Unit,
    onDeleteCue: (String) -> Unit,
    onTriggerAiAutoCaption: () -> Unit,
    onExportHdClip: () -> Unit
) {
    BackHandler(onBack = onClose)

    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current

    var isPlaying by remember { mutableStateOf(true) }
    var currentRelMs by remember(session.clipId) { mutableLongStateOf(0L) }
    var showPasteTranscriptDialog by remember { mutableStateOf(false) }
    var editingCue by remember { mutableStateOf<SubtitleCue?>(null) }
    var isListeningMic by remember { mutableStateOf(false) }
    var micStatusMessage by remember { mutableStateOf<String?>(null) }

    // ExoPlayer configured with exact ClippingConfiguration so only this clip plays cleanly in loop
    val exoPlayer = remember(session.sourceUriString, session.startMs, session.endMs) {
        ExoPlayer.Builder(context).build().apply {
            val mediaItem = MediaItem.Builder()
                .setUri(session.sourceUriString)
                .setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(session.startMs)
                        .setEndPositionMs(session.endMs)
                        .build()
                )
                .build()
            setMediaItem(mediaItem)
            repeatMode = Player.REPEAT_MODE_ONE
            prepare()
            playWhenReady = true
        }
    }

    DisposableEffect(exoPlayer) {
        onDispose {
            runCatching { exoPlayer.release() }
        }
    }

    // Track current clip-relative playback position for live synchronized subtitle preview
    LaunchedEffect(exoPlayer) {
        while (true) {
            isPlaying = exoPlayer.isPlaying
            currentRelMs = exoPlayer.currentPosition.coerceIn(0L, session.clipDurationMs)
            delay(80L)
        }
    }

    // Zero-permission SAF picker for .srt / .vtt / .txt subtitle files
    val srtPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            onImportSubtitleFile(uri)
        }
    }

    // On-device Android SpeechRecognizer for live Hindi/English voice captioning
    val speechRecognizer = remember {
        if (SpeechRecognizer.isRecognitionAvailable(context)) {
            SpeechRecognizer.createSpeechRecognizer(context)
        } else {
            null
        }
    }

    DisposableEffect(speechRecognizer) {
        onDispose {
            runCatching { speechRecognizer?.destroy() }
        }
    }

    fun startMicListening() {
        val recognizer = speechRecognizer
        if (recognizer == null) {
            micStatusMessage = "Speech recognition service unavailable on this device."
            return
        }
        val captureTimeMs = currentRelMs
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE,
                if (language == AppLanguage.HINDI) "hi-IN" else "en-IN"
            )
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                isListeningMic = true
                micStatusMessage = copy.editorMicListening
            }

            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() {
                isListeningMic = false
            }

            override fun onError(error: Int) {
                isListeningMic = false
                micStatusMessage = "Speech capture finished ($error). Tap mic to dictate another line."
            }

            override fun onResults(results: Bundle?) {
                isListeningMic = false
                val text = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                if (!text.isNullOrBlank()) {
                    onAppendSpokenText(text, captureTimeMs)
                    micStatusMessage = "✓ \"$text\""
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val partial = partialResults
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                if (!partial.isNullOrBlank()) {
                    micStatusMessage = partial
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
        recognizer.startListening(intent)
    }

    val audioPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startMicListening()
        } else {
            micStatusMessage = copy.editorMicPermissionNeeded
        }
    }

    val activeCue = remember(session.cues, currentRelMs, session.settings.subtitlesEnabled) {
        if (!session.settings.subtitlesEnabled) {
            null
        } else {
            session.cues.firstOrNull { currentRelMs in it.startMs..it.endMs }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(StudioObsidian),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 760.dp)
        ) {
            // Top Editor App Bar
            Surface(
                color = StudioCarbon,
                modifier = Modifier
                    .fillMaxWidth()
                    .border(0.5.dp, StudioBorder)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = onClose,
                        modifier = Modifier.testTag("close_clip_editor_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = "Back",
                            tint = Color.White
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = copy.editorScreenTitle,
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.White
                        )
                        Text(
                            text = session.title,
                            style = MaterialTheme.typography.bodySmall,
                            color = TimecodeAmber,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(RazorVermilion.copy(alpha = 0.18f))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = "${copy.aspectRatioShortTag(session.settings.aspectRatio)} • ${session.settings.resolution.label}",
                            style = MaterialTheme.typography.labelSmall,
                            color = RazorVermilion
                        )
                    }
                }
            }

            // Scrollable Editor Body
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // 1. LIVE 9:16 VERTICAL PREVIEW STAGE WITH REAL SUBTITLE OVERLAY
                item(key = "live_preview_stage") {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        val isVertical = session.settings.aspectRatio != ExportAspectRatio.ORIGINAL_LOSSLESS
                        val previewModifier = if (isVertical) {
                            Modifier
                                .height(340.dp)
                                .aspectRatio(9f / 16f)
                        } else {
                            Modifier
                                .fillMaxWidth()
                                .aspectRatio(16f / 9f)
                        }

                        Box(
                            modifier = previewModifier
                                .clip(RoundedCornerShape(18.dp))
                                .background(Color.Black)
                                .border(1.5.dp, StudioBorder, RoundedCornerShape(18.dp))
                                .clickable {
                                    if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play()
                                }
                        ) {
                            AndroidView(
                                factory = { ctx ->
                                    PlayerView(ctx).apply {
                                        player = exoPlayer
                                        useController = false
                                        setShutterBackgroundColor(android.graphics.Color.BLACK)
                                    }
                                },
                                update = { view ->
                                    view.resizeMode = when (session.settings.aspectRatio) {
                                        ExportAspectRatio.VERTICAL_9_16_CROP -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                                        ExportAspectRatio.VERTICAL_9_16_FIT,
                                        ExportAspectRatio.ORIGINAL_LOSSLESS -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                                    }
                                },
                                modifier = Modifier.fillMaxSize()
                            )

                            // Live Synchronized Subtitle Preview at TOP / CENTER / BOTTOM
                            val previewText = activeCue?.text ?: if (session.settings.subtitlesEnabled && session.cues.isEmpty()) {
                                if (language == AppLanguage.HINDI) "यहाँ आपका सबटाइटल दिखेगा" else "Your subtitle appears here"
                            } else {
                                null
                            }

                            if (!previewText.isNullOrBlank()) {
                                val alignment = when (session.settings.subtitlePosition) {
                                    SubtitlePosition.TOP -> Alignment.TopCenter
                                    SubtitlePosition.CENTER -> Alignment.Center
                                    SubtitlePosition.BOTTOM -> Alignment.BottomCenter
                                }
                                val verticalPad = when (session.settings.subtitlePosition) {
                                    SubtitlePosition.TOP -> PaddingValues(top = 26.dp, start = 10.dp, end = 10.dp)
                                    SubtitlePosition.CENTER -> PaddingValues(horizontal = 10.dp)
                                    SubtitlePosition.BOTTOM -> PaddingValues(bottom = 26.dp, start = 10.dp, end = 10.dp)
                                }

                                Box(
                                    modifier = Modifier
                                        .align(alignment)
                                        .padding(verticalPad)
                                ) {
                                    LiveSubtitlePreviewBadge(
                                        text = previewText,
                                        style = session.settings.subtitleStyle,
                                        fontScale = session.settings.fontScale,
                                        isSamplePlaceholder = activeCue == null
                                    )
                                }
                            }

                            // Subtle play/pause corner pill
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(8.dp)
                                    .size(32.dp)
                                    .clip(CircleShape)
                                    .background(Color.Black.copy(alpha = 0.6f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                    contentDescription = if (isPlaying) copy.pause else copy.play,
                                    tint = Color.White,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }

                        // Playback Scrubber
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(StudioCarbon)
                                .border(1.dp, StudioBorder, RoundedCornerShape(14.dp))
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                IconButton(
                                    onClick = {
                                        if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play()
                                    },
                                    modifier = Modifier.size(40.dp)
                                ) {
                                    Icon(
                                        imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                        contentDescription = copy.play,
                                        tint = RazorVermilion
                                    )
                                }
                                Slider(
                                    value = (currentRelMs.toFloat() / session.clipDurationMs.toFloat()).coerceIn(0f, 1f),
                                    onValueChange = { frac ->
                                        val seekMs = (frac * session.clipDurationMs).toLong()
                                        exoPlayer.seekTo(seekMs)
                                        currentRelMs = seekMs
                                    },
                                    colors = SliderDefaults.colors(
                                        thumbColor = RazorVermilion,
                                        activeTrackColor = RazorVermilion,
                                        inactiveTrackColor = StudioSlate
                                    ),
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    text = "${SubtitleParser.formatShortTime(currentRelMs)} / ${SubtitleParser.formatShortTime(session.clipDurationMs)}",
                                    style = TimecodeStyle,
                                    color = TimecodeAmber
                                )
                            }
                        }
                    }
                }

                // 2. ASPECT RATIO (9:16) & HD RESOLUTION CONTROLS
                item(key = "aspect_quality_card") {
                    EditorSettingsCard(
                        settings = session.settings,
                        copy = copy,
                        onUpdateSettings = onUpdateSettings
                    )
                }

                // 3. SUBTITLE SOURCES & LIVE VOICE CAPTIONING CARD
                item(key = "subtitle_sources_card") {
                    SubtitleSourcesCard(
                        isListeningMic = isListeningMic,
                        micStatusMessage = micStatusMessage,
                        aiNoticeMessage = session.aiNoticeMessage,
                        copy = copy,
                        onOpenPasteTranscript = { showPasteTranscriptDialog = true },
                        onImportSrt = {
                            srtPickerLauncher.launch(arrayOf("text/*", "application/x-subrip", "*/*"))
                        },
                        onToggleMic = {
                            if (isListeningMic) {
                                runCatching { speechRecognizer?.stopListening() }
                                isListeningMic = false
                            } else {
                                val granted = ContextCompat.checkSelfPermission(
                                    context,
                                    Manifest.permission.RECORD_AUDIO
                                ) == PackageManager.PERMISSION_GRANTED
                                if (granted) {
                                    startMicListening()
                                } else {
                                    audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                }
                            }
                        },
                        onAiAutoCaption = onTriggerAiAutoCaption,
                        onAddManualCue = {
                            val start = currentRelMs.coerceAtMost((session.clipDurationMs - 1_000L).coerceAtLeast(0L))
                            val end = (start + 2_500L).coerceAtMost(session.clipDurationMs)
                            editingCue = SubtitleCue(
                                id = "cue_${System.currentTimeMillis()}",
                                startMs = start,
                                endMs = maxOf(end, start + 800L),
                                text = ""
                            )
                        }
                    )
                }

                // 4. EDITABLE SUBTITLE CUES LIST
                item(key = "cues_list_header") {
                    Text(
                        text = copy.editorCuesHeader(session.cues.size),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                if (session.cues.isEmpty()) {
                    item(key = "cues_empty_hint") {
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = StudioCarbon,
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(1.dp, StudioBorder.copy(alpha = 0.6f), RoundedCornerShape(14.dp))
                        ) {
                            Text(
                                text = copy.editorNoCuesHint,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(14.dp)
                            )
                        }
                    }
                } else {
                    items(items = session.cues, key = { it.id }) { cue ->
                        val isActive = activeCue?.id == cue.id
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = if (isActive) StudioSlate else StudioCarbon,
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(
                                    width = if (isActive) 1.5.dp else 1.dp,
                                    color = if (isActive) TimecodeAmber else StudioBorder,
                                    shape = RoundedCornerShape(12.dp)
                                )
                                .clickable {
                                    exoPlayer.seekTo(cue.startMs)
                                    currentRelMs = cue.startMs
                                }
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "${SubtitleParser.formatShortTime(cue.startMs)} → ${SubtitleParser.formatShortTime(cue.endMs)}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = TimecodeAmber
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = cue.text,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = Color.White
                                    )
                                }
                                IconButton(onClick = { editingCue = cue }) {
                                    Icon(
                                        imageVector = Icons.Outlined.Edit,
                                        contentDescription = "Edit subtitle",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                IconButton(onClick = { onDeleteCue(cue.id) }) {
                                    Icon(
                                        imageVector = Icons.Outlined.DeleteOutline,
                                        contentDescription = "Delete subtitle",
                                        tint = MaterialTheme.colorScheme.error.copy(alpha = 0.85f)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Bottom Sticky Export 9:16 HD CTA
            Surface(
                color = StudioCarbon,
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, StudioBorder, RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = onExportHdClip,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = RazorVermilion,
                            contentColor = Color.White
                        ),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 52.dp)
                            .testTag("export_9_16_hd_clip_button")
                    ) {
                        Icon(Icons.Rounded.HighQuality, contentDescription = null, modifier = Modifier.size(22.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "${copy.editorSaveHdCta} (${session.settings.resolution.label})",
                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold)
                        )
                    }
                }
            }
        }
    }

    // Paste Transcript / Script Dialog
    if (showPasteTranscriptDialog) {
        var pastedText by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showPasteTranscriptDialog = false },
            containerColor = StudioCarbon,
            title = { Text(copy.editorPasteDialogTitle, style = MaterialTheme.typography.titleLarge) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = copy.editorPasteDialogHint,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    FilledTonalButton(
                        onClick = {
                            clipboardManager.getText()?.text?.let { pastedText = it }
                        },
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Rounded.ContentPaste, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(copy.pasteClipboard)
                    }
                    OutlinedTextField(
                        value = pastedText,
                        onValueChange = { pastedText = it },
                        minLines = 5,
                        maxLines = 8,
                        placeholder = {
                            Text("00:00 नमस्ते दोस्तों...\n00:03 आज हम बात करेंगे...")
                        },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = StudioObsidian,
                            unfocusedContainerColor = StudioObsidian
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("paste_transcript_input")
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (pastedText.isNotBlank()) {
                            onImportSubtitleText(pastedText)
                        }
                        showPasteTranscriptDialog = false
                    },
                    modifier = Modifier.testTag("apply_transcript_button")
                ) {
                    Text(copy.editorApplyTranscript)
                }
            },
            dismissButton = {
                TextButton(onClick = { showPasteTranscriptDialog = false }) {
                    Text(copy.dismiss)
                }
            }
        )
    }

    // Edit Individual Cue Dialog
    editingCue?.let { cue ->
        var text by remember(cue.id) { mutableStateOf(cue.text) }
        var startSecStr by remember(cue.id) { mutableStateOf(String.format("%.1f", cue.startMs / 1000.0)) }
        var endSecStr by remember(cue.id) { mutableStateOf(String.format("%.1f", cue.endMs / 1000.0)) }

        AlertDialog(
            onDismissRequest = { editingCue = null },
            containerColor = StudioCarbon,
            title = { Text(copy.editorAddCue, style = MaterialTheme.typography.titleLarge) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        label = { Text("Subtitle Text (Hindi / English)") },
                        minLines = 2,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = startSecStr,
                            onValueChange = { startSecStr = it },
                            label = { Text("Start (sec)") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = endSecStr,
                            onValueChange = { endSecStr = it },
                            label = { Text("End (sec)") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val sMs = ((startSecStr.toDoubleOrNull() ?: 0.0) * 1000.0).toLong().coerceAtLeast(0L)
                        val eMs = ((endSecStr.toDoubleOrNull() ?: (sMs / 1000.0 + 2.5)) * 1000.0)
                            .toLong()
                            .coerceAtLeast(sMs + 400L)
                            .coerceAtMost(session.clipDurationMs)
                        if (text.isNotBlank()) {
                            onAddOrUpdateCue(cue.copy(startMs = sMs, endMs = maxOf(eMs, sMs + 400L), text = text.trim()))
                        }
                        editingCue = null
                    }
                ) {
                    Text(copy.saveChanges)
                }
            },
            dismissButton = {
                TextButton(onClick = { editingCue = null }) {
                    Text(copy.dismiss)
                }
            }
        )
    }
}

@Composable
private fun LiveSubtitlePreviewBadge(
    text: String,
    style: SubtitleStylePreset,
    fontScale: Float,
    isSamplePlaceholder: Boolean
) {
    val baseSize = (14f * fontScale.coerceIn(0.75f, 1.45f)).sp
    val textColor = when (style) {
        SubtitleStylePreset.VIRAL_YELLOW -> Color(0xFFFFE033)
        SubtitleStylePreset.CINEMA_PILL -> Color.White
        SubtitleStylePreset.AMBER_PRO -> TimecodeAmber
        SubtitleStylePreset.CLASSIC_WHITE -> Color.White
    }.copy(alpha = if (isSamplePlaceholder) 0.72f else 1f)

    val containerModifier = when (style) {
        SubtitleStylePreset.CINEMA_PILL -> Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xCC0B0D12))
            .padding(horizontal = 10.dp, vertical = 5.dp)
        SubtitleStylePreset.AMBER_PRO -> Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xDD121621))
            .border(1.dp, RazorVermilion, RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 5.dp)
        else -> Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
    }

    Box(modifier = containerModifier) {
        Text(
            text = text,
            color = textColor,
            fontSize = baseSize,
            fontWeight = FontWeight.ExtraBold,
            textAlign = TextAlign.Center,
            maxLines = 3,
            style = MaterialTheme.typography.titleMedium.copy(
                shadow = Shadow(
                    color = Color.Black,
                    offset = Offset(2f, 2f),
                    blurRadius = 6f
                )
            )
        )
    }
}

@kotlin.OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EditorSettingsCard(
    settings: ClipEditSettings,
    copy: UiCopy,
    onUpdateSettings: ((ClipEditSettings) -> ClipEditSettings) -> Unit
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = StudioCarbon,
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, StudioBorder, RoundedCornerShape(18.dp))
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Aspect Ratio (9:16)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(Icons.Rounded.MovieFilter, contentDescription = null, tint = RazorVermilion, modifier = Modifier.size(18.dp))
                Text(copy.editorAspectSection, style = MaterialTheme.typography.titleSmall)
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                ExportAspectRatio.entries.forEach { ratio ->
                    FilterChip(
                        selected = settings.aspectRatio == ratio,
                        onClick = { onUpdateSettings { it.copy(aspectRatio = ratio) } },
                        label = { Text(copy.aspectRatioLabel(ratio), style = MaterialTheme.typography.labelSmall) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = RazorVermilion,
                            selectedLabelColor = Color.White
                        ),
                        modifier = Modifier.testTag("aspect_chip_${ratio.name}")
                    )
                }
            }

            // HD Quality
            Text(copy.editorQualitySection, style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ExportResolution.entries.forEach { res ->
                    FilterChip(
                        selected = settings.resolution == res,
                        onClick = { onUpdateSettings { it.copy(resolution = res) } },
                        label = { Text(res.label, style = MaterialTheme.typography.labelSmall) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = LosslessMint,
                            selectedLabelColor = Color(0xFF00241A)
                        )
                    )
                }
            }

            // Subtitles Enable Switch
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(Icons.Rounded.Subtitles, contentDescription = null, tint = TimecodeAmber, modifier = Modifier.size(18.dp))
                    Text(copy.editorSubtitlesToggle, style = MaterialTheme.typography.titleSmall)
                }
                Switch(
                    checked = settings.subtitlesEnabled,
                    onCheckedChange = { enabled -> onUpdateSettings { it.copy(subtitlesEnabled = enabled) } },
                    colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = RazorVermilion)
                )
            }

            AnimatedVisibility(visible = settings.subtitlesEnabled) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    // Subtitle Position: TOP / CENTER / BOTTOM
                    Text(copy.editorSubtitlePosSection, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SubtitlePosition.entries.forEach { pos ->
                            FilterChip(
                                selected = settings.subtitlePosition == pos,
                                onClick = { onUpdateSettings { it.copy(subtitlePosition = pos) } },
                                label = { Text(copy.subtitlePositionLabel(pos), style = MaterialTheme.typography.labelSmall) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = TimecodeAmber,
                                    selectedLabelColor = Color(0xFF1F1600)
                                ),
                                modifier = Modifier.testTag("subtitle_pos_${pos.name}")
                            )
                        }
                    }

                    // Subtitle Style Preset
                    Text(copy.editorSubtitleStyleSection, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        SubtitleStylePreset.entries.forEach { style ->
                            FilterChip(
                                selected = settings.subtitleStyle == style,
                                onClick = { onUpdateSettings { it.copy(subtitleStyle = style) } },
                                label = { Text(copy.subtitleStyleLabel(style), style = MaterialTheme.typography.labelSmall) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = StudioSlate,
                                    selectedLabelColor = TimecodeAmber
                                )
                            )
                        }
                    }

                    // Font Scale Slider
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(copy.editorFontSizeLabel, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Slider(
                            value = settings.fontScale,
                            onValueChange = { scale -> onUpdateSettings { it.copy(fontScale = scale) } },
                            valueRange = 0.8f..1.4f,
                            colors = SliderDefaults.colors(thumbColor = TimecodeAmber, activeTrackColor = TimecodeAmber),
                            modifier = Modifier.weight(1f)
                        )
                        Text("${(settings.fontScale * 100).toInt()}%", style = TimecodeStyle, color = TimecodeAmber)
                    }
                }
            }
        }
    }
}

@kotlin.OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SubtitleSourcesCard(
    isListeningMic: Boolean,
    micStatusMessage: String?,
    aiNoticeMessage: String?,
    copy: UiCopy,
    onOpenPasteTranscript: () -> Unit,
    onImportSrt: () -> Unit,
    onToggleMic: () -> Unit,
    onAiAutoCaption: () -> Unit,
    onAddManualCue: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = StudioCarbon,
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, StudioBorder, RoundedCornerShape(18.dp))
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilledTonalButton(
                    onClick = onOpenPasteTranscript,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.testTag("editor_paste_transcript_button")
                ) {
                    Icon(Icons.Rounded.ContentPaste, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(copy.editorPasteTranscript, style = MaterialTheme.typography.labelMedium)
                }

                FilledTonalButton(
                    onClick = onImportSrt,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.testTag("editor_import_srt_button")
                ) {
                    Icon(Icons.Rounded.UploadFile, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(copy.editorImportSrt, style = MaterialTheme.typography.labelMedium)
                }

                Button(
                    onClick = onToggleMic,
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isListeningMic) RazorVermilion else StudioSlate,
                        contentColor = Color.White
                    ),
                    modifier = Modifier.testTag("editor_mic_caption_button")
                ) {
                    Icon(
                        imageVector = if (isListeningMic) Icons.Rounded.MicOff else Icons.Rounded.Mic,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (isListeningMic) copy.editorMicListening else copy.editorMicCaption,
                        style = MaterialTheme.typography.labelMedium
                    )
                }

                OutlinedButton(
                    onClick = onAiAutoCaption,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.testTag("editor_ai_caption_button")
                ) {
                    Icon(Icons.Rounded.AutoAwesome, contentDescription = null, tint = TimecodeAmber, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(copy.editorAiAutoCaption, style = MaterialTheme.typography.labelMedium)
                }

                OutlinedButton(
                    onClick = onAddManualCue,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.testTag("editor_add_manual_cue_button")
                ) {
                    Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(copy.editorAddCue.removePrefix("+ "), style = MaterialTheme.typography.labelMedium)
                }
            }

            if (!micStatusMessage.isNullOrBlank()) {
                Text(
                    text = micStatusMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = LosslessMint
                )
            }

            if (!aiNoticeMessage.isNullOrBlank()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(TimecodeAmberContainer.copy(alpha = 0.75f))
                        .padding(10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Info,
                        contentDescription = null,
                        tint = TimecodeAmber,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = aiNoticeMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFFFE5B8)
                    )
                }
            }
        }
    }
}
