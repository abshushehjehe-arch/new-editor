package com.example.domain.parser

import com.example.domain.model.SubtitleCue
import java.util.Locale

/**
 * Parses SRT, WebVTT, YouTube TimedText XML, and copy-pasted YouTube Transcript text
 * into synchronized SubtitleCue timelines, and slices them cleanly for any clip range.
 */
object SubtitleParser {

    private val srtTimeRangeRegex = Regex(
        """(\d{1,2}:\d{2}:\d{2}[,.]\d{1,3}|\d{1,2}:\d{2}[,.]\d{1,3})\s*-->\s*(\d{1,2}:\d{2}:\d{2}[,.]\d{1,3}|\d{1,2}:\d{2}[,.]\d{1,3})"""
    )

    private val transcriptLineTimeRegex = Regex("""^\s*(\d{1,3}:\d{2}(?::\d{2})?)\s*(.*)$""")

    private val xmlTextTagRegex = Regex(
        """<text\s+[^>]*start="([\d.]+)"(?:[^>]*dur="([\d.]+)")?[^>]*>(.*?)</text>""",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
    )

    fun parseAny(raw: String): List<SubtitleCue> {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return emptyList()

        // 1. YouTube TimedText XML
        if (trimmed.contains("<text ") && trimmed.contains("start=")) {
            val xmlCues = parseYouTubeTimedTextXml(trimmed)
            if (xmlCues.isNotEmpty()) return xmlCues
        }

        // 2. SRT or VTT
        if (trimmed.contains("-->")) {
            val srtCues = parseSrtOrVtt(trimmed)
            if (srtCues.isNotEmpty()) return srtCues
        }

        // 3. YouTube "Show Transcript" copy-paste (timestamp line followed by text or inline)
        return parseTranscriptLines(trimmed)
    }

    fun parseSrtOrVtt(raw: String): List<SubtitleCue> {
        val normalized = raw.replace("\r\n", "\n").replace("\r", "\n")
        val blocks = normalized.split(Regex("""\n\s*\n"""))
        val cues = mutableListOf<SubtitleCue>()

        blocks.forEachIndexed { idx, block ->
            val lines = block.lines().map { it.trim() }.filter { it.isNotEmpty() }
            if (lines.isEmpty()) return@forEachIndexed
            val timeLineIndex = lines.indexOfFirst { it.contains("-->") }
            if (timeLineIndex == -1) return@forEachIndexed

            val match = srtTimeRangeRegex.find(lines[timeLineIndex]) ?: return@forEachIndexed
            val startMs = parseSrtTimeToMs(match.groupValues[1]) ?: return@forEachIndexed
            val endMs = parseSrtTimeToMs(match.groupValues[2]) ?: return@forEachIndexed
            val text = lines.drop(timeLineIndex + 1)
                .joinToString(" ")
                .replace(Regex("""<[^>]+>"""), "")
                .trim()

            if (text.isNotBlank() && endMs > startMs) {
                cues.add(
                    SubtitleCue(
                        id = "srt_${idx}_$startMs",
                        startMs = startMs,
                        endMs = endMs,
                        text = text
                    )
                )
            }
        }
        return cues.sortedBy { it.startMs }
    }

    fun parseYouTubeTimedTextXml(xml: String): List<SubtitleCue> {
        val cues = mutableListOf<SubtitleCue>()
        xmlTextTagRegex.findAll(xml).forEachIndexed { idx, match ->
            val startSec = match.groupValues[1].toDoubleOrNull() ?: return@forEachIndexed
            val durSec = match.groupValues[2].toDoubleOrNull() ?: 2.8
            val rawText = decodeHtmlEntities(match.groupValues[3])
                .replace("\n", " ")
                .replace(Regex("""\s+"""), " ")
                .trim()
            if (rawText.isNotBlank()) {
                val startMs = (startSec * 1000.0).toLong().coerceAtLeast(0L)
                val endMs = ((startSec + durSec.coerceAtLeast(0.6)) * 1000.0).toLong()
                cues.add(
                    SubtitleCue(
                        id = "yt_${idx}_$startMs",
                        startMs = startMs,
                        endMs = endMs,
                        text = rawText
                    )
                )
            }
        }
        return cues.sortedBy { it.startMs }
    }

