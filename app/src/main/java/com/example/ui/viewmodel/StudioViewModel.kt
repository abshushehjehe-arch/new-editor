package com.example.ui.viewmodel

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.local.ClipProjectEntity
import com.example.data.local.ClipSliceDatabase
import com.example.data.local.ExportedClipEntity
import com.example.data.repository.ClipSliceRepository
import com.example.domain.model.AppLanguage
import com.example.domain.model.ClipEditSettings
import com.example.domain.model.ExportAspectRatio
import com.example.domain.model.ExportProgressState
import com.example.domain.model.ExportResolution
import com.example.domain.model.ParsedClip
import com.example.domain.model.ResolvedLinkSource
import com.example.domain.model.SingleTimestampMode
import com.example.domain.model.SubtitleCue
import com.example.domain.model.SubtitlePosition
import com.example.domain.model.SubtitleStylePreset
import com.example.domain.model.VideoSourceInfo
import com.example.domain.parser.GeminiTimestampParser
import com.example.domain.parser.SubtitleParser
import com.example.media.AiCaptionRepository
import com.example.media.LocalFallbackAiCaptionRepository
import com.example.media.LosslessVideoTrimmer
import com.example.media.OnlineVideoLinkResolver
import com.example.media.VerticalHdClipRenderer
import com.example.media.VideoMetadataHelper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class SourceInputTab {
    LOCAL_FILE,
    ONLINE_LINK
}

data class ClipEditorSession(
    val clipId: String,
    val clipIndex: Int,
    val sourceUriString: String,
    val projectFolderName: String,
    val title: String,
    val startMs: Long,
    val endMs: Long,
    val settings: ClipEditSettings,
    val cues: List<SubtitleCue>,
    val aiNoticeMessage: String? = null
) {
    val clipDurationMs: Long
        get() = (endMs - startMs).coerceAtLeast(1_000L)
}

data class StudioUiState(
    val language: AppLanguage = AppLanguage.HINDI,
    val sourceInputTab: SourceInputTab = SourceInputTab.LOCAL_FILE,
    val selectedVideo: VideoSourceInfo? = null,
    val isLoadingVideo: Boolean = false,
    val videoErrorMessage: String? = null,
    val linkUrlInput: String = "",
    val isResolvingLink: Boolean = false,
    val resolvedLink: ResolvedLinkSource? = null,
    val linkErrorMessage: String? = null,
    val promptText: String = "",
    val singleTimestampMode: SingleTimestampMode = SingleTimestampMode.NEXT_TIMESTAMP,
    val hasInferredSingleTimestamps: Boolean = false,
    val parsedClips: List<ParsedClip> = emptyList(),
    val paddingSeconds: Int = 0,
    val defaultEditSettings: ClipEditSettings = ClipEditSettings(
        aspectRatio = ExportAspectRatio.VERTICAL_9_16_CROP,
        resolution = ExportResolution.FULL_HD_1080P,
        subtitlePosition = SubtitlePosition.BOTTOM,
        subtitleStyle = SubtitleStylePreset.VIRAL_YELLOW,
        subtitlesEnabled = true
    ),
    val globalVideoSubtitles: List<SubtitleCue> = emptyList(),
    val clipCustomCues: Map<String, List<SubtitleCue>> = emptyMap(),
    val exportState: ExportProgressState = ExportProgressState.Idle,
    val editingClip: ParsedClip? = null,
    val editorSession: ClipEditorSession? = null,
    val previewingClip: PreviewTarget? = null,
    val showGuideDialog: Boolean = false,
    val showPaddingDialog: Boolean = false,
    val activeProjectId: Long = 0L,
    val highlightedClipId: String? = null,
    val statusSnackbar: String? = null
) {
    val selectedClipCount: Int
        get() = parsedClips.count { it.isSelected && !it.isOutOfBounds && it.durationMs > 0L }

    val totalSelectedDurationMs: Long
        get() = parsedClips
            .filter { it.isSelected && !it.isOutOfBounds }
            .sumOf { it.durationMs }

    val effectiveTimelineDurationMs: Long
        get() = selectedVideo?.durationMs?.takeIf { it > 0L }
            ?: resolvedLink?.durationMs?.takeIf { it > 0L }
            ?: 0L
}

