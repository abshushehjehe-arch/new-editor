package com.example.ui.screens

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CheckBox
import androidx.compose.material.icons.rounded.CheckBoxOutlineBlank
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.R
import com.example.domain.model.ClipEditSettings
import com.example.domain.model.ExportAspectRatio
import com.example.domain.model.ParsedClip
import com.example.domain.model.ResolvedLinkSource
import com.example.domain.model.SingleTimestampMode
import com.example.domain.model.VideoLinkPlatform
import com.example.domain.model.VideoSourceInfo
import com.example.domain.parser.GeminiTimestampParser
import com.example.ui.components.MultiHourTimelineBar
import com.example.ui.components.VideoFrameThumbnail
import com.example.ui.localization.UiCopy
import com.example.ui.theme.LosslessMint
import com.example.ui.theme.LosslessMintContainer
import com.example.ui.theme.OnLosslessMintContainer
import com.example.ui.theme.RazorVermilion
import com.example.ui.theme.StudioBorder
import com.example.ui.theme.StudioCarbon
import com.example.ui.theme.StudioObsidian
import com.example.ui.theme.StudioSlate
import com.example.ui.theme.TimecodeAmber
import com.example.ui.theme.TimecodeAmberContainer
import com.example.ui.theme.TimecodeLargeStyle
import com.example.ui.theme.TimecodeStyle
import com.example.ui.viewmodel.PreviewTarget
import com.example.ui.viewmodel.SourceInputTab
import com.example.ui.viewmodel.StudioUiState
import kotlinx.coroutines.launch

