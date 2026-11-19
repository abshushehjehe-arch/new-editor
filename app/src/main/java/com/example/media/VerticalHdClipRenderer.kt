package com.example.media

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.annotation.OptIn
import androidx.core.content.FileProvider
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.OverlaySettings
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.Presentation
import androidx.media3.effect.StaticOverlaySettings
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import com.example.domain.model.ClipEditSettings
import com.example.domain.model.ExportAspectRatio
import com.example.domain.model.SubtitleCue
import com.example.domain.model.SubtitlePosition
import com.example.domain.model.SubtitleStylePreset
import com.example.domain.parser.GeminiTimestampParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import kotlin.coroutines.resume

/**
 * GPU-accelerated Jetpack Media3 Transformer renderer that exports clips in:
 * - True 9:16 Full HD (1080×1920) or HD (720×1280) MP4
 * - Center Crop (Fill) or Full-Frame Fit (Cinema Bars) framing
 * - Frame-accurate burned-in Hindi/English dynamic subtitles at TOP, CENTER, or BOTTOM
 * - Supports both massive local 5–12+ hour files and remote Kick/HLS (.m3u8) streams!
 */
@OptIn(UnstableApi::class)
class VerticalHdClipRenderer(private val context: Context) {

    data class RenderResult(
        val outputUriString: String,
        val displayName: String,
        val actualDurationMs: Long,
        val fileSizeBytes: Long,
        val folderLabel: String
    )