data class PreviewTarget(
    val videoUriString: String,
    val title: String,
    val startMs: Long,
    val endMs: Long
)

class StudioViewModel(application: Application) : AndroidViewModel(application) {

    private val database = ClipSliceDatabase.getInstance(application)
    private val repository = ClipSliceRepository(database.projectDao(), database.exportedClipDao())
    private val metadataHelper = VideoMetadataHelper(application)
    private val losslessTrimmer = LosslessVideoTrimmer(application)
    private val verticalHdRenderer = VerticalHdClipRenderer(application)
    private val linkResolver = OnlineVideoLinkResolver()
    private val aiCaptionRepository: AiCaptionRepository = LocalFallbackAiCaptionRepository()

    private val _uiState = MutableStateFlow(StudioUiState())
    val uiState: StateFlow<StudioUiState> = _uiState.asStateFlow()

    val savedProjects: StateFlow<List<ClipProjectEntity>> = repository.projects
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val exportedClips: StateFlow<List<ExportedClipEntity>> = repository.exportedClips
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private var exportJob: Job? = null

    fun toggleLanguage() {
        _uiState.update { state ->
            state.copy(
                language = if (state.language == AppLanguage.HINDI) {
                    AppLanguage.ENGLISH
                } else {
                    AppLanguage.HINDI
                }
            )
        }
    }

    fun selectSourceTab(tab: SourceInputTab) {
        _uiState.update { it.copy(sourceInputTab = tab) }
    }

    fun onLinkUrlChanged(url: String) {
        _uiState.update { it.copy(linkUrlInput = url, linkErrorMessage = null) }
    }

    fun resolveVideoLink() {
        val rawUrl = _uiState.value.linkUrlInput.trim()
        if (rawUrl.isEmpty()) return

        _uiState.update { it.copy(isResolvingLink = true, linkErrorMessage = null) }
        viewModelScope.launch {
            linkResolver.resolve(rawUrl)
                .onSuccess { resolved ->
                    _uiState.update { state ->
                        val remoteVideo = if (!resolved.playableStreamUrl.isNullOrBlank()) {
                            VideoSourceInfo(
                                uriString = resolved.playableStreamUrl,
                                displayName = resolved.title,
                                durationMs = resolved.durationMs,
                                width = 1920,
                                height = 1080,
                                fileSizeBytes = 0L,
                                rotationDegrees = 0,
                                videoCodec = "HLS/MP4",
                                audioCodec = "AAC",
                                isRemoteStream = true,
                                thumbnailUrl = resolved.thumbnailUrl,
                                platformBadge = resolved.platform.name
                            )
                        } else {
                            // Keep existing local video if attached, or wait for attach
                            state.selectedVideo
                        }

                        val durForBounds = remoteVideo?.durationMs?.takeIf { it > 0L }
                            ?: resolved.durationMs.takeIf { it > 0L }

                        val parsed = GeminiTimestampParser.parse(
                            rawText = state.promptText,
                            videoDurationMs = durForBounds,
                            singleMode = state.singleTimestampMode
                        )

                        state.copy(
                            isResolvingLink = false,
                            resolvedLink = resolved,
                            selectedVideo = remoteVideo,
                            globalVideoSubtitles = if (resolved.fetchedTranscriptCues.isNotEmpty()) {
                                resolved.fetchedTranscriptCues
                            } else {
                                state.globalVideoSubtitles
                            },
                            parsedClips = parsed.clips,
                            hasInferredSingleTimestamps = parsed.hasInferredSingleTimestamps,
                            linkErrorMessage = null
                        )
                    }
                }
                .onFailure { err ->
                    _uiState.update {
                        it.copy(
                            isResolvingLink = false,
                            linkErrorMessage = err.localizedMessage ?: "Unable to load link"
                        )
                    }
                }
        }
    }

    fun clearResolvedLink() {
        _uiState.update { state ->
            state.copy(
                resolvedLink = null,
                linkUrlInput = "",
                linkErrorMessage = null,
                selectedVideo = if (state.selectedVideo?.isRemoteStream == true) null else state.selectedVideo
            )
        }
    }