@Composable
fun StudioScreen(
    state: StudioUiState,
    copy: UiCopy,
    onSelectSourceTab: (SourceInputTab) -> Unit,
    onLinkUrlChanged: (String) -> Unit,
    onResolveLink: () -> Unit,
    onClearLink: () -> Unit,
    onPickVideoDocument: () -> Unit,
    onPickVideoGallery: () -> Unit,
    onPromptTextChanged: (String) -> Unit,
    onLoadSamplePrompt: () -> Unit,
    onClearPrompt: () -> Unit,
    onSelectSingleMode: (SingleTimestampMode) -> Unit,
    onUpdateExportSettings: ((ClipEditSettings) -> ClipEditSettings) -> Unit,
    onToggleClipSelection: (String) -> Unit,
    onSelectAllClips: (Boolean) -> Unit,
    onAddManualClip: () -> Unit,
    onEditClipTime: (ParsedClip) -> Unit,
    onOpenClipCaptionEditor: (ParsedClip) -> Unit,
    onDeleteClip: (String) -> Unit,
    onPreviewClip: (PreviewTarget) -> Unit,
    onHighlightClip: (String) -> Unit,
    onOpenPaddingSettings: () -> Unit,
    onStartBatchExport: () -> Unit,
    getClipCaptionCount: (ParsedClip) -> Int,
    loadThumbnail: suspend (String, Long) -> Bitmap?,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 760.dp)
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // STEP 1: LOCAL VIDEO (5-12H) OR YOUTUBE / KICK LINK IMPORTER
                item(key = "step_video_source") {
                    VideoSourceSection(
                        sourceTab = state.sourceInputTab,
                        video = state.selectedVideo,
                        isLoading = state.isLoadingVideo,
                        errorMessage = state.videoErrorMessage,
                        linkUrlInput = state.linkUrlInput,
                        isResolvingLink = state.isResolvingLink,
                        resolvedLink = state.resolvedLink,
                        linkErrorMessage = state.linkErrorMessage,
                        copy = copy,
                        onSelectSourceTab = onSelectSourceTab,
                        onLinkUrlChanged = onLinkUrlChanged,
                        onPasteLink = {
                            clipboardManager.getText()?.text?.let { onLinkUrlChanged(it) }
                        },
                        onResolveLink = onResolveLink,
                        onClearLink = onClearLink,
                        onPickVideoDocument = onPickVideoDocument,
                        onPickVideoGallery = onPickVideoGallery,
                        onPreviewWholeVideo = { video ->
                            onPreviewClip(
                                PreviewTarget(
                                    videoUriString = video.uriString,
                                    title = video.displayName,
                                    startMs = 0L,
                                    endMs = video.durationMs.coerceAtLeast(1000L)
                                )
                            )
                        },
                        loadThumbnail = loadThumbnail
                    )
                }

                // STEP 2: YOUTUBE GEMINI PROMPT INPUT + EXPORT RATIO PRESET + TIMELINE
                item(key = "step_gemini_prompt") {
                    GeminiPromptSection(
                        promptText = state.promptText,
                        hasVideo = state.selectedVideo != null,
                        clips = state.parsedClips,
                        videoDurationMs = state.effectiveTimelineDurationMs,
                        hasInferredSingleTimestamps = state.hasInferredSingleTimestamps,
                        singleMode = state.singleTimestampMode,
                        exportSettings = state.defaultEditSettings,
                        highlightedClipId = state.highlightedClipId,
                        copy = copy,
                        onPromptTextChanged = onPromptTextChanged,
                        onPasteClipboard = {
                            val text = clipboardManager.getText()?.text
                            if (!text.isNullOrBlank()) {
                                onPromptTextChanged(text)
                            }
                        },
                        onLoadSample = onLoadSamplePrompt,
                        onClear = onClearPrompt,
                        onSelectSingleMode = onSelectSingleMode,
                        onUpdateExportSettings = onUpdateExportSettings,
                        onTimelineClipTapped = { tappedClip ->
                            onHighlightClip(tappedClip.id)
                            val targetIdx = state.parsedClips.indexOfFirst { it.id == tappedClip.id }
                            if (targetIdx >= 0) {
                                coroutineScope.launch {
                                    listState.animateScrollToItem(targetIdx + 3)
                                }
                            }
                        }
                    )
                }

                // STEP 3: PARSED CLIPS HEADER
                if (state.parsedClips.isNotEmpty()) {
                    item(key = "step_clips_header") {
                        ClipsQueueHeader(
                            clips = state.parsedClips,
                            selectedCount = state.selectedClipCount,
                            totalSelectedDurationMs = state.totalSelectedDurationMs,
                            copy = copy,
                            onSelectAll = onSelectAllClips,
                            onAddManualClip = onAddManualClip
                        )
                    }

                    // CLIPS ITEMS
                    items(
                        items = state.parsedClips,
                        key = { it.id }
                    ) { clip ->
                        ParsedClipCard(
                            clip = clip,
                            videoUriString = state.selectedVideo?.uriString,
                            resolvedLink = state.resolvedLink,
                            captionCount = getClipCaptionCount(clip),
                            isHighlighted = clip.id == state.highlightedClipId,
                            forceHours = (state.effectiveTimelineDurationMs.takeIf { it > 0L } ?: clip.endMs) >= 3_600_000L,
                            copy = copy,
                            onToggleSelect = { onToggleClipSelection(clip.id) },
                            onEditTime = { onEditClipTime(clip) },
                            onOpenCaptionEditor = { onOpenClipCaptionEditor(clip) },
                            onDelete = { onDeleteClip(clip.id) },
                            onOpenOnlineTimestamp = { link ->
                                val sec = (clip.startMs / 1000L).coerceAtLeast(0L)
                                val deepUrl = when (link.platform) {
                                    VideoLinkPlatform.YOUTUBE -> "https://www.youtube.com/watch?v=${link.videoId}&t=${sec}s"
                                    VideoLinkPlatform.KICK -> link.originalUrl
                                    VideoLinkPlatform.DIRECT_STREAM -> link.originalUrl
                                }
                                runCatching {
                                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(deepUrl)))
                                }
                            },
                            onPreview = {
                                val uri = state.selectedVideo?.uriString
                                if (!uri.isNullOrBlank()) {
                                    onPreviewClip(
                                        PreviewTarget(
                                            videoUriString = uri,
                                            title = clip.title,
                                            startMs = clip.startMs,
                                            endMs = clip.endMs
                                        )
                                    )
                                }
                            },
                            loadThumbnail = loadThumbnail
                        )
                    }
                }
            }

            // STICKY CUT ACTION BAR
            BottomCutActionBar(
                hasVideo = state.selectedVideo != null,
                selectedCount = state.selectedClipCount,
                paddingSeconds = state.paddingSeconds,
                aspectRatioTag = copy.aspectRatioShortTag(state.defaultEditSettings.aspectRatio),
                copy = copy,
                onOpenPaddingSettings = onOpenPaddingSettings,
                onCutClick = onStartBatchExport,
                onPickVideoFallback = onPickVideoDocument
            )
        }
    }
}

