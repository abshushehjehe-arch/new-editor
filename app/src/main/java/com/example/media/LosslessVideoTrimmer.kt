package com.example.media

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.example.domain.parser.GeminiTimestampParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.util.Locale

/**
 * Lossless, zero-reencode stream-copy video trimmer powered by Android's native
 * MediaExtractor + MediaMuxer.
 *
 * Designed specifically for 5–12+ hour videos:
 * - Seeks directly via keyframe index (`SEEK_TO_PREVIOUS_SYNC`) in milliseconds
 * - Copies original video + audio bitstreams without re-encoding (100% original quality)
 * - Uses 64-bit Long microseconds to support 100+ hour timelines without overflow
 * - Saves directly to Movies/ClipSlice via zero-permission MediaStore on API 29+
 */
class LosslessVideoTrimmer(private val context: Context) {

    data class TrimResult(
        val outputUriString: String,
        val displayName: String,
        val actualDurationMs: Long,
        val fileSizeBytes: Long,
        val folderLabel: String
    )

    suspend fun trimClip(
        sourceUri: Uri,
        projectFolderName: String,
        clipIndex: Int,
        clipTitle: String,
        startMs: Long,
        endMs: Long,
        paddingMs: Long,
        rotationDegrees: Int,
        onProgress: (Float) -> Unit
    ): Result<TrimResult> = withContext(Dispatchers.IO) {
        val safeStartUs = ((startMs - paddingMs).coerceAtLeast(0L)) * 1000L
        val safeEndUs = ((endMs + paddingMs).coerceAtLeast(startMs + 500L)) * 1000L
        val targetDurationUs = (safeEndUs - safeStartUs).coerceAtLeast(1_000_000L)

        val safeFolder = GeminiTimestampParser.sanitizeFileName(projectFolderName).ifBlank { "Clips" }
        val safeTitle = GeminiTimestampParser.sanitizeFileName(clipTitle)
        val fileName = String.format(Locale.US, "%02d_%s.mp4", clipIndex, safeTitle)

        var insertedMediaStoreUri: Uri? = null
        var fallbackFile: File? = null

        val result = runCatching {
            val targetPair = createOutputTarget(safeFolder, fileName)
            insertedMediaStoreUri = targetPair.mediaStoreUri
            fallbackFile = targetPair.fallbackFile

            val actualDurationUs: Long = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && targetPair.mediaStoreUri != null) {
                context.contentResolver.openFileDescriptor(targetPair.mediaStoreUri, "w")?.use { outPfd ->
                    val muxer = MediaMuxer(outPfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                    muxStreams(
                        sourceUri = sourceUri,
                        muxer = muxer,
                        startUs = safeStartUs,
                        endUs = safeEndUs,
                        targetDurationUs = targetDurationUs,
                        rotationDegrees = rotationDegrees,
                        onProgress = onProgress
                    )
                } ?: error("Unable to open MediaStore destination for writing")
            } else {
                val outFile = targetPair.fallbackFile
                    ?: File(context.cacheDir, fileName)
                val muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                val dur = muxStreams(
                    sourceUri = sourceUri,
                    muxer = muxer,
                    startUs = safeStartUs,
                    endUs = safeEndUs,
                    targetDurationUs = targetDurationUs,
                    rotationDegrees = rotationDegrees,
                    onProgress = onProgress
                )
                // If we had a MediaStore URI on API < 26, copy temp file into it
                if (targetPair.mediaStoreUri != null) {
                    context.contentResolver.openOutputStream(targetPair.mediaStoreUri)?.use { out ->
                        outFile.inputStream().use { input -> input.copyTo(out) }
                    }
                    outFile.delete()
                }
                dur
            }

            // Finalize MediaStore pending state
            val finalUriString: String
            val finalSizeBytes: Long
            if (targetPair.mediaStoreUri != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val doneValues = ContentValues().apply {
                        put(MediaStore.Video.Media.IS_PENDING, 0)
                        put(MediaStore.Video.Media.DURATION, actualDurationUs / 1000L)
                    }
                    context.contentResolver.update(targetPair.mediaStoreUri, doneValues, null, null)
                }
                finalUriString = targetPair.mediaStoreUri.toString()
                finalSizeBytes = context.contentResolver.openFileDescriptor(targetPair.mediaStoreUri, "r")
                    ?.use { it.statSize.coerceAtLeast(0L) } ?: 0L
            } else {
                val file = requireNotNull(targetPair.fallbackFile)
                val providerUri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    file
                )
                finalUriString = providerUri.toString()
                finalSizeBytes = file.length()
            }