    fun onVideoSelected(uri: Uri) {
        val ctx = getApplication<Application>()
        runCatching {
            ctx.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }

        _uiState.update { it.copy(isLoadingVideo = true, videoErrorMessage = null) }
        viewModelScope.launch {
            metadataHelper.inspectVideo(uri)
                .onSuccess { info ->
                    _uiState.update { state ->
                        val parsed = GeminiTimestampParser.parse(
                            rawText = state.promptText,
                            videoDurationMs = info.durationMs.takeIf { it > 0L },
                            singleMode = state.singleTimestampMode
                        )
                        state.copy(
                            selectedVideo = info,
                            isLoadingVideo = false,
                            videoErrorMessage = null,
                            parsedClips = parsed.clips,
                            hasInferredSingleTimestamps = parsed.hasInferredSingleTimestamps
                        )
                    }
                    persistCurrentProjectIfReady()
                }
                .onFailure { err ->
                    _uiState.update {
                        it.copy(
                            isLoadingVideo = false,
                            videoErrorMessage = err.localizedMessage ?: "Failed to read video file"
                        )
                    }
                }
        }
    }

    fun onPromptTextChanged(newText: String) {
        _uiState.update { state ->
            val durationMs = state.effectiveTimelineDurationMs.takeIf { it > 0L }
            val result = GeminiTimestampParser.parse(
                rawText = newText,
                videoDurationMs = durationMs,
                singleMode = state.singleTimestampMode
            )
            state.copy(
                promptText = newText,
                parsedClips = result.clips,
                hasInferredSingleTimestamps = result.hasInferredSingleTimestamps
            )
        }
        persistCurrentProjectIfReady()
    }

    fun loadSamplePrompt() {
        onPromptTextChanged(GeminiTimestampParser.SAMPLE_YOUTUBE_GEMINI_PROMPT)
    }

    fun clearPrompt() {
        _uiState.update {
            it.copy(
                promptText = "",
                parsedClips = emptyList(),
                hasInferredSingleTimestamps = false
            )
        }
    }

    fun setSingleTimestampMode(mode: SingleTimestampMode) {
        _uiState.update { state ->
            val durationMs = state.effectiveTimelineDurationMs.takeIf { it > 0L }
            val result = GeminiTimestampParser.parse(
                rawText = state.promptText,
                videoDurationMs = durationMs,
                singleMode = mode
            )
            state.copy(
                singleTimestampMode = mode,
                parsedClips = result.clips,
                hasInferredSingleTimestamps = result.hasInferredSingleTimestamps
            )
        }
    }

    fun toggleClipSelection(clipId: String) {
        _uiState.update { state ->
            state.copy(
                parsedClips = state.parsedClips.map { clip ->
                    if (clip.id == clipId) clip.copy(isSelected = !clip.isSelected) else clip
                }
            )
        }
    }

    fun selectAllClips(select: Boolean) {
        _uiState.update { state ->
            state.copy(
                parsedClips = state.parsedClips.map { clip ->
                    if (clip.isOutOfBounds) clip else clip.copy(isSelected = select)
                }
            )
        }
    }

    fun removeClip(clipId: String) {
        _uiState.update { state ->
            val remaining = state.parsedClips
                .filterNot { it.id == clipId }
                .mapIndexed { idx, clip -> clip.copy(index = idx + 1) }
            state.copy(parsedClips = remaining)
        }
    }

    fun openEditClipDialog(clip: ParsedClip) {
        _uiState.update { it.copy(editingClip = clip) }
    }

    fun openAddManualClipDialog() {
        val state = _uiState.value
        val nextIndex = state.parsedClips.size + 1
        val lastEnd = state.parsedClips.lastOrNull()?.endMs ?: 0L
        val maxDur = state.effectiveTimelineDurationMs.takeIf { it > 0L } ?: (lastEnd + 60_000L)
        val defaultStart = lastEnd.coerceAtMost((maxDur - 5_000L).coerceAtLeast(0L))
        val defaultEnd = (defaultStart + 60_000L).coerceAtMost(maxDur)
        val newClip = ParsedClip(
            id = "manual_${System.currentTimeMillis()}",
            index = nextIndex,
            title = "Clip $nextIndex",
            startMs = defaultStart,
            endMs = if (defaultEnd > defaultStart) defaultEnd else defaultStart + 30_000L,
            isSelected = true
        )
        _uiState.update { it.copy(editingClip = newClip) }
    }