@Composable
private fun VideoSourceSection(
    sourceTab: SourceInputTab,
    video: VideoSourceInfo?,
    isLoading: Boolean,
    errorMessage: String?,
    linkUrlInput: String,
    isResolvingLink: Boolean,
    resolvedLink: ResolvedLinkSource?,
    linkErrorMessage: String?,
    copy: UiCopy,
    onSelectSourceTab: (SourceInputTab) -> Unit,
    onLinkUrlChanged: (String) -> Unit,
    onPasteLink: () -> Unit,
    onResolveLink: () -> Unit,
    onClearLink: () -> Unit,
    onPickVideoDocument: () -> Unit,
    onPickVideoGallery: () -> Unit,
    onPreviewWholeVideo: (VideoSourceInfo) -> Unit,
    loadThumbnail: suspend (String, Long) -> Bitmap?
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = StudioCarbon,
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, StudioBorder, RoundedCornerShape(20.dp))
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Source Mode Selector Pills: Local Video | YouTube / Kick Link
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SourceModePill(
                    selected = sourceTab == SourceInputTab.LOCAL_FILE,
                    label = copy.sourceModeLocal,
                    icon = Icons.Rounded.FolderOpen,
                    onClick = { onSelectSourceTab(SourceInputTab.LOCAL_FILE) },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("source_tab_local")
                )
                SourceModePill(
                    selected = sourceTab == SourceInputTab.ONLINE_LINK,
                    label = copy.sourceModeLink,
                    icon = Icons.Rounded.Link,
                    onClick = { onSelectSourceTab(SourceInputTab.ONLINE_LINK) },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("source_tab_link")
                )
            }

            if (sourceTab == SourceInputTab.ONLINE_LINK) {
                // YOUTUBE / KICK LINK IMPORTER PANEL
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedTextField(
                        value = linkUrlInput,
                        onValueChange = onLinkUrlChanged,
                        placeholder = {
                            Text(
                                text = copy.linkPlaceholder,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                            )
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = StudioObsidian,
                            unfocusedContainerColor = StudioObsidian,
                            focusedBorderColor = RazorVermilion,
                            unfocusedBorderColor = StudioBorder
                        ),
                        trailingIcon = {
                            if (linkUrlInput.isNotEmpty()) {
                                IconButton(onClick = onClearLink) {
                                    Icon(Icons.Rounded.Clear, contentDescription = copy.clearText)
                                }
                            } else {
                                IconButton(
                                    onClick = onPasteLink,
                                    modifier = Modifier.testTag("paste_video_link_button")
                                ) {
                                    Icon(Icons.Rounded.ContentPaste, contentDescription = copy.pasteClipboard, tint = TimecodeAmber)
                                }
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("video_link_input")
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = onResolveLink,
                            enabled = linkUrlInput.isNotBlank() && !isResolvingLink,
                            colors = ButtonDefaults.buttonColors(containerColor = RazorVermilion, contentColor = Color.White),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp)
                                .testTag("fetch_video_link_button")
                        ) {
                            if (isResolvingLink) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(copy.fetchingLinkStatus, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            } else {
                                Icon(Icons.Rounded.Link, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(copy.fetchLinkButton)
                            }
                        }
                    }

                    if (!linkErrorMessage.isNullOrBlank()) {
                        Text(
                            text = linkErrorMessage,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }

                    // Resolved YouTube / Kick Card
                    if (resolvedLink != null) {
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = StudioObsidian,
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(1.dp, StudioBorder, RoundedCornerShape(14.dp))
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    if (!resolvedLink.thumbnailUrl.isNullOrBlank()) {
                                        AsyncImage(
                                            model = resolvedLink.thumbnailUrl,
                                            contentDescription = resolvedLink.title,
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier
                                                .size(width = 96.dp, height = 58.dp)
                                                .clip(RoundedCornerShape(10.dp))
                                        )
                                    }
                                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                            Box(
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(6.dp))
                                                    .background(
                                                        if (resolvedLink.platform == VideoLinkPlatform.KICK) {
                                                            LosslessMintContainer
                                                        } else {
                                                            RazorVermilion.copy(alpha = 0.22f)
                                                        }
                                                    )
                                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                                            ) {
                                                Text(
                                                    text = resolvedLink.platform.name,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = if (resolvedLink.platform == VideoLinkPlatform.KICK) LosslessMint else RazorVermilion
                                                )
                                            }
                                            if (resolvedLink.durationMs > 0L) {
                                                Text(
                                                    text = GeminiTimestampParser.formatDurationMs(resolvedLink.durationMs, forceHours = true),
                                                    style = TimecodeStyle,
                                                    color = TimecodeAmber
                                                )
                                            }
                                        }
                                        Text(
                                            text = resolvedLink.title,
                                            style = MaterialTheme.typography.titleSmall,
                                            color = Color.White,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = resolvedLink.channelName,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }

                                if (resolvedLink.fetchedTranscriptCues.isNotEmpty()) {
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(LosslessMintContainer)
                                            .padding(horizontal = 8.dp, vertical = 4.dp)
                                    ) {
                                        Text(
                                            text = copy.autoCaptionsFetchedBadge(resolvedLink.fetchedTranscriptCues.size),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = OnLosslessMintContainer
                                        )
                                    }
                                } else if (!resolvedLink.playableStreamUrl.isNullOrBlank()) {
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(LosslessMintContainer)
                                            .padding(horizontal = 8.dp, vertical = 4.dp)
                                    ) {
                                        Text(
                                            text = copy.directStreamReadyBadge,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = OnLosslessMintContainer
                                        )
                                    }
                                }

                                if (resolvedLink.statusNote.isNotBlank()) {
                                    Text(
                                        text = resolvedLink.statusNote,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }

                                if (resolvedLink.requiresLocalVideoAttach && video == null) {
                                    FilledTonalButton(
                                        onClick = onPickVideoDocument,
                                        shape = RoundedCornerShape(10.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Icon(Icons.Rounded.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(copy.attachLocalForExport, style = MaterialTheme.typography.labelMedium)
                                    }
                                }
                            }
                        }
                    }
                }
            } else if (video == null) {
                // LOCAL VIDEO EMPTY HERO STATE
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(144.dp)
                        .padding(horizontal = 14.dp)
                        .clip(RoundedCornerShape(16.dp))
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.img_studio_hero),
                        contentDescription = stringResource(R.string.content_desc_studio_hero),
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    colors = listOf(
                                        StudioObsidian.copy(alpha = 0.25f),
                                        StudioCarbon.copy(alpha = 0.96f)
                                    )
                                )
                            )
                    )
                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(LosslessMintContainer.copy(alpha = 0.9f))
                                .padding(horizontal = 8.dp, vertical = 3.dp)
                        ) {
                            Text(
                                text = copy.losslessFastBadge,
                                style = MaterialTheme.typography.labelSmall,
                                color = OnLosslessMintContainer
                            )
                        }
                        Text(
                            text = copy.noVideoHeroTitle,
                            style = MaterialTheme.typography.titleLarge,
                            color = Color.White
                        )
                    }
                }

                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = copy.noVideoHeroDesc,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    if (isLoading) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.5.dp, color = RazorVermilion)
                            Text("Reading video metadata...", style = MaterialTheme.typography.bodySmall, color = TimecodeAmber)
                        }
                    }

                    if (errorMessage != null) {
                        Text(text = errorMessage, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = onPickVideoDocument,
                            colors = ButtonDefaults.buttonColors(containerColor = RazorVermilion, contentColor = Color.White),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .weight(1.45f)
                                .heightIn(min = 50.dp)
                                .testTag("pick_video_document_button")
                        ) {
                            Icon(Icons.Rounded.FolderOpen, contentDescription = null, modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(copy.pickVideoFiles, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }

                        FilledTonalButton(
                            onClick = onPickVideoGallery,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 50.dp)
                                .testTag("pick_video_gallery_button")
                        ) {
                            Icon(Icons.Rounded.VideoLibrary, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(copy.pickFromGallery, maxLines = 1)
                        }
                    }
                }
            }

            // Loaded Active Video Telemetry Card (shown below either tab when a video is ready)
            if (video != null) {
                Column(
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(width = 104.dp, height = 66.dp)
                                .clickable { onPreviewWholeVideo(video) }
                        ) {
                            if (!video.thumbnailUrl.isNullOrBlank()) {
                                AsyncImage(
                                    model = video.thumbnailUrl,
                                    contentDescription = video.displayName,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .clip(RoundedCornerShape(10.dp))
                                )
                            } else {
                                VideoFrameThumbnail(
                                    uriString = video.uriString,
                                    timestampMs = (video.durationMs / 10L).coerceAtMost(30_000L),
                                    loadThumbnail = loadThumbnail,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                            Box(
                                modifier = Modifier
                                    .align(Alignment.Center)
                                    .size(28.dp)
                                    .clip(CircleShape)
                                    .background(Color.Black.copy(alpha = 0.6f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.PlayArrow,
                                    contentDescription = copy.previewVideo,
                                    tint = Color.White,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }

                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = video.displayName,
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = if (video.durationMs > 0L) {
                                    GeminiTimestampParser.formatDurationMs(video.durationMs, forceHours = true)
                                } else {
                                    "LIVE / STREAM"
                                },
                                style = TimecodeLargeStyle,
                                color = TimecodeAmber
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                if (video.width > 0 && video.height > 0) {
                                    MetaChip("${video.width}×${video.height}")
                                }
                                MetaChip(video.videoCodec)
                                if (video.fileSizeBytes > 0L) {
                                    MetaChip(GeminiTimestampParser.formatFileSize(video.fileSizeBytes))
                                }
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = onPickVideoDocument,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("change_video_button")
                        ) {
                            Icon(Icons.Rounded.SwapHoriz, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(copy.changeVideo)
                        }
                        FilledTonalButton(
                            onClick = { onPreviewWholeVideo(video) },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("preview_source_video_button")
                        ) {
                            Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(copy.previewVideo)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SourceModePill(
    selected: Boolean,
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = if (selected) RazorVermilion else StudioObsidian,
        modifier = modifier
            .heightIn(min = 44.dp)
            .border(1.dp, if (selected) RazorVermilion else StudioBorder, RoundedCornerShape(12.dp))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (selected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(17.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                color = if (selected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun MetaChip(label: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(StudioSlate)
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GeminiPromptSection(
    promptText: String,
    hasVideo: Boolean,
    clips: List<ParsedClip>,
    videoDurationMs: Long,
    hasInferredSingleTimestamps: Boolean,
    singleMode: SingleTimestampMode,
    exportSettings: ClipEditSettings,
    highlightedClipId: String?,
    copy: UiCopy,
    onPromptTextChanged: (String) -> Unit,
    onPasteClipboard: () -> Unit,
    onLoadSample: () -> Unit,
    onClear: () -> Unit,
    onSelectSingleMode: (SingleTimestampMode) -> Unit,
    onUpdateExportSettings: ((ClipEditSettings) -> ClipEditSettings) -> Unit,
    onTimelineClipTapped: (ParsedClip) -> Unit
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = StudioCarbon,
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, StudioBorder, RoundedCornerShape(20.dp))
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.AutoAwesome,
                        contentDescription = null,
                        tint = TimecodeAmber,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = copy.step2Title,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = copy.step2Subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Quick Action Pills: Paste | 10h Sample | Clear
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilledTonalButton(
                    onClick = onPasteClipboard,
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    modifier = Modifier.testTag("paste_gemini_prompt_button")
                ) {
                    Icon(Icons.Rounded.ContentPaste, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(copy.pasteClipboard, style = MaterialTheme.typography.labelMedium)
                }

                OutlinedButton(
                    onClick = onLoadSample,
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    modifier = Modifier.testTag("load_sample_prompt_button")
                ) {
                    Icon(Icons.Rounded.AutoAwesome, contentDescription = null, modifier = Modifier.size(16.dp), tint = TimecodeAmber)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(copy.loadDemoPrompt, style = MaterialTheme.typography.labelMedium)
                }

                if (promptText.isNotBlank()) {
                    TextButton(
                        onClick = onClear,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                        modifier = Modifier.testTag("clear_prompt_button")
                    ) {
                        Icon(Icons.Rounded.Clear, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(copy.clearText, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }

            OutlinedTextField(
                value = promptText,
                onValueChange = onPromptTextChanged,
                placeholder = {
                    Text(
                        text = copy.promptPlaceholder,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f)
                    )
                },
                minLines = 4,
                maxLines = 9,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = StudioObsidian,
                    unfocusedContainerColor = StudioObsidian,
                    focusedBorderColor = TimecodeAmber,
                    unfocusedBorderColor = StudioBorder
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("gemini_prompt_input")
            )

            // Quick 9:16 HD Export Preset Row
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = copy.exportPresetTitle,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    ExportAspectRatio.entries.forEach { ratio ->
                        FilterChip(
                            selected = exportSettings.aspectRatio == ratio,
                            onClick = { onUpdateExportSettings { it.copy(aspectRatio = ratio) } },
                            label = { Text(copy.aspectRatioLabel(ratio), style = MaterialTheme.typography.labelSmall) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = RazorVermilion,
                                selectedLabelColor = Color.White
                            ),
                            modifier = Modifier.testTag("studio_ratio_${ratio.name}")
                        )
                    }
                }
            }

            AnimatedVisibility(visible = !hasVideo && clips.isNotEmpty(), enter = fadeIn(), exit = fadeOut()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(TimecodeAmberContainer.copy(alpha = 0.7f))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Rounded.WarningAmber, contentDescription = null, tint = TimecodeAmber, modifier = Modifier.size(18.dp))
                    Text(text = copy.demoModeBanner, style = MaterialTheme.typography.bodySmall, color = Color(0xFFFFE5B8))
                }
            }

            AnimatedVisibility(visible = hasInferredSingleTimestamps) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(text = copy.singleModeLabel, style = MaterialTheme.typography.labelMedium, color = TimecodeAmber)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        SingleTimestampMode.entries.forEach { mode ->
                            FilterChip(
                                selected = singleMode == mode,
                                onClick = { onSelectSingleMode(mode) },
                                label = { Text(copy.singleModeName(mode), style = MaterialTheme.typography.labelSmall) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = TimecodeAmber,
                                    selectedLabelColor = Color(0xFF1F1600)
                                )
                            )
                        }
                    }
                }
            }

            AnimatedVisibility(visible = clips.isNotEmpty()) {
                MultiHourTimelineBar(
                    clips = clips,
                    totalDurationMs = videoDurationMs,
                    highlightedClipId = highlightedClipId,
                    label = copy.timelineLabel,
                    onClipTapped = onTimelineClipTapped
                )
            }
        }
    }
}

@Composable
private fun ClipsQueueHeader(
    clips: List<ParsedClip>,
    selectedCount: Int,
    totalSelectedDurationMs: Long,
    copy: UiCopy,
    onSelectAll: (Boolean) -> Unit,
    onAddManualClip: () -> Unit
) {
    val allSelected = selectedCount > 0 && selectedCount == clips.count { !it.isOutOfBounds }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = copy.step3Title,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = copy.clipsFoundSummary(
                    clips.size,
                    GeminiTimestampParser.formatHumanLength(totalSelectedDurationMs)
                ),
                style = MaterialTheme.typography.bodySmall,
                color = LosslessMint
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(
                onClick = { onSelectAll(!allSelected) },
                modifier = Modifier.testTag("toggle_select_all_button")
            ) {
                Text(
                    text = if (allSelected) copy.deselectAll else copy.selectAll,
                    style = MaterialTheme.typography.labelMedium
                )
            }
            FilledTonalButton(
                onClick = onAddManualClip,
                shape = RoundedCornerShape(10.dp),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                modifier = Modifier.testTag("add_manual_clip_button")
            ) {
                Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text(copy.addManualClip.removePrefix("+ "), style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
private fun ParsedClipCard(
    clip: ParsedClip,
    videoUriString: String?,
    resolvedLink: ResolvedLinkSource?,
    captionCount: Int,
    isHighlighted: Boolean,
    forceHours: Boolean,
    copy: UiCopy,
    onToggleSelect: () -> Unit,
    onEditTime: () -> Unit,
    onOpenCaptionEditor: () -> Unit,
    onDelete: () -> Unit,
    onOpenOnlineTimestamp: (ResolvedLinkSource) -> Unit,
    onPreview: () -> Unit,
    loadThumbnail: suspend (String, Long) -> Bitmap?
) {
    val borderColor = when {
        clip.isOutOfBounds -> MaterialTheme.colorScheme.error.copy(alpha = 0.7f)
        isHighlighted -> LosslessMint
        clip.isSelected -> StudioBorder
        else -> StudioBorder.copy(alpha = 0.45f)
    }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = if (clip.isSelected) StudioCarbon else StudioCarbon.copy(alpha = 0.55f),
        modifier = Modifier
            .fillMaxWidth()
            .border(if (isHighlighted) 1.5.dp else 1.dp, borderColor, RoundedCornerShape(16.dp))
            .testTag("parsed_clip_card_${clip.index}")
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onToggleSelect,
                    enabled = !clip.isOutOfBounds,
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        imageVector = if (clip.isSelected && !clip.isOutOfBounds) {
                            Icons.Rounded.CheckBox
                        } else {
                            Icons.Rounded.CheckBoxOutlineBlank
                        },
                        contentDescription = clip.title,
                        tint = if (clip.isSelected && !clip.isOutOfBounds) RazorVermilion else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Box(
                    modifier = Modifier
                        .size(width = 76.dp, height = 52.dp)
                        .clickable(enabled = !videoUriString.isNullOrBlank()) { onPreview() }
                ) {
                    VideoFrameThumbnail(
                        uriString = videoUriString,
                        timestampMs = clip.startMs,
                        loadThumbnail = loadThumbnail,
                        modifier = Modifier.fillMaxSize()
                    )
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(3.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color.Black.copy(alpha = 0.72f))
                            .padding(horizontal = 4.dp, vertical = 1.dp)
                    ) {
                        Text(
                            text = "#${clip.index.toString().padStart(2, '0')}",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White
                        )
                    }
                }

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Text(
                        text = clip.title,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )

                    Text(
                        text = "${GeminiTimestampParser.formatDurationMs(clip.startMs, forceHours)} → ${GeminiTimestampParser.formatDurationMs(clip.endMs, forceHours)}",
                        style = TimecodeStyle,
                        color = if (clip.isOutOfBounds) MaterialTheme.colorScheme.error else TimecodeAmber
                    )

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        MetaChip(GeminiTimestampParser.formatHumanLength(clip.durationMs))
                        if (captionCount > 0) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(LosslessMintContainer)
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = copy.captionsCountBadge(captionCount),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = OnLosslessMintContainer
                                )
                            }
                        }
                        if (clip.isSingleTimestampInferred) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(TimecodeAmberContainer)
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(text = copy.inferredEndTag, style = MaterialTheme.typography.labelSmall, color = TimecodeAmber)
                            }
                        }
                        if (clip.isOutOfBounds) {
                            Text(text = copy.outOfBoundsWarning, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }

            if (clip.note.isNotBlank()) {
                Text(
                    text = clip.note,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 10.dp)
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (!videoUriString.isNullOrBlank() && !clip.isOutOfBounds) {
                        FilledTonalButton(
                            onClick = onOpenCaptionEditor,
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                            modifier = Modifier.testTag("open_9_16_editor_button_${clip.index}")
                        ) {
                            Icon(Icons.Rounded.Subtitles, contentDescription = null, modifier = Modifier.size(16.dp), tint = TimecodeAmber)
                            Spacer(modifier = Modifier.width(5.dp))
                            Text(copy.editCaptionsButton, style = MaterialTheme.typography.labelSmall, color = TimecodeAmber)
                        }
                    } else if (resolvedLink != null) {
                        OutlinedButton(
                            onClick = { onOpenOnlineTimestamp(resolvedLink) },
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Icon(Icons.Rounded.OpenInNew, contentDescription = null, modifier = Modifier.size(15.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(copy.openAtTimestamp, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (!videoUriString.isNullOrBlank() && !clip.isOutOfBounds) {
                        IconButton(
                            onClick = onPreview,
                            modifier = Modifier.testTag("preview_clip_button_${clip.index}")
                        ) {
                            Icon(
                                Icons.Rounded.PlayArrow,
                                contentDescription = stringResource(R.string.content_desc_play_clip),
                                tint = LosslessMint
                            )
                        }
                    }
                    IconButton(
                        onClick = onEditTime,
                        modifier = Modifier.testTag("edit_clip_button_${clip.index}")
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Edit,
                            contentDescription = stringResource(R.string.content_desc_edit_clip),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(
                        onClick = onDelete,
                        modifier = Modifier.testTag("delete_clip_button_${clip.index}")
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.DeleteOutline,
                            contentDescription = stringResource(R.string.content_desc_delete_clip),
                            tint = MaterialTheme.colorScheme.error.copy(alpha = 0.85f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BottomCutActionBar(
    hasVideo: Boolean,
    selectedCount: Int,
    paddingSeconds: Int,
    aspectRatioTag: String,
    copy: UiCopy,
    onOpenPaddingSettings: () -> Unit,
    onCutClick: () -> Unit,
    onPickVideoFallback: () -> Unit
) {
    Surface(
        color = StudioCarbon,
        tonalElevation = 6.dp,
        modifier = Modifier
            .fillMaxWidth()
            .border(width = 1.dp, color = StudioBorder, shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedButton(
                onClick = onOpenPaddingSettings,
                shape = RoundedCornerShape(14.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
                modifier = Modifier
                    .heightIn(min = 52.dp)
                    .testTag("padding_settings_button")
            ) {
                Icon(
                    imageVector = Icons.Rounded.Tune,
                    contentDescription = stringResource(R.string.content_desc_settings),
                    tint = TimecodeAmber,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = if (paddingSeconds == 0) "±0s" else "±${paddingSeconds}s",
                    style = TimecodeStyle
                )
            }

            val canCut = hasVideo && selectedCount > 0
            Button(
                onClick = {
                    if (canCut) {
                        onCutClick()
                    } else if (!hasVideo) {
                        onPickVideoFallback()
                    }
                },
                enabled = canCut || !hasVideo,
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (canCut) RazorVermilion else StudioSlate,
                    contentColor = Color.White,
                    disabledContainerColor = StudioSlate,
                    disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant
                ),
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 52.dp)
                    .testTag("cut_clips_cta_button")
            ) {
                Icon(
                    imageVector = if (canCut) Icons.Rounded.ContentCut else Icons.Rounded.FolderOpen,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = when {
                        canCut -> copy.cutSelectedCta(selectedCount, aspectRatioTag)
                        !hasVideo -> copy.pickVideoFirstCta
                        else -> copy.pasteTimestampsCta
                    },
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
