package com.example.domain.model

data class VideoSourceInfo(
    val uriString: String,
    val displayName: String,
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val fileSizeBytes: Long,
    val rotationDegrees: Int,
    val videoCodec: String,
    val audioCodec: String,
    val isRemoteStream: Boolean = false,
    val thumbnailUrl: String? = null,
    val platformBadge: String? = null
)

data class ParsedClip(
    val id: String,
    val index: Int,
    val title: String,
    val note: String = "",
    val startMs: Long,
    val endMs: Long,
    val isSelected: Boolean = true,
    val isSingleTimestampInferred: Boolean = false,
    val isOutOfBounds: Boolean = false
) {
    val durationMs: Long
        get() = (endMs - startMs).coerceAtLeast(0L)
}

enum class SingleTimestampMode(val fixedDurationMs: Long?) {
    NEXT_TIMESTAMP(null),
    FIXED_30S(30_000L),
    FIXED_60S(60_000L),
    FIXED_2M(120_000L),
    FIXED_5M(300_000L)
}

enum class AppLanguage {
    HINDI,
    ENGLISH
}

enum class ExportAspectRatio {
    VERTICAL_9_16_CROP,
    VERTICAL_9_16_FIT,
    ORIGINAL_LOSSLESS
}

enum class ExportResolution(val verticalWidth: Int, val verticalHeight: Int, val label: String) {
    FULL_HD_1080P(1080, 1920, "1080p Full HD"),
    HD_720P(720, 1280, "720p HD")
}

enum class SubtitlePosition {
    TOP,
    CENTER,
    BOTTOM
}

enum class SubtitleStylePreset {
    VIRAL_YELLOW,
    CINEMA_PILL,
    AMBER_PRO,
    CLASSIC_WHITE
}

data class SubtitleCue(
    val id: String,
    val startMs: Long,
    val endMs: Long,
    val text: String
)

data class ClipEditSettings(
    val aspectRatio: ExportAspectRatio = ExportAspectRatio.VERTICAL_9_16_CROP,
    val resolution: ExportResolution = ExportResolution.FULL_HD_1080P,
    val subtitlePosition: SubtitlePosition = SubtitlePosition.BOTTOM,
    val subtitleStyle: SubtitleStylePreset = SubtitleStylePreset.VIRAL_YELLOW,
    val fontScale: Float = 1.0f,
    val subtitlesEnabled: Boolean = true
)

enum class VideoLinkPlatform {
    YOUTUBE,
    KICK,
    DIRECT_STREAM
}

data class ResolvedLinkSource(
    val platform: VideoLinkPlatform,
    val originalUrl: String,
    val videoId: String,
    val title: String,
    val channelName: String,
    val thumbnailUrl: String?,
    val durationMs: Long,
    val playableStreamUrl: String?,
    val fetchedTranscriptCues: List<SubtitleCue> = emptyList(),
    val requiresLocalVideoAttach: Boolean = false,
    val statusNote: String = ""
)

sealed interface ExportProgressState {
    data object Idle : ExportProgressState

    data class Running(
        val currentIndex: Int,
        val totalCount: Int,
        val currentClipTitle: String,
        val currentClipProgress: Float,
        val overallProgress: Float,
        val completedCount: Int,
        val modeLabel: String = ""
    ) : ExportProgressState

    data class Completed(
        val exportedCount: Int,
        val failedCount: Int,
        val failedMessages: List<String>,
        val outputFolderLabel: String
    ) : ExportProgressState

    data class Cancelled(
        val exportedCount: Int
    ) : ExportProgressState

    data class Error(
        val message: String
    ) : ExportProgressState
}
