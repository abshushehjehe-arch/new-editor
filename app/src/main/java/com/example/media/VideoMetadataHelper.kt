package com.example.media

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import android.util.LruCache
import com.example.domain.model.VideoSourceInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Zero-copy metadata inspector and keyframe thumbnail extractor built for
 * massive 5–12+ hour videos accessed via SAF or Photo Picker URIs.
 */
class VideoMetadataHelper(private val context: Context) {

    private val thumbnailCache = object : LruCache<String, Bitmap>(28) {}

    suspend fun inspectVideo(uri: Uri): Result<VideoSourceInfo> = withContext(Dispatchers.IO) {
        runCatching {
            var displayName = "video.mp4"
            var fileSizeBytes = 0L

            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (nameIdx >= 0) {
                        displayName = cursor.getString(nameIdx) ?: displayName
                    }
                    if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) {
                        fileSizeBytes = cursor.getLong(sizeIdx)
                    }
                }
            }

            var durationMs = 0L
            var width = 0
            var height = 0
            var rotation = 0

            val retriever = MediaMetadataRetriever()
            try {
                context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                    if (fileSizeBytes <= 0L && pfd.statSize > 0L) {
                        fileSizeBytes = pfd.statSize
                    }
                    retriever.setDataSource(pfd.fileDescriptor)
                    durationMs = retriever.extractMetadata(
                        MediaMetadataRetriever.METADATA_KEY_DURATION
                    )?.toLongOrNull() ?: 0L
                    width = retriever.extractMetadata(
                        MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH
                    )?.toIntOrNull() ?: 0
                    height = retriever.extractMetadata(
                        MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT
                    )?.toIntOrNull() ?: 0
                    rotation = retriever.extractMetadata(
                        MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION
                    )?.toIntOrNull() ?: 0
                }
            } finally {
                runCatching { retriever.release() }
            }

            var videoCodec = "H.264"
            var audioCodec = "AAC"
            val extractor = MediaExtractor()
            try {
                context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                    extractor.setDataSource(pfd.fileDescriptor)
                    for (i in 0 until extractor.trackCount) {
                        val format = extractor.getTrackFormat(i)
                        val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                        if (mime.startsWith("video/")) {
                            videoCodec = friendlyCodecName(mime)
                            if (durationMs <= 0L && format.containsKey(MediaFormat.KEY_DURATION)) {
                                durationMs = format.getLong(MediaFormat.KEY_DURATION) / 1000L
                            }
                            if (width == 0 && format.containsKey(MediaFormat.KEY_WIDTH)) {
                                width = format.getInteger(MediaFormat.KEY_WIDTH)
                            }
                            if (height == 0 && format.containsKey(MediaFormat.KEY_HEIGHT)) {
                                height = format.getInteger(MediaFormat.KEY_HEIGHT)
                            }
                        } else if (mime.startsWith("audio/")) {
                            audioCodec = friendlyCodecName(mime)
                        }
                    }
                }
            } finally {
                runCatching { extractor.release() }
            }

            VideoSourceInfo(
                uriString = uri.toString(),
                displayName = displayName,
                durationMs = durationMs,
                width = width,
                height = height,
                fileSizeBytes = fileSizeBytes,
                rotationDegrees = rotation,
                videoCodec = videoCodec,
                audioCodec = audioCodec
            )
        }
    }

    suspend fun getFrameThumbnail(uriString: String, timestampMs: Long): Bitmap? {
        val bucketSec = (timestampMs / 1000L).coerceAtLeast(0L)
        val cacheKey = "${uriString}_$bucketSec"
        thumbnailCache.get(cacheKey)?.let { return it }

        return withContext(Dispatchers.IO) {
            val retriever = MediaMetadataRetriever()
            try {
                val uri = Uri.parse(uriString)
                context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                    retriever.setDataSource(pfd.fileDescriptor)
                    val frame = retriever.getFrameAtTime(
                        timestampMs.coerceAtLeast(0L) * 1000L,
                        MediaMetadataRetriever.OPTION_CLOSEST_SYNC
                    )
                    if (frame != null) {
                        val scaled = scaleDownThumbnail(frame, 320)
                        if (scaled !== frame) frame.recycle()
                        thumbnailCache.put(cacheKey, scaled)
                        scaled
                    } else {
                        null
                    }
                }
            } catch (_: Throwable) {
                null
            } finally {
                runCatching { retriever.release() }
            }
        }
    }

    private fun scaleDownThumbnail(src: Bitmap, maxDim: Int): Bitmap {
        val w = src.width
        val h = src.height
        if (w <= maxDim && h <= maxDim) return src
        val ratio = maxDim.toFloat() / maxOf(w, h).toFloat()
        val targetW = (w * ratio).toInt().coerceAtLeast(1)
        val targetH = (h * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(src, targetW, targetH, true)
    }

    private fun friendlyCodecName(mime: String): String = when {
        mime.contains("avc", ignoreCase = true) -> "H.264"
        mime.contains("hevc", ignoreCase = true) -> "HEVC"
        mime.contains("av01", ignoreCase = true) -> "AV1"
        mime.contains("vp9", ignoreCase = true) -> "VP9"
        mime.contains("mp4a", ignoreCase = true) -> "AAC"
        mime.contains("opus", ignoreCase = true) -> "OPUS"
        mime.contains("mpeg", ignoreCase = true) -> "MP3"
        else -> mime.substringAfter('/').uppercase()
    }
}
