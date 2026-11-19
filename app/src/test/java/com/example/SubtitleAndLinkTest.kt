package com.example

import com.example.domain.parser.SubtitleParser
import com.example.media.OnlineVideoLinkResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleAndLinkTest {

    @Test
    fun extractsYouTubeAndKickVideoIdentifiers() {
        assertEquals(
            "dQw4w9WgXcQ",
            OnlineVideoLinkResolver.extractYouTubeVideoId("https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=3600s")
        )
        assertEquals(
            "abcDEF12345",
            OnlineVideoLinkResolver.extractYouTubeVideoId("https://youtu.be/abcDEF12345")
        )
        assertEquals(
            "xyz98765432",
            OnlineVideoLinkResolver.extractYouTubeVideoId("https://www.youtube.com/live/xyz98765432")
        )

        val kickVod = OnlineVideoLinkResolver.extractKickIdentifier(
            "https://kick.com/samayraina/videos/1234abcd-5678-ef90-1234-567890abcdef"
        )
        assertTrue(kickVod is OnlineVideoLinkResolver.KickIdentifier.Vod)
        assertEquals(
            "1234abcd-5678-ef90-1234-567890abcdef",
            (kickVod as OnlineVideoLinkResolver.KickIdentifier.Vod).uuid
        )

        val kickClip = OnlineVideoLinkResolver.extractKickIdentifier(
            "https://kick.com/creator?clip=clip_01JABC123"
        )
        assertTrue(kickClip is OnlineVideoLinkResolver.KickIdentifier.Clip)
        assertEquals("clip_01JABC123", (kickClip as OnlineVideoLinkResolver.KickIdentifier.Clip).clipId)
    }

    @Test
    fun parsesYouTubeTranscriptAndSlicesForTenHourClip() {
        val transcript = """
            09:10:00
            नमस्ते दोस्तों आज का सबसे बड़ा सीक्रेट यह है
            09:10:05
            कि लगातार मेहनत करने से सब कुछ बदल जाता है
            09:15:00
            यह बाहर का हिस्सा है
        """.trimIndent()

        val allCues = SubtitleParser.parseAny(transcript)
        assertEquals(3, allCues.size)

        val clipStartMs = (9L * 3600 + 10 * 60) * 1000L
        val clipEndMs = (9L * 3600 + 10 * 60 + 12) * 1000L

        val sliced = SubtitleParser.sliceCuesForClip(allCues, clipStartMs, clipEndMs, chunkForShorts = true)
        assertTrue(sliced.size >= 2)
        assertEquals(0L, sliced.first().startMs)
        assertTrue(sliced.last().endMs <= 12_000L)
    }

    @Test
    fun parsesSrtSubtitlesAccurately() {
        val srt = """
            1
            00:00:01,000 --> 00:00:04,500
            Welcome to ClipSlice 9:16 HD!

            2
            00:00:05,000 --> 00:00:08,000
            Burned-in subtitles at top or bottom.
        """.trimIndent()

        val cues = SubtitleParser.parseAny(srt)
        assertEquals(2, cues.size)
        assertEquals(1_000L, cues[0].startMs)
        assertEquals(4_500L, cues[0].endMs)
        assertEquals("Welcome to ClipSlice 9:16 HD!", cues[0].text)
    }
}
