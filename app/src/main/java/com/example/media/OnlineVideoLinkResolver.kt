package com.example.media

import com.example.domain.model.ResolvedLinkSource
import com.example.domain.model.SubtitleCue
import com.example.domain.model.VideoLinkPlatform
import com.example.domain.parser.SubtitleParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Resolves pasted YouTube video links, Kick.com VOD/Clip links, and direct MP4/HLS (.m3u8) URLs.
 *
 * - Kick VODs/Clips: Queries Kick's public VOD/Clip API to extract title, duration, thumbnail,
 *   and direct HLS (.m3u8) stream URL so Media3 Transformer can clip timestamps directly!
 * - YouTube Links: Queries official YouTube oEmbed metadata + public timedtext caption tracks
 *   (Hindi/English) when available, ready to pair with timestamps and local downloaded video.
 * - Direct Stream URLs (.mp4 / .m3u8): Loads directly into Media3 for clipping & 9:16 HD export.
 */
class OnlineVideoLinkResolver {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val browserUserAgent =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

    suspend fun resolve(rawInput: String): Result<ResolvedLinkSource> = withContext(Dispatchers.IO) {
        runCatching {
            val url = rawInput.trim()
            require(url.startsWith("http://") || url.startsWith("https://")) {
                "कृपया सही YouTube या Kick वीडियो लिंक डालें (https://...)"
            }

            val ytId = extractYouTubeVideoId(url)
            if (ytId != null) {
                return@runCatching resolveYouTubeLink(url, ytId)
            }

            val kickInfo = extractKickIdentifier(url)
            if (kickInfo != null) {
                return@runCatching resolveKickLink(url, kickInfo)
            }

            // Direct MP4 / M3U8 / WebM stream URL
            if (url.contains(".m3u8", ignoreCase = true) ||
                url.contains(".mp4", ignoreCase = true) ||
                url.contains(".mov", ignoreCase = true) ||
                url.contains(".mkv", ignoreCase = true)
            ) {
                val cleanName = url.substringAfterLast('/').substringBefore('?').ifBlank { "Stream_Video.mp4" }
                return@runCatching ResolvedLinkSource(
                    platform = VideoLinkPlatform.DIRECT_STREAM,
                    originalUrl = url,
                    videoId = cleanName,
                    title = cleanName,
                    channelName = "Direct Video Stream",
                    thumbnailUrl = null,
                    durationMs = 0L,
                    playableStreamUrl = url,
                    requiresLocalVideoAttach = false,
                    statusNote = "Direct stream ready for 9:16 HD clipping"
                )
            }

            error("लिंक की पहचान नहीं हो सकी। YouTube, Kick VOD/Clip, या डायरेक्ट .mp4/.m3u8 लिंक पेस्ट करें।")
        }
    }

    private fun resolveYouTubeLink(originalUrl: String, videoId: String): ResolvedLinkSource {
        val watchUrl = "https://www.youtube.com/watch?v=$videoId"
        val encodedWatch = URLEncoder.encode(watchUrl, "UTF-8")
        val oEmbedUrl = "https://www.youtube.com/oembed?url=$encodedWatch&format=json"

        var title = "YouTube Video ($videoId)"
        var author = "YouTube"
        var thumbnail = "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"

        val oEmbedReq = Request.Builder()
            .url(oEmbedUrl)
            .header("User-Agent", browserUserAgent)
            .build()

        runCatching {
            client.newCall(oEmbedReq).execute().use { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body?.string().orEmpty()
                    if (body.isNotBlank()) {
                        val json = JSONObject(body)
                        title = json.optString("title", title)
                        author = json.optString("author_name", author)
                        thumbnail = json.optString("thumbnail_url", thumbnail)
                    }
                }
            }
        }

        // Attempt to fetch duration and publicly exposed spoken captions from watch page
        val (durationMs, captions) = fetchYouTubeDurationAndCaptions(videoId)

        val statusNote = if (captions.isNotEmpty()) {
            "YouTube मेटाडेटा और ${captions.size} सबटाइटल लाइन्स लोड हुईं! 9:16 HD कटिंग के लिए नीचे डाउनलोड की हुई वीडियो फाइल जोड़ें।"
        } else {
            "YouTube वीडियो लिंक लोड हो गया। अब Gemini टाइमस्टैम्प पेस्ट करें और एक्सपोर्ट के लिए वीडियो फाइल चुनें।"
        }