            onProgress(1f)
            TrimResult(
                outputUriString = finalUriString,
                displayName = fileName,
                actualDurationMs = (actualDurationUs / 1000L).coerceAtLeast(endMs - startMs),
                fileSizeBytes = finalSizeBytes,
                folderLabel = targetPair.folderLabel
            )
        }

        if (result.isFailure) {
            // Clean up incomplete artifact on cancellation or failure
            insertedMediaStoreUri?.let { uri ->
                runCatching { context.contentResolver.delete(uri, null, null) }
            }
            fallbackFile?.let { file ->
                runCatching { if (file.exists()) file.delete() }
            }
        }

        result
    }

    private suspend fun muxStreams(
        sourceUri: Uri,
        muxer: MediaMuxer,
        startUs: Long,
        endUs: Long,
        targetDurationUs: Long,
        rotationDegrees: Int,
        onProgress: (Float) -> Unit
    ): Long {
        val extractor = MediaExtractor()
        var muxerStarted = false
        var maxWrittenPtsUs = 0L

        try {
            val sourcePfd = context.contentResolver.openFileDescriptor(sourceUri, "r")
                ?: error("Cannot read selected video file")

            sourcePfd.use { pfd ->
                extractor.setDataSource(pfd.fileDescriptor)

                val trackIndexMap = HashMap<Int, Int>()
                var maxBufferSize = 1024 * 1024 // 1 MB floor
                var videoTrackSourceIdx = -1
                var audioTrackSourceIdx = -1

                for (i in 0 until extractor.trackCount) {
                    val format = extractor.getTrackFormat(i)
                    val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                    if (mime.startsWith("video/") && videoTrackSourceIdx == -1) {
                        videoTrackSourceIdx = i
                    } else if (mime.startsWith("audio/") && audioTrackSourceIdx == -1) {
                        audioTrackSourceIdx = i
                    }
                }

                if (videoTrackSourceIdx == -1 && audioTrackSourceIdx == -1) {
                    error("No supported video or audio track found")
                }

                if (videoTrackSourceIdx != -1) {
                    val fmt = extractor.getTrackFormat(videoTrackSourceIdx)
                    val dstIdx = muxer.addTrack(fmt)
                    trackIndexMap[videoTrackSourceIdx] = dstIdx
                    extractor.selectTrack(videoTrackSourceIdx)
                    if (fmt.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                        maxBufferSize = maxOf(maxBufferSize, fmt.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE))
                    }
                }

                if (audioTrackSourceIdx != -1) {
                    val fmt = extractor.getTrackFormat(audioTrackSourceIdx)
                    // Some MKV/WebM audio codecs (e.g. Vorbis/AC3) may not be accepted by MP4 MediaMuxer
                    runCatching {
                        val dstIdx = muxer.addTrack(fmt)
                        trackIndexMap[audioTrackSourceIdx] = dstIdx
                        extractor.selectTrack(audioTrackSourceIdx)
                        if (fmt.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                            maxBufferSize = maxOf(maxBufferSize, fmt.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE))
                        }
                    }
                }

                if (rotationDegrees != 0) {
                    runCatching { muxer.setOrientationHint(rotationDegrees) }
                }

                muxer.start()
                muxerStarted = true

                // Direct O(1) keyframe seek even inside a 12-hour 30GB video!
                extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

                val buffer = ByteBuffer.allocateDirect(maxBufferSize.coerceAtMost(8 * 1024 * 1024))
                val bufferInfo = MediaCodec.BufferInfo()

                var baseTimestampUs = -1L
                val lastPtsByTrack = HashMap<Int, Long>()
                var sampleCounter = 0

                while (true) {
                    if (sampleCounter % 64 == 0) {
                        currentCoroutineContext().ensureActive()
                    }
                    sampleCounter++

                    bufferInfo.offset = 0
                    bufferInfo.size = extractor.readSampleData(buffer, 0)
                    if (bufferInfo.size < 0) {
                        break
                    }

                    val sampleTimeUs = extractor.sampleTime
                    val srcTrackIndex = extractor.sampleTrackIndex
                    val dstTrackIndex = trackIndexMap[srcTrackIndex]

                    if (dstTrackIndex == null) {
                        extractor.advance()
                        continue
                    }

                    // Stop once past requested end timestamp
                    if (sampleTimeUs > endUs) {
                        if (srcTrackIndex == videoTrackSourceIdx || videoTrackSourceIdx == -1) {
                            break
                        } else {
                            extractor.advance()
                            continue
                        }
                    }

                    // Anchor output timeline at the first video sync frame (or first sample if audio-only)
                    if (baseTimestampUs < 0L) {
                        if (videoTrackSourceIdx != -1 && srcTrackIndex != videoTrackSourceIdx) {
                            extractor.advance()
                            continue
                        }
                        baseTimestampUs = sampleTimeUs
                    }

                    val relativePts = (sampleTimeUs - baseTimestampUs).coerceAtLeast(0L)
                    val prevTrackPts = lastPtsByTrack[dstTrackIndex] ?: -1L
                    // Keep audio monotonically non-decreasing; video may contain B-frames (handled by muxer)
                    val safePts = if (srcTrackIndex != videoTrackSourceIdx && relativePts <= prevTrackPts) {
                        prevTrackPts + 1L
                    } else {
                        relativePts
                    }
                    lastPtsByTrack[dstTrackIndex] = maxOf(prevTrackPts, safePts)

                    bufferInfo.presentationTimeUs = safePts
                    bufferInfo.flags = mapSampleFlags(extractor.sampleFlags)

                    muxer.writeSampleData(dstTrackIndex, buffer, bufferInfo)

                    if (safePts > maxWrittenPtsUs) {
                        maxWrittenPtsUs = safePts
                    }

                    if (sampleCounter % 32 == 0) {
                        val fraction = ((sampleTimeUs - startUs).toFloat() / targetDurationUs.toFloat())
                            .coerceIn(0f, 0.98f)
                        onProgress(fraction)
                    }

                    extractor.advance()
                }
            }
        } finally {
            runCatching { extractor.release() }
            if (muxerStarted) {
                runCatching { muxer.stop() }
            }
            runCatching { muxer.release() }
        }

        if (maxWrittenPtsUs <= 0L) {
            error("No frames were found inside this timestamp range")
        }
        return maxWrittenPtsUs
    }

    private fun mapSampleFlags(extractorFlags: Int): Int {
        var codecFlags = 0
        if ((extractorFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0) {
            codecFlags = codecFlags or MediaCodec.BUFFER_FLAG_KEY_FRAME
        }
        if ((extractorFlags and MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME) != 0 &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
        ) {
            codecFlags = codecFlags or MediaCodec.BUFFER_FLAG_PARTIAL_FRAME
        }
        return codecFlags
    }

    private data class OutputTarget(
        val mediaStoreUri: Uri?,
        val fallbackFile: File?,
        val folderLabel: String
    )

    private fun createOutputTarget(subFolder: String, fileName: String): OutputTarget {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val relativePath = "${Environment.DIRECTORY_MOVIES}/ClipSlice/$subFolder"
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, relativePath)
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val uri = context.contentResolver.insert(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                values
            )
            if (uri != null) {
                return OutputTarget(
                    mediaStoreUri = uri,
                    fallbackFile = null,
                    folderLabel = "Movies/ClipSlice/$subFolder"
                )
            }
        }

        val dir = File(
            context.getExternalFilesDir(Environment.DIRECTORY_MOVIES),
            "ClipSlice/$subFolder"
        ).apply { mkdirs() }
        val file = File(dir, fileName)
        return OutputTarget(
            mediaStoreUri = null,
            fallbackFile = file,
            folderLabel = "Movies/ClipSlice/$subFolder"
        )
    }
}