    fun dismissEditClipDialog() {
        _uiState.update { it.copy(editingClip = null) }
    }

    fun saveEditedClip(updated: ParsedClip) {
        _uiState.update { state ->
            val videoDur = state.effectiveTimelineDurationMs.takeIf { it > 0L }
            val outOfBounds = videoDur != null && updated.startMs >= videoDur
            val safeEnd = if (videoDur != null) updated.endMs.coerceAtMost(videoDur) else updated.endMs
            val normalized = updated.copy(
                endMs = safeEnd,
                isOutOfBounds = outOfBounds,
                isSelected = !outOfBounds
            )
            val exists = state.parsedClips.any { it.id == normalized.id }
            val updatedList = if (exists) {
                state.parsedClips.map { if (it.id == normalized.id) normalized else it }
            } else {
                state.parsedClips + normalized
            }.sortedBy { it.startMs }
                .mapIndexed { idx, c -> c.copy(index = idx + 1) }

            state.copy(
                parsedClips = updatedList,
                editingClip = null
            )
        }
    }

    // ---- 9:16 HD & Captions Editor Actions ----

    fun getCuesForParsedClip(clip: ParsedClip): List<SubtitleCue> {
        val state = _uiState.value
        state.clipCustomCues[clip.id]?.let { return it }
        if (state.globalVideoSubtitles.isNotEmpty()) {
            return SubtitleParser.sliceCuesForClip(
                allVideoCues = state.globalVideoSubtitles,
                clipStartMs = clip.startMs,
                clipEndMs = clip.endMs
            )
        }
        return emptyList()
    }

    fun openClipEditorForParsedClip(clip: ParsedClip) {
        val state = _uiState.value
        val video = state.selectedVideo ?: return
        val initialCues = getCuesForParsedClip(clip)
        _uiState.update {
            it.copy(
                editorSession = ClipEditorSession(
                    clipId = clip.id,
                    clipIndex = clip.index,
                    sourceUriString = video.uriString,
                    projectFolderName = video.displayName.substringBeforeLast('.'),
                    title = clip.title,
                    startMs = clip.startMs,
                    endMs = clip.endMs,
                    settings = state.defaultEditSettings,
                    cues = initialCues
                )
            )
        }
    }

    fun openClipEditorForExportedClip(exported: ExportedClipEntity) {
        val state = _uiState.value
        _uiState.update {
            it.copy(
                editorSession = ClipEditorSession(
                    clipId = "exported_${exported.id}",
                    clipIndex = 1,
                    sourceUriString = exported.outputUriString,
                    projectFolderName = exported.sourceVideoName.substringBeforeLast('.'),
                    title = exported.clipTitle,
                    startMs = 0L,
                    endMs = exported.durationMs.coerceAtLeast(1_000L),
                    settings = state.defaultEditSettings.copy(
                        aspectRatio = ExportAspectRatio.VERTICAL_9_16_CROP,
                        resolution = ExportResolution.FULL_HD_1080P,
                        subtitlesEnabled = true
                    ),
                    cues = emptyList()
                )
            )
        }
    }

    fun closeClipEditor() {
        _uiState.update { state ->
            val session = state.editorSession
            if (session != null) {
                state.copy(
                    editorSession = null,
                    defaultEditSettings = session.settings,
                    clipCustomCues = state.clipCustomCues + (session.clipId to session.cues)
                )
            } else {
                state.copy(editorSession = null)
            }
        }
    }

    fun updateEditorSettings(transform: (ClipEditSettings) -> ClipEditSettings) {
        _uiState.update { state ->
            val session = state.editorSession ?: return@update state
            val updated = transform(session.settings)
            state.copy(
                editorSession = session.copy(settings = updated),
                defaultEditSettings = updated
            )
        }
    }

    fun updateDefaultExportSettings(transform: (ClipEditSettings) -> ClipEditSettings) {
        _uiState.update { state ->
            state.copy(defaultEditSettings = transform(state.defaultEditSettings))
        }
    }