        return ResolvedLinkSource(
            platform = VideoLinkPlatform.YOUTUBE,
            originalUrl = watchUrl,
            videoId = videoId,
            title = title,
            channelName = author,
            thumbnailUrl = thumbnail,
            durationMs = durationMs,
            playableStreamUrl = null,
            fetchedTranscriptCues = captions,
            requiresLocalVideoAttach = true,
            statusNote = statusNote
        )
    }

    private fun fetchYouTubeDurationAndCaptions(videoId: String): Pair<Long, List<SubtitleCue>> {
        var durationMs = 0L
        var cues: List<SubtitleCue> = emptyList()

        runCatching {
            val req = Request.Builder()
                .url("https://www.youtube.com/watch?v=$videoId&hl=en")
                .header("User-Agent", browserUserAgent)
                .header("Accept-Language", "hi-IN,hi;q=0.9,en-US;q=0.8,en;q=0.7")
                .build()

            val html = client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) resp.body?.string().orEmpty() else ""
            }

            if (html.isNotBlank()) {
                // Extract lengthSeconds
                val lenMatch = Regex(""""lengthSeconds"\s*:\s*"(\d+)"""").find(html)
                val sec = lenMatch?.groupValues?.getOrNull(1)?.toLongOrNull()
                if (sec != null && sec > 0L) {
                    durationMs = sec * 1000L
                }

                // Extract captionTracks JSON array if present
                val tracksMatch = Regex(""""captionTracks"\s*:\s*(\[.*?\])""").find(html)
                val tracksJsonStr = tracksMatch?.groupValues?.getOrNull(1)
                if (!tracksJsonStr.isNullOrBlank()) {
                    val tracks = JSONArray(tracksJsonStr)
                    var selectedBaseUrl: String? = null
                    // Prefer Hindi or English track
                    for (i in 0 until tracks.length()) {
                        val obj = tracks.optJSONObject(i) ?: continue
                        val lang = obj.optString("languageCode")
                        val baseUrl = obj.optString("baseUrl").replace("\\u0026", "&")
                        if (baseUrl.isNotBlank()) {
                            if (selectedBaseUrl == null || lang.startsWith("hi") || lang.startsWith("en")) {
                                selectedBaseUrl = baseUrl
                                if (lang.startsWith("hi")) break
                            }
                        }
                    }
                    if (!selectedBaseUrl.isNullOrBlank()) {
                        val capReq = Request.Builder()
                            .url(selectedBaseUrl)
                            .header("User-Agent", browserUserAgent)
                            .build()
                        val xml = client.newCall(capReq).execute().use { resp ->
                            if (resp.isSuccessful) resp.body?.string().orEmpty() else ""
                        }
                        if (xml.isNotBlank()) {
                            cues = SubtitleParser.parseYouTubeTimedTextXml(xml)
                        }
                    }
                }
            }
        }
        return durationMs to cues
    }

    private fun resolveKickLink(originalUrl: String, kickId: KickIdentifier): ResolvedLinkSource {
        val apiUrl = when (kickId) {
            is KickIdentifier.Vod -> "https://kick.com/api/v1/video/${kickId.uuid}"
            is KickIdentifier.Clip -> "https://kick.com/api/v2/clips/${kickId.clipId}"
        }

        val req = Request.Builder()
            .url(apiUrl)
            .header("User-Agent", browserUserAgent)
            .header("Accept", "application/json")
            .build()

        var title = "Kick Video"
        var channel = "Kick"
        var thumb: String? = null
        var durationMs = 0L
        var streamUrl: String? = null

        val apiResult = runCatching {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    error("Kick API HTTP ${resp.code}")
                }
                val body = resp.body?.string().orEmpty()
                val json = JSONObject(body)

                when (kickId) {
                    is KickIdentifier.Vod -> {
                        val livestream = json.optJSONObject("livestream")
                        title = livestream?.optString("session_title")?.takeIf { it.isNotBlank() }
                            ?: json.optString("title", "Kick VOD")
                        channel = livestream?.optJSONObject("channel")?.optString("slug")
                            ?: "Kick Creator"
                        durationMs = (livestream?.optLong("duration", 0L) ?: 0L).let { d ->
                            if (d in 1L..86_400L) d * 1000L else d
                        }
                        thumb = livestream?.optJSONObject("thumbnail")?.optString("url")
                            ?: livestream?.optString("thumbnail")
                        streamUrl = json.optString("source").takeIf { it.startsWith("http") }
                    }
                    is KickIdentifier.Clip -> {
                        val clipObj = json.optJSONObject("clip") ?: json
                        title = clipObj.optString("title", "Kick Clip")
                        channel = clipObj.optJSONObject("channel")?.optString("username") ?: "Kick"
                        val sec = clipObj.optLong("duration", 0L)
                        durationMs = if (sec > 0L) sec * 1000L else 0L
                        thumb = clipObj.optString("thumbnail_url").takeIf { it.startsWith("http") }
                        streamUrl = clipObj.optString("clip_url").takeIf { it.startsWith("http") }
                            ?: clipObj.optString("video_url").takeIf { it.startsWith("http") }
                    }
                }
            }
        }

        val hasDirectStream = !streamUrl.isNullOrBlank()
        val note = if (hasDirectStream) {
            "Kick लाइवस्ट्रीम/VOD डायरेक्ट स्ट्रीम मिल गई! टाइमस्टैम्प डालकर सीधे 9:16 HD क्लिप्स काटें।"
        } else if (apiResult.isFailure) {
            "Kick लिंक पहचाना गया (Cloudflare सुरक्षा के कारण डायरेक्ट स्ट्रीम ब्लॉक है) — टाइमस्टैम्प पेस्ट करें और डाउनलोड की हुई वीडियो फाइल चुनें।"
        } else {
            "Kick लिंक लोड हो गया — अब डाउनलोड वीडियो फाइल चुनकर टाइमस्टैम्प काटें।"
        }

        return ResolvedLinkSource(
            platform = VideoLinkPlatform.KICK,
            originalUrl = originalUrl,
            videoId = when (kickId) {
                is KickIdentifier.Vod -> kickId.uuid
                is KickIdentifier.Clip -> kickId.clipId
            },
            title = title,
            channelName = channel,
            thumbnailUrl = thumb,
            durationMs = durationMs,
            playableStreamUrl = streamUrl,
            requiresLocalVideoAttach = !hasDirectStream,
            statusNote = note
        )
    }

    sealed interface KickIdentifier {
        data class Vod(val uuid: String) : KickIdentifier
        data class Clip(val clipId: String) : KickIdentifier
    }

    companion object {
        fun extractYouTubeVideoId(url: String): String? {
            val patterns = listOf(
                Regex("""(?:youtube\.com/watch\?(?:.*&)?v=)([A-Za-z0-9_-]{11})"""),
                Regex("""(?:youtu\.be/)([A-Za-z0-9_-]{11})"""),
                Regex("""(?:youtube\.com/(?:live|shorts|embed)/)([A-Za-z0-9_-]{11})""")
            )
            for (regex in patterns) {
                val match = regex.find(url)
                if (match != null) return match.groupValues[1]
            }
            return null
        }

        fun extractKickIdentifier(url: String): KickIdentifier? {
            if (!url.contains("kick.com", ignoreCase = true)) return null
            // Clip query parameter or path
            val clipParam = Regex("""[?&]clip=([A-Za-z0-9_-]+)""").find(url)
                ?: Regex("""kick\.com/(?:[^/]+/)?clips/([A-Za-z0-9_-]+)""").find(url)
            if (clipParam != null) {
                return KickIdentifier.Clip(clipParam.groupValues[1])
            }
            // VOD UUID: kick.com/<channel>/videos/<uuid> or kick.com/video/<uuid>
            val vodMatch = Regex("""kick\.com/(?:[^/]+/videos|video)/([A-Za-z0-9-]+)""").find(url)
            if (vodMatch != null) {
                return KickIdentifier.Vod(vodMatch.groupValues[1])
            }
            return null
        }
    }
}