    suspend fun renderClip(
        sourceUriString: String,
        projectFolderName: String,
        clipIndex: Int,
        clipTitle: String,
        startMs: Long,
        endMs: Long,
        paddingMs: Long,
        editSettings: ClipEditSettings,
        cues: List<SubtitleCue>,
        onProgress: (Float) -> Unit
    ): Result<RenderResult> = runCatching {
        val safeStartMs = (startMs - paddingMs).coerceAtLeast(0L)
        val safeEndMs = (endMs + paddingMs).coerceAtLeast(safeStartMs + 500L)
        val clipDurationMs = (safeEndMs - safeStartMs).coerceAtLeast(500L)

        val safeFolder = GeminiTimestampParser.sanitizeFileName(projectFolderName).ifBlank { "9x16_HD" }
        val safeTitle = GeminiTimestampParser.sanitizeFileName(clipTitle)
        val ratioTag = when (editSettings.aspectRatio) {
            ExportAspectRatio.VERTICAL_9_16_CROP, ExportAspectRatio.VERTICAL_9_16_FIT -> "9x16_HD"
            ExportAspectRatio.ORIGINAL_LOSSLESS -> "HD"
        }
        val fileName = String.format(Locale.US, "%02d_%s_%s.mp4", clipIndex, safeTitle, ratioTag)

        // Render into temp cache file first (Transformer requires a writable local path),
        // then publish cleanly to MediaStore Gallery (Movies/ClipSlice/9x16_HD)
        val tempDir = File(context.cacheDir, "clipslice_renders").apply { mkdirs() }
        val tempOutFile = File(tempDir, "tmp_${System.currentTimeMillis()}_$fileName")
        if (tempOutFile.exists()) tempOutFile.delete()

        try {
            val mediaItem = MediaItem.Builder()
                .setUri(Uri.parse(sourceUriString))
                .setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(safeStartMs)
                        .setEndPositionMs(safeEndMs)
                        .build()
                )
                .build()

            val videoEffects = buildList<Effect> {
                val outW = editSettings.resolution.verticalWidth
                val outH = editSettings.resolution.verticalHeight

                when (editSettings.aspectRatio) {
                    ExportAspectRatio.VERTICAL_9_16_CROP -> {
                        add(
                            Presentation.createForWidthAndHeight(
                                outW,
                                outH,
                                Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP
                            )
                        )
                    }
                    ExportAspectRatio.VERTICAL_9_16_FIT -> {
                        add(
                            Presentation.createForWidthAndHeight(
                                outW,
                                outH,
                                Presentation.LAYOUT_SCALE_TO_FIT
                            )
                        )
                    }
                    ExportAspectRatio.ORIGINAL_LOSSLESS -> {
                        // Keep native ratio, ensure at least HD height
                        add(Presentation.createForHeight(1080))
                    }
                }

                if (editSettings.subtitlesEnabled && cues.isNotEmpty()) {
                    val overlayWidth = when (editSettings.aspectRatio) {
                        ExportAspectRatio.ORIGINAL_LOSSLESS -> 1280
                        else -> outW
                    }
                    add(
                        OverlayEffect(
                            listOf(
                                SubtitleBitmapOverlay(
                                    cues = cues,
                                    position = editSettings.subtitlePosition,
                                    stylePreset = editSettings.subtitleStyle,
                                    fontScale = editSettings.fontScale,
                                    canvasWidth = overlayWidth
                                )
                            )
                        )
                    )
                }
            }

            val editedMediaItem = EditedMediaItem.Builder(mediaItem)
                .setEffects(Effects(emptyList(), videoEffects))
                .build()

            runTransformerOnMainThread(
                editedMediaItem = editedMediaItem,
                outputPath = tempOutFile.absolutePath,
                onProgress = onProgress
            )

            // Publish temp file to Gallery MediaStore (Movies/ClipSlice/<folder>)
            val published = withContext(Dispatchers.IO) {
                publishToGallery(
                    sourceFile = tempOutFile,
                    subFolder = safeFolder,
                    fileName = fileName,
                    durationMs = clipDurationMs
                )
            }

            onProgress(1f)
            published
        } finally {
            runCatching { if (tempOutFile.exists()) tempOutFile.delete() }
        }
    }

    private suspend fun runTransformerOnMainThread(
        editedMediaItem: EditedMediaItem,
        outputPath: String,
        onProgress: (Float) -> Unit
    ) = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine<Unit> { continuation ->
            val mainHandler = Handler(Looper.getMainLooper())
            var progressRunnable: Runnable? = null

            val listener = object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    progressRunnable?.let { mainHandler.removeCallbacks(it) }
                    if (continuation.isActive) {
                        continuation.resume(Unit)
                    }
                }

                override fun onError(
                    composition: Composition,
                    exportResult: ExportResult,
                    exportException: ExportException
                ) {
                    progressRunnable?.let { mainHandler.removeCallbacks(it) }
                    if (continuation.isActive) {
                        continuation.resumeWith(Result.failure(exportException))
                    }
                }
            }

            val transformer = Transformer.Builder(context)
                .setVideoMimeType(MimeTypes.VIDEO_H264)
                .addListener(listener)
                .build()

            val progressHolder = ProgressHolder()
            progressRunnable = object : Runnable {
                override fun run() {
                    if (!continuation.isActive) return
                    val state = transformer.getProgress(progressHolder)
                    if (state == Transformer.PROGRESS_STATE_AVAILABLE) {
                        onProgress((progressHolder.progress / 100f).coerceIn(0f, 0.99f))
                    }
                    if (state != Transformer.PROGRESS_STATE_NOT_STARTED) {
                        mainHandler.postDelayed(this, 200L)
                    }
                }
            }

            continuation.invokeOnCancellation {
                mainHandler.post {
                    progressRunnable?.let { mainHandler.removeCallbacks(it) }
                    runCatching { transformer.cancel() }
                }
            }

            transformer.start(editedMediaItem, outputPath)
            mainHandler.postDelayed(progressRunnable, 250L)
        }
    }

    private fun publishToGallery(
        sourceFile: File,
        subFolder: String,
        fileName: String,
        durationMs: Long
    ): RenderResult {
        val fileSize = sourceFile.length().coerceAtLeast(0L)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val relativePath = "${Environment.DIRECTORY_MOVIES}/ClipSlice/$subFolder"
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, relativePath)
                put(MediaStore.Video.Media.DURATION, durationMs)
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val uri = context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            if (uri != null) {
                try {
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        sourceFile.inputStream().use { input -> input.copyTo(out) }
                    } ?: error("Cannot open MediaStore output stream")

                    val done = ContentValues().apply {
                        put(MediaStore.Video.Media.IS_PENDING, 0)
                    }
                    context.contentResolver.update(uri, done, null, null)
                    return RenderResult(
                        outputUriString = uri.toString(),
                        displayName = fileName,
                        actualDurationMs = durationMs,
                        fileSizeBytes = fileSize,
                        folderLabel = "Movies/ClipSlice/$subFolder"
                    )
                } catch (e: Throwable) {
                    runCatching { context.contentResolver.delete(uri, null, null) }
                    throw e
                }
            }
        }

        val destDir = File(
            context.getExternalFilesDir(Environment.DIRECTORY_MOVIES),
            "ClipSlice/$subFolder"
        ).apply { mkdirs() }
        val destFile = File(destDir, fileName)
        sourceFile.copyTo(destFile, overwrite = true)
        val providerUri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            destFile
        )
        return RenderResult(
            outputUriString = providerUri.toString(),
            displayName = fileName,
            actualDurationMs = durationMs,
            fileSizeBytes = destFile.length(),
            folderLabel = "Movies/ClipSlice/$subFolder"
        )
    }

    /**
     * Custom Media3 GPU BitmapOverlay that burns crisp, anti-aliased Hindi/Devanagari & English
     * subtitles dynamically at TOP, CENTER, or BOTTOM in exact sync with presentationTimeUs.
     */
    private class SubtitleBitmapOverlay(
        private val cues: List<SubtitleCue>,
        private val position: SubtitlePosition,
        private val stylePreset: SubtitleStylePreset,
        private val fontScale: Float,
        private val canvasWidth: Int
    ) : BitmapOverlay() {

        private val overlayHeight = (canvasWidth * 0.42f).toInt().coerceIn(280, 480)
        private val emptyBitmap: Bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        private var lastRenderedText: String? = null
        private var cachedBitmap: Bitmap = emptyBitmap

        private val anchorY: Float = when (position) {
            SubtitlePosition.TOP -> 0.66f
            SubtitlePosition.CENTER -> 0.0f
            SubtitlePosition.BOTTOM -> -0.66f
        }

        private val overlaySettings: OverlaySettings = StaticOverlaySettings.Builder()
            .setOverlayFrameAnchor(0f, 0f)
            .setBackgroundFrameAnchor(0f, anchorY)
            .build()

        override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings = overlaySettings

        override fun getBitmap(presentationTimeUs: Long): Bitmap {
            val timeMs = presentationTimeUs / 1000L
            val activeCue = cues.firstOrNull { timeMs in it.startMs..it.endMs }
            val text = activeCue?.text?.trim().orEmpty()

            if (text == lastRenderedText) {
                return cachedBitmap
            }
            lastRenderedText = text

            if (text.isEmpty()) {
                cachedBitmap = emptyBitmap
                return emptyBitmap
            }

            val bmp = Bitmap.createBitmap(canvasWidth, overlayHeight, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            drawSubtitleBanner(canvas, text)
            cachedBitmap = bmp
            return bmp
        }

        private fun drawSubtitleBanner(canvas: Canvas, text: String) {
            val horizontalPadding = (canvasWidth * 0.08f).toInt()
            val maxTextWidth = (canvasWidth - horizontalPadding * 2).coerceAtLeast(200)
            val textSizePx = (canvasWidth * 0.056f * fontScale.coerceIn(0.75f, 1.45f))

            val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = textSizePx
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                color = when (stylePreset) {
                    SubtitleStylePreset.VIRAL_YELLOW -> Color.parseColor("#FFE033")
                    SubtitleStylePreset.CINEMA_PILL -> Color.WHITE
                    SubtitleStylePreset.AMBER_PRO -> Color.parseColor("#FFB020")
                    SubtitleStylePreset.CLASSIC_WHITE -> Color.WHITE
                }
                setShadowLayer(8f, 0f, 4f, Color.argb(215, 0, 0, 0))
            }

            val strokePaint = TextPaint(textPaint).apply {
                this.style = Paint.Style.STROKE
                strokeWidth = (textSizePx * 0.16f).coerceAtLeast(6f)
                strokeJoin = Paint.Join.ROUND
                color = Color.argb(235, 10, 12, 18)
                clearShadowLayer()
            }

            val fillLayout = StaticLayout.Builder
                .obtain(text, 0, text.length, textPaint, maxTextWidth)
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setLineSpacing(6f, 1.08f)
                .setIncludePad(true)
                .setMaxLines(3)
                .build()

            val strokeLayout = StaticLayout.Builder
                .obtain(text, 0, text.length, strokePaint, maxTextWidth)
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setLineSpacing(6f, 1.08f)
                .setIncludePad(true)
                .setMaxLines(3)
                .build()

            val blockHeight = fillLayout.height.toFloat()
            val startY = ((overlayHeight - blockHeight) / 2f).coerceAtLeast(16f)

            // Draw translucent rounded pill box for CINEMA_PILL and AMBER_PRO styles
            if (stylePreset == SubtitleStylePreset.CINEMA_PILL || stylePreset == SubtitleStylePreset.AMBER_PRO) {
                var maxLineW = 0f
                for (line in 0 until fillLayout.lineCount) {
                    maxLineW = maxOf(maxLineW, fillLayout.getLineWidth(line))
                }
                val boxPadX = 36f
                val boxPadY = 20f
                val boxW = (maxLineW + boxPadX * 2).coerceAtMost(canvasWidth - 36f)
                val left = (canvasWidth - boxW) / 2f
                val top = (startY - boxPadY).coerceAtLeast(4f)
                val right = left + boxW
                val bottom = (startY + blockHeight + boxPadY).coerceAtMost(overlayHeight - 4f)
                val rect = RectF(left, top, right, bottom)

                val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = if (stylePreset == SubtitleStylePreset.CINEMA_PILL) {
                        Color.argb(205, 11, 13, 18)
                    } else {
                        Color.argb(220, 18, 22, 33)
                    }
                }
                canvas.drawRoundRect(rect, 26f, 26f, bgPaint)

                if (stylePreset == SubtitleStylePreset.AMBER_PRO) {
                    val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        this.style = Paint.Style.STROKE
                        strokeWidth = 4f
                        color = Color.parseColor("#FF4D2E")
                    }
                    canvas.drawRoundRect(rect, 26f, 26f, borderPaint)
                }
            }

            canvas.save()
            canvas.translate(horizontalPadding.toFloat(), startY)
            if (stylePreset == SubtitleStylePreset.VIRAL_YELLOW || stylePreset == SubtitleStylePreset.CLASSIC_WHITE) {
                strokeLayout.draw(canvas)
            }
            fillLayout.draw(canvas)
            canvas.restore()
        }
    }
}