    fun importSubtitleTextIntoEditor(rawText: String) {
        val session = _uiState.value.editorSession ?: return
        val parsed = SubtitleParser.parseAny(rawText)
        val finalCues = if (parsed.isNotEmpty()) {
            // If timestamps look like absolute video timestamps (beyond clip duration), auto-slice them!
            val looksAbsolute = parsed.any { it.startMs > session.clipDurationMs + 5_000L } && session.startMs > 0L
            if (looksAbsolute) {
                val sliced = SubtitleParser.sliceCuesForClip(parsed, session.startMs, session.endMs)
                // Also store globally for other clips in the same video
                _uiState.update { it.copy(globalVideoSubtitles = parsed) }
                sliced
            } else {
                SubtitleParser.chunkForVerticalCaptions(parsed)
            }
        } else {
            // Plain script text without timestamps -> distribute cleanly across clip duration
            SubtitleParser.createTimedCuesFromScript(rawText, session.clipDurationMs)
        }

        _uiState.update { state ->
            val cur = state.editorSession ?: return@update state
            state.copy(
                editorSession = cur.copy(
                    cues = finalCues,
                    settings = cur.settings.copy(subtitlesEnabled = true)
                ),
                clipCustomCues = state.clipCustomCues + (cur.clipId to finalCues)
            )
        }
    }

    fun importSubtitleFileUri(uri: Uri) {
        viewModelScope.launch {
            val content = withContext(Dispatchers.IO) {
                runCatching {
                    getApplication<Application>().contentResolver.openInputStream(uri)
                        ?.bufferedReader()
                        ?.use { it.readText() }
                        .orEmpty()
                }.getOrDefault("")
            }
            if (content.isNotBlank()) {
                importSubtitleTextIntoEditor(content)
            }
        }
    }

    fun addOrUpdateEditorCue(cue: SubtitleCue) {
        _uiState.update { state ->
            val session = state.editorSession ?: return@update state
            val exists = session.cues.any { it.id == cue.id }
            val updated = if (exists) {
                session.cues.map { if (it.id == cue.id) cue else it }
            } else {
                session.cues + cue
            }.sortedBy { it.startMs }
            state.copy(
                editorSession = session.copy(
                    cues = updated,
                    settings = session.settings.copy(subtitlesEnabled = true)
                ),
                clipCustomCues = state.clipCustomCues + (session.clipId to updated)
            )
        }
    }

    fun appendSpokenTextAtPosition(spokenText: String, currentRelMs: Long) {
        val cleaned = spokenText.trim()
        if (cleaned.isEmpty()) return
        val session = _uiState.value.editorSession ?: return
        val start = currentRelMs.coerceIn(0L, (session.clipDurationMs - 1000L).coerceAtLeast(0L))
        val estDur = (cleaned.split(Regex("""\s+""")).size * 420L).coerceIn(1_800L, 4_500L)
        val end = (start + estDur).coerceAtMost(session.clipDurationMs)
        val chunked = SubtitleParser.chunkForVerticalCaptions(
            listOf(
                SubtitleCue(
                    id = "mic_${System.currentTimeMillis()}",
                    startMs = start,
                    endMs = maxOf(end, start + 1_200L),
                    text = cleaned
                )
            )
        )
        _uiState.update { state ->
            val cur = state.editorSession ?: return@update state
            val merged = (cur.cues + chunked).sortedBy { it.startMs }
            state.copy(
                editorSession = cur.copy(
                    cues = merged,
                    settings = cur.settings.copy(subtitlesEnabled = true)
                ),
                clipCustomCues = state.clipCustomCues + (cur.clipId to merged)
            )
        }
    }

    fun deleteEditorCue(cueId: String) {
        _uiState.update { state ->
            val session = state.editorSession ?: return@update state
            val updated = session.cues.filterNot { it.id == cueId }
            state.copy(
                editorSession = session.copy(cues = updated),
                clipCustomCues = state.clipCustomCues + (session.clipId to updated)
            )
        }
    }

