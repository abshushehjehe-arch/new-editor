package com.example

import com.example.domain.model.SingleTimestampMode
import com.example.domain.parser.GeminiTimestampParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiTimestampParserTest {

    @Test
    fun parsesTenToTwelveHourYouTubeGeminiRanges() {
        val prompt = """
            Here are the best clips from your 12-hour video:
            * **00:12:34 - 00:15:20** - Opening Hook
              Strong story intro
            * **05:45:00 – 05:49:30** | Mid-stream Breakdown
            * [10:15:10 to 10:18:45] Deep Technical Talk
            * (11:55:00 - 11:59:30) Final Climax
        """.trimIndent()

        val result = GeminiTimestampParser.parse(
            rawText = prompt,
            videoDurationMs = 12L * 3600L * 1000L
        )

        assertFalse(result.hasInferredSingleTimestamps)
        assertEquals(4, result.clips.size)

        assertEquals("Opening Hook", result.clips[0].title)
        assertEquals(754_000L, result.clips[0].startMs)
        assertEquals(920_000L, result.clips[0].endMs)
        assertEquals("Strong story intro", result.clips[0].note)

        assertEquals("Deep Technical Talk", result.clips[2].title)
        assertEquals((10L * 3600 + 15 * 60 + 10) * 1000L, result.clips[2].startMs)
        assertEquals((10L * 3600 + 18 * 60 + 45) * 1000L, result.clips[2].endMs)

        assertEquals("Final Climax", result.clips[3].title)
        assertEquals((11L * 3600 + 55 * 60) * 1000L, result.clips[3].startMs)
        assertEquals((11L * 3600 + 59 * 60 + 30) * 1000L, result.clips[3].endMs)
    }

    @Test
    fun parsesHindiDevanagariTimestampsAndConnectors() {
        val prompt = """
            1. ०९:१०:०० से ०९:१२:३० - मोटिवेशनल कहानी
            2. १०:०५:१५ - १०:०७:०० : मजेदार पल
        """.trimIndent()

        val result = GeminiTimestampParser.parse(
            rawText = prompt,
            videoDurationMs = 11L * 3600L * 1000L
        )

        assertEquals(2, result.clips.size)
        assertEquals((9L * 3600 + 10 * 60) * 1000L, result.clips[0].startMs)
        assertEquals((9L * 3600 + 12 * 60 + 30) * 1000L, result.clips[0].endMs)
        assertEquals("मोटिवेशनल कहानी", result.clips[0].title)
        assertEquals("मजेदार पल", result.clips[1].title)
    }

    @Test
    fun parsesMultiLineGeminiCardsWithStartAndEndLabels() {
        val prompt = """
            ### 1. Best Podcast Story
            Start: 04:20:00
            End: 04:23:15

            ### 2. Viral Q&A Moment
            Timestamp: 08:50:10 - 08:52:40
        """.trimIndent()

        val result = GeminiTimestampParser.parse(prompt)

        assertEquals(2, result.clips.size)
        assertEquals("Best Podcast Story", result.clips[0].title)
        assertEquals((4L * 3600 + 20 * 60) * 1000L, result.clips[0].startMs)
        assertEquals((4L * 3600 + 23 * 60 + 15) * 1000L, result.clips[0].endMs)

        assertEquals("Viral Q&A Moment", result.clips[1].title)
        assertEquals((8L * 3600 + 50 * 60 + 10) * 1000L, result.clips[1].startMs)
        assertEquals((8L * 3600 + 52 * 60 + 40) * 1000L, result.clips[1].endMs)
    }

    @Test
    fun parsesSingleTimestampChaptersInBothModesAndFlagsOutOfBounds() {
        val prompt = """
            * 0:00 Intro
            * 02:30:00 Main Workshop
            * 09:00:00 Out of Bounds Clip
        """.trimIndent()

        val fiveHoursMs = 5L * 3600L * 1000L

        val fixedResult = GeminiTimestampParser.parse(
            rawText = prompt,
            videoDurationMs = fiveHoursMs,
            singleMode = SingleTimestampMode.FIXED_60S
        )

        assertTrue(fixedResult.hasInferredSingleTimestamps)
        assertEquals(3, fixedResult.clips.size)
        assertEquals(60_000L, fixedResult.clips[0].endMs)
        assertEquals((2L * 3600 + 31 * 60) * 1000L, fixedResult.clips[1].endMs)
        assertTrue(fixedResult.clips[2].isOutOfBounds)
        assertFalse(fixedResult.clips[2].isSelected)
    }
}