    fun parseTranscriptLines(raw: String): List<SubtitleCue> {
        val normalized = GeminiTimestampParser.normalizeNumerals(raw)
        val lines = normalized.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val entries = mutableListOf<Pair<Long, String>>()

        var i = 0
        while (i < lines.size) {
            val match = transcriptLineTimeRegex.find(lines[i])
            if (match != null) {
                val timeMs = GeminiTimestampParser.parseTimecodeToMs(match.groupValues[1])
                if (timeMs != null) {
                    val inlineText = match.groupValues[2].trimStart('-', ':', '–', '—', ' ')
                    val text = if (inlineText.isNotBlank()) {
                        inlineText
                    } else if (i + 1 < lines.size && transcriptLineTimeRegex.find(lines[i + 1]) == null) {
                        i++
                        lines[i]
                    } else {
                        ""
                    }
                    if (text.isNotBlank()) {
                        entries.add(timeMs to text)
                    }
                }
            }
            i++
        }

        if (entries.isEmpty()) return emptyList()
        val sorted = entries.sortedBy { it.first }
        return sorted.mapIndexed { idx, (startMs, text) ->
            val nextStart = sorted.getOrNull(idx + 1)?.first
            val endMs = if (nextStart != null && nextStart > startMs) {
                minOf(nextStart, startMs + 6_000L)
            } else {
                startMs + 3_500L
            }
            SubtitleCue(
                id = "tr_${idx}_$startMs",
                startMs = startMs,
                endMs = endMs,
                text = text
            )
        }
    }

    /**
     * Extracts subtitle cues falling inside [clipStartMs..clipEndMs] and shifts their timestamps
     * to clip-relative milliseconds starting from 0ms, breaking long lines into punchy 9:16 caption chunks.
     */
    fun sliceCuesForClip(
        allVideoCues: List<SubtitleCue>,
        clipStartMs: Long,
        clipEndMs: Long,
        chunkForShorts: Boolean = true
    ): List<SubtitleCue> {
        val clipDur = (clipEndMs - clipStartMs).coerceAtLeast(1000L)
        val sliced = allVideoCues.mapNotNull { cue ->
            if (cue.endMs <= clipStartMs || cue.startMs >= clipEndMs) return@mapNotNull null
            val relStart = (cue.startMs - clipStartMs).coerceAtLeast(0L)
            val relEnd = (cue.endMs - clipStartMs).coerceAtMost(clipDur)
            if (relEnd <= relStart + 150L) return@mapNotNull null
            cue.copy(
                id = "rel_${relStart}_${cue.id}",
                startMs = relStart,
                endMs = relEnd
            )
        }
        return if (chunkForShorts) chunkForVerticalCaptions(sliced) else sliced
    }

    /**
     * Splits long sentences (> 6 words) into dynamic short 4-5 word Reels/Shorts subtitle bursts
     * with proportional timing so captions feel snappy and readable on 9:16 vertical screens.
     */
    fun chunkForVerticalCaptions(cues: List<SubtitleCue>, maxWordsPerCue: Int = 5): List<SubtitleCue> {
        val output = mutableListOf<SubtitleCue>()
        cues.forEach { cue ->
            val words = cue.text.trim().split(Regex("""\s+""")).filter { it.isNotEmpty() }
            if (words.size <= maxWordsPerCue) {
                output.add(cue)
            } else {
                val chunks = words.chunked(maxWordsPerCue)
                val totalDur = (cue.endMs - cue.startMs).coerceAtLeast(500L)
                val perChunk = totalDur / chunks.size
                chunks.forEachIndexed { idx, wordList ->
                    val cStart = cue.startMs + (idx * perChunk)
                    val cEnd = if (idx == chunks.lastIndex) cue.endMs else (cStart + perChunk)
                    output.add(
                        SubtitleCue(
                            id = "${cue.id}_c$idx",
                            startMs = cStart,
                            endMs = maxOf(cEnd, cStart + 350L),
                            text = wordList.joinToString(" ")
                        )
                    )
                }
            }
        }
        return output
    }

    /**
     * Converts plain pasted script/dialogue into evenly-timed caption chunks across [clipDurationMs].
     */
    fun createTimedCuesFromScript(script: String, clipDurationMs: Long): List<SubtitleCue> {
        val words = script.trim().split(Regex("""\s+""")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return emptyList()
        val chunks = words.chunked(5)
        val total = clipDurationMs.coerceAtLeast(chunks.size * 1_500L)
        val slot = total / chunks.size
        return chunks.mapIndexed { idx, chunk ->
            val start = idx * slot
            val end = if (idx == chunks.lastIndex) total else (start + slot)
            SubtitleCue(
                id = "script_${idx}_$start",
                startMs = start,
                endMs = end,
                text = chunk.joinToString(" ")
            )
        }
    }

    fun formatShortTime(ms: Long): String {
        val clamped = ms.coerceAtLeast(0L)
        val totalSec = clamped / 1000L
        val mins = totalSec / 60L
        val secs = totalSec % 60L
        val tenths = (clamped % 1000L) / 100L
        return String.format(Locale.US, "%02d:%02d.%d", mins, secs, tenths)
    }

    private fun parseSrtTimeToMs(raw: String): Long? {
        val normalized = raw.replace(',', '.')
        return GeminiTimestampParser.parseTimecodeToMs(normalized)
    }

    private fun decodeHtmlEntities(input: String): String {
        return input
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&#x27;", "'")
            .replace("&nbsp;", " ")
    }
}