    fun triggerAiAutoCaptionPlaceholder() {
        val session = _uiState.value.editorSession ?: return
        val isHindi = _uiState.value.language == AppLanguage.HINDI
        viewModelScope.launch {
            val res = aiCaptionRepository.transcribeClip(session.clipDurationMs, session.title)
            _uiState.update { state ->
                val cur = state.editorSession ?: return@update state
                val newCues = if (cur.cues.isEmpty()) res.cues else cur.cues
                state.copy(
                    editorSession = cur.copy(
                        cues = newCues,
                        aiNoticeMessage = if (isHindi) res.noticeMessageHindi else res.noticeMessageEnglish
                    )
                )
            }
        }
    }

    fun exportSingleEditedClip() {
        val session = _uiState.value.editorSession ?: return
        val stateSnapshot = _uiState.value
        val paddingMs = if (session.clipId.startsWith("exported_")) 0L else stateSnapshot.paddingSeconds * 1000L

        exportJob?.cancel()
        exportJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    exportState = ExportProgressState.Running(
                        currentIndex = 1,
                        totalCount = 1,
                        currentClipTitle = session.title,
                        currentClipProgress = 0f,
                        overallProgress = 0f,
                        completedCount = 0,
                        modeLabel = "9:16 HD"
                    )
                )
            }

            try {
                val res = verticalHdRenderer.renderClip(
                    sourceUriString = session.sourceUriString,
                    projectFolderName = "${session.projectFolderName}_9x16_HD",
                    clipIndex = session.clipIndex,
                    clipTitle = session.title,
                    startMs = session.startMs,
                    endMs = session.endMs,
                    paddingMs = paddingMs,
                    editSettings = session.settings,
                    cues = if (session.settings.subtitlesEnabled) session.cues else emptyList(),
                    onProgress = { progress ->
                        _uiState.update { cur ->
                            cur.copy(
                                exportState = ExportProgressState.Running(
                                    currentIndex = 1,
                                    totalCount = 1,
                                    currentClipTitle = session.title,
                                    currentClipProgress = progress,
                                    overallProgress = progress,
                                    completedCount = 0,
                                    modeLabel = "9:16 HD"
                                )
                            )
                        }
                    }
                )

                res.onSuccess { rendered ->
                    repository.recordExportedClip(
                        ExportedClipEntity(
                            outputUriString = rendered.outputUriString,
                            displayName = rendered.displayName,
                            clipTitle = session.title,
                            sourceVideoName = session.projectFolderName,
                            startMs = session.startMs,
                            endMs = session.endMs,
                            durationMs = rendered.actualDurationMs,
                            fileSizeBytes = rendered.fileSizeBytes,
                            folderLabel = rendered.folderLabel
                        )
                    )
                    _uiState.update {
                        it.copy(
                            editorSession = null,
                            exportState = ExportProgressState.Completed(
                                exportedCount = 1,
                                failedCount = 0,
                                failedMessages = emptyList(),
                                outputFolderLabel = rendered.folderLabel
                            )
                        )
                    }
                }.onFailure { err ->
                    if (err is CancellationException) throw err
                    _uiState.update {
                        it.copy(
                            exportState = ExportProgressState.Error(
                                err.localizedMessage ?: "9:16 HD render failed"
                            )
                        )
                    }
                }
            } catch (_: CancellationException) {
                _uiState.update { it.copy(exportState = ExportProgressState.Cancelled(0)) }
            }
        }
    }

    // ---- Batch Export ----

    fun setPaddingSeconds(seconds: Int) {
        _uiState.update { it.copy(paddingSeconds = seconds.coerceIn(0, 15)) }
    }

    fun setShowPaddingDialog(show: Boolean) {
        _uiState.update { it.copy(showPaddingDialog = show) }
    }

    fun setShowGuideDialog(show: Boolean) {
        _uiState.update { it.copy(showGuideDialog = show) }
    }

    fun highlightClip(clipId: String?) {
        _uiState.update { it.copy(highlightedClipId = clipId) }
    }

    fun openPreview(target: PreviewTarget) {
        _uiState.update { it.copy(previewingClip = target) }
    }

    fun closePreview() {
        _uiState.update { it.copy(previewingClip = null) }
    }

    suspend fun loadFrameThumbnail(uriString: String, timestampMs: Long): Bitmap? {
        if (uriString.startsWith("http://") || uriString.startsWith("https://")) return null
        return metadataHelper.getFrameThumbnail(uriString, timestampMs)
    }

    fun startBatchExport() {
        val state = _uiState.value
        val video = state.selectedVideo ?: return
        val queue = state.parsedClips.filter { it.isSelected && !it.isOutOfBounds && it.durationMs > 0L }
        if (queue.isEmpty()) return

        val editSettings = state.defaultEditSettings
        val useHdTransformer = editSettings.aspectRatio != ExportAspectRatio.ORIGINAL_LOSSLESS ||
            video.isRemoteStream

        exportJob?.cancel()
        exportJob = viewModelScope.launch {
            val sourceUri = Uri.parse(video.uriString)
            val projectFolder = video.displayName.substringBeforeLast('.')
            val paddingMs = state.paddingSeconds * 1000L

            var exportedCount = 0
            val failures = mutableListOf<String>()
            var lastFolderLabel = "Movies/ClipSlice"

            try {
                queue.forEachIndexed { idx, clip ->
                    val clipCues = if (editSettings.subtitlesEnabled) getCuesForParsedClip(clip) else emptyList()
                    val modeTag = if (useHdTransformer) "9:16 HD" else "Lossless"

                    _uiState.update {
                        it.copy(
                            exportState = ExportProgressState.Running(
                                currentIndex = idx + 1,
                                totalCount = queue.size,
                                currentClipTitle = clip.title,
                                currentClipProgress = 0f,
                                overallProgress = idx.toFloat() / queue.size.toFloat(),
                                completedCount = exportedCount,
                                modeLabel = modeTag
                            )
                        )
                    }

                    val onClipProgress: (Float) -> Unit = { clipFraction ->
                        val overall = (idx.toFloat() + clipFraction) / queue.size.toFloat()
                        _uiState.update { cur ->
                            cur.copy(
                                exportState = ExportProgressState.Running(
                                    currentIndex = idx + 1,
                                    totalCount = queue.size,
                                    currentClipTitle = clip.title,
                                    currentClipProgress = clipFraction,
                                    overallProgress = overall.coerceIn(0f, 1f),
                                    completedCount = exportedCount,
                                    modeLabel = modeTag
                                )
                            )
                        }
                    }

                    if (useHdTransformer || clipCues.isNotEmpty()) {
                        verticalHdRenderer.renderClip(
                            sourceUriString = video.uriString,
                            projectFolderName = projectFolder,
                            clipIndex = clip.index,
                            clipTitle = clip.title,
                            startMs = clip.startMs,
                            endMs = clip.endMs,
                            paddingMs = paddingMs,
                            editSettings = editSettings,
                            cues = clipCues,
                            onProgress = onClipProgress
                        ).onSuccess { rendered ->
                            exportedCount++
                            lastFolderLabel = rendered.folderLabel
                            repository.recordExportedClip(
                                ExportedClipEntity(
                                    outputUriString = rendered.outputUriString,
                                    displayName = rendered.displayName,
                                    clipTitle = clip.title,
                                    sourceVideoName = video.displayName,
                                    startMs = clip.startMs,
                                    endMs = clip.endMs,
                                    durationMs = rendered.actualDurationMs,
                                    fileSizeBytes = rendered.fileSizeBytes,
                                    folderLabel = rendered.folderLabel
                                )
                            )
                        }.onFailure { err ->
                            if (err is CancellationException) throw err
                            failures.add("#${clip.index} ${clip.title}: ${err.localizedMessage ?: "Render error"}")
                        }
                    } else {
                        losslessTrimmer.trimClip(
                            sourceUri = sourceUri,
                            projectFolderName = projectFolder,
                            clipIndex = clip.index,
                            clipTitle = clip.title,
                            startMs = clip.startMs,
                            endMs = clip.endMs,
                            paddingMs = paddingMs,
                            rotationDegrees = video.rotationDegrees,
                            onProgress = onClipProgress
                        ).onSuccess { trimmed ->
                            exportedCount++
                            lastFolderLabel = trimmed.folderLabel
                            repository.recordExportedClip(
                                ExportedClipEntity(
                                    outputUriString = trimmed.outputUriString,
                                    displayName = trimmed.displayName,
                                    clipTitle = clip.title,
                                    sourceVideoName = video.displayName,
                                    startMs = clip.startMs,
                                    endMs = clip.endMs,
                                    durationMs = trimmed.actualDurationMs,
                                    fileSizeBytes = trimmed.fileSizeBytes,
                                    folderLabel = trimmed.folderLabel
                                )
                            )
                        }.onFailure { err ->
                            if (err is CancellationException) throw err
                            failures.add("#${clip.index} ${clip.title}: ${err.localizedMessage ?: "Trim error"}")
                        }
                    }
                }

                persistCurrentProjectIfReady()

                _uiState.update {
                    it.copy(
                        exportState = ExportProgressState.Completed(
                            exportedCount = exportedCount,
                            failedCount = failures.size,
                            failedMessages = failures,
                            outputFolderLabel = lastFolderLabel
                        )
                    )
                }
            } catch (_: CancellationException) {
                _uiState.update {
                    it.copy(exportState = ExportProgressState.Cancelled(exportedCount = exportedCount))
                }
            } catch (e: Throwable) {
                _uiState.update {
                    it.copy(
                        exportState = ExportProgressState.Error(
                            message = e.localizedMessage ?: "Export failed"
                        )
                    )
                }
            }
        }
    }

    fun cancelExport() {
        exportJob?.cancel()
    }

    fun dismissExportSheet() {
        if (_uiState.value.exportState !is ExportProgressState.Running) {
            _uiState.update { it.copy(exportState = ExportProgressState.Idle) }
        }
    }

    fun loadSavedProject(project: ClipProjectEntity) {
        _uiState.update {
            it.copy(
                activeProjectId = project.id,
                promptText = project.promptText,
                isLoadingVideo = true
            )
        }
        viewModelScope.launch {
            val uri = Uri.parse(project.videoUriString)
            metadataHelper.inspectVideo(uri)
                .onSuccess { info ->
                    _uiState.update { state ->
                        val parsed = GeminiTimestampParser.parse(
                            rawText = project.promptText,
                            videoDurationMs = info.durationMs.takeIf { it > 0L },
                            singleMode = state.singleTimestampMode
                        )
                        state.copy(
                            selectedVideo = info,
                            isLoadingVideo = false,
                            parsedClips = parsed.clips,
                            hasInferredSingleTimestamps = parsed.hasInferredSingleTimestamps
                        )
                    }
                }
                .onFailure {
                    _uiState.update { state ->
                        val parsed = GeminiTimestampParser.parse(
                            rawText = project.promptText,
                            videoDurationMs = project.videoDurationMs.takeIf { it > 0L },
                            singleMode = state.singleTimestampMode
                        )
                        state.copy(
                            selectedVideo = null,
                            isLoadingVideo = false,
                            parsedClips = parsed.clips,
                            hasInferredSingleTimestamps = parsed.hasInferredSingleTimestamps,
                            videoErrorMessage = "Video permission expired — please re-pick ${project.videoDisplayName}"
                        )
                    }
                }
        }
    }

    fun deleteProject(id: Long) {
        viewModelScope.launch { repository.deleteProject(id) }
    }

    fun deleteExportedClip(clip: ExportedClipEntity) {
        viewModelScope.launch {
            runCatching {
                getApplication<Application>().contentResolver.delete(
                    Uri.parse(clip.outputUriString),
                    null,
                    null
                )
            }
            repository.deleteExportedClip(clip.id)
        }
    }

    private fun persistCurrentProjectIfReady() {
        val state = _uiState.value
        val video = state.selectedVideo ?: return
        if (state.promptText.isBlank()) return
        viewModelScope.launch {
            val newId = repository.saveProject(
                ClipProjectEntity(
                    id = state.activeProjectId,
                    videoUriString = video.uriString,
                    videoDisplayName = video.displayName,
                    videoDurationMs = video.durationMs,
                    videoFileSizeBytes = video.fileSizeBytes,
                    promptText = state.promptText,
                    parsedClipCount = state.parsedClips.size,
                    updatedAt = System.currentTimeMillis()
                )
            )
            if (state.activeProjectId == 0L) {
                _uiState.update { it.copy(activeProjectId = newId) }
            }
        }
    }
}
