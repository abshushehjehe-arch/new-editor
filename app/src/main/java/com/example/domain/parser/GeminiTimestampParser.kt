package com.example.domain.parser

import com.example.domain.model.ParsedClip
import com.example.domain.model.SingleTimestampMode
import java.util.Locale
import kotlin.math.max

/**
 * Deterministic, ultra-fast parser for timestamp outputs copied from YouTube's
 * "Ask / Gemini" button, video chapters, highlight lists, and multi-hour podcasts (5h–12h+).
 *
 * Supports:
 * - Explicit ranges: 00:12:34 - 00:15:20, 10:05:10 – 10:09:45, [1:02:03 to 1:05:00], 05:10 से 07:40
 * - Multi-line Gemini cards: Title on line 1, "Timestamp: 03:14:20 - 03:17:05" or "Start:/End:" on next lines
 * - Single-timestamp chapter/highlight lists: * 01:15:30 Key Insight (auto-computes end time)
 * - Devanagari numerals (०-९) automatically normalized to ASCII digits
 * - 64-bit Long millisecond math supporting 100+ hour videos cleanly
 */
object GeminiTimestampParser {

    // Matches H:MM:SS, HH:MM:SS, HHH:MM:SS, or MM:SS (with optional .ms)
    private const val TC = """(?:\d{1,3}:\d{2}:\d{2}|\d{1,2}:\d{2})(?:\.\d{1,3})?"""

    private val rangeRegex = Regex(
        """[\[\(\*`]*($TC)[\]\)\*`]*\s*(?:[-–—~→]|->|\bto\b|से)\s*[\[\(\*`]*($TC)[\]\)\*`]*""",
        RegexOption.IGNORE_CASE
    )

    private val singleTimeRegex = Regex(
        """(?<![\d:])($TC)(?![\d:])"""
    )

    private val startLabelRegex = Regex(
        """(?:start|from|शुरू|प्रारंभ)\s*[:=-]?\s*[\[\(\*`]*($TC)""",
        RegexOption.IGNORE_CASE
    )

    private val endLabelRegex = Regex(
        """(?:end|until|to|खत्म|अंत|तक)\s*[:=-]?\s*[\[\(\*`]*($TC)""",
        RegexOption.IGNORE_CASE
    )

    private val markdownNoiseRegex = Regex("""^[\s>*#•●▪▫\-–—]+""")
    private val leadingNumberingRegex = Regex("""^(?:(?:clip|part|segment|क्लिप|भाग)\s*)?\d{1,3}\s*[.):\-–—]\s*""", RegexOption.IGNORE_CASE)
    private val timestampPrefixNoise = Regex("""^(?:timestamp|time|timestamps|duration|टाइमस्टैम्प|समय)\s*[:\-–—]?\s*""", RegexOption.IGNORE_CASE)

    data class ParseResult(
        val clips: List<ParsedClip>,
        val hasInferredSingleTimestamps: Boolean
    )

    fun parse(
        rawText: String,
        videoDurationMs: Long? = null,
        singleMode: SingleTimestampMode = SingleTimestampMode.NEXT_TIMESTAMP
    ): ParseResult {
        if (rawText.isBlank()) {
            return ParseResult(emptyList(), false)
        }

        val normalized = normalizeNumerals(rawText)
        val lines = normalized.lines()

        // First pass: collect explicit range hits and Start/End paired hits, or single timestamps
        val explicitEntries = mutableListOf<RawEntry>()
        val singleEntries = mutableListOf<SingleEntry>()

        var pendingHeading: String? = null
        var pendingStartMs: Long? = null
        var pendingStartTitle: String? = null

        var i = 0
        while (i < lines.size) {
            val rawLine = lines[i].trim()
            if (rawLine.isEmpty()) {
                pendingHeading = null
                i++
                continue
            }

            // Check explicit range on the same line
            val rangeMatch = rangeRegex.find(rawLine)
            if (rangeMatch != null) {
                val startMs = parseTimecodeToMs(rangeMatch.groupValues[1])
                val endMs = parseTimecodeToMs(rangeMatch.groupValues[2])
                if (startMs != null && endMs != null && endMs > startMs) {
                    val remainder = cleanTitleText(
                        rawLine.removeRange(rangeMatch.range)
                    )
                    val title = when {
                        remainder.isNotBlank() && !isBoilerplateLabel(remainder) -> remainder
                        !pendingHeading.isNullOrBlank() -> pendingHeading
                        else -> ""
                    }
                    // Look ahead 1 line for a short description/note if line has no timestamps
                    val note = extractOptionalNote(lines, i + 1)
                    explicitEntries.add(
                        RawEntry(
                            startMs = startMs,
                            endMs = endMs,
                            title = title,
                            note = note
                        )
                    )
                    pendingHeading = null
                    pendingStartMs = null
                    i++
                    continue
                }
            }

            // Check multi-line "Start: HH:MM:SS" followed by "End: HH:MM:SS"
            val startLabelMatch = startLabelRegex.find(rawLine)
            if (startLabelMatch != null) {
                val sMs = parseTimecodeToMs(startLabelMatch.groupValues[1])
                if (sMs != null) {
                    pendingStartMs = sMs
                    pendingStartTitle = pendingHeading
                    i++
                    continue
                }
            }

            val endLabelMatch = endLabelRegex.find(rawLine)
            if (endLabelMatch != null && pendingStartMs != null) {
                val eMs = parseTimecodeToMs(endLabelMatch.groupValues[1])
                if (eMs != null && eMs > pendingStartMs) {
                    val note = extractOptionalNote(lines, i + 1)
                    explicitEntries.add(
                        RawEntry(
                            startMs = pendingStartMs,
                            endMs = eMs,
                            title = pendingStartTitle.orEmpty(),
                            note = note
                        )
                    )
                    pendingStartMs = null
                    pendingStartTitle = null
                    pendingHeading = null
                    i++
                    continue
                }
            }

            // Check single timestamp on this line
            val singleMatches = singleTimeRegex.findAll(rawLine).toList()
            if (singleMatches.size == 1) {
                val match = singleMatches.first()
                val startMs = parseTimecodeToMs(match.groupValues[1])
                if (startMs != null) {
                    val remainder = cleanTitleText(rawLine.removeRange(match.range))
                    val title = when {
                        remainder.isNotBlank() && !isBoilerplateLabel(remainder) -> remainder
                        !pendingHeading.isNullOrBlank() -> pendingHeading
                        else -> ""
                    }
                    val note = extractOptionalNote(lines, i + 1)
                    singleEntries.add(SingleEntry(startMs = startMs, title = title, note = note))
                    pendingHeading = null
                    i++
                    continue
                }
            } else if (singleMatches.size >= 2) {
                // Two timestamps on one line without standard dash (e.g. "01:02:00 / 01:05:00 Topic")
                val sMs = parseTimecodeToMs(singleMatches[0].groupValues[1])
                val eMs = parseTimecodeToMs(singleMatches[1].groupValues[1])
                if (sMs != null && eMs != null && eMs > sMs) {
                    val afterSecond = rawLine.substring(singleMatches[1].range.last + 1)
                    val beforeFirst = rawLine.substring(0, singleMatches[0].range.first)
                    val remainder = cleanTitleText("$beforeFirst $afterSecond")
                    val title = when {
                        remainder.isNotBlank() && !isBoilerplateLabel(remainder) -> remainder
                        !pendingHeading.isNullOrBlank() -> pendingHeading
                        else -> ""
                    }
                    explicitEntries.add(RawEntry(sMs, eMs, title, extractOptionalNote(lines, i + 1)))
                    pendingHeading = null
                    i++
                    continue
                }
            }

            // Otherwise treat clean short heading lines as potential title for the next timestamp line
            val cleanedCandidate = cleanTitleText(rawLine)
            if (cleanedCandidate.length in 3..90 && !isIntroChatter(cleanedCandidate)) {
                pendingHeading = cleanedCandidate
            }
            i++
        }

        // Combine explicit entries and inferred single entries
        val combined = mutableListOf<ParsedClip>()
        var hasInferred = false

        explicitEntries.forEach { entry ->
            val clamped = clampToDuration(entry.startMs, entry.endMs, videoDurationMs)
            combined.add(
                ParsedClip(
                    id = "exp_${entry.startMs}_${entry.endMs}_${combined.size}",
                    index = combined.size + 1,
                    title = entry.title.ifBlank { "Clip ${combined.size + 1}" },
                    note = entry.note,
                    startMs = clamped.first,
                    endMs = clamped.second,
                    isSelected = !clamped.third,
                    isSingleTimestampInferred = false,
                    isOutOfBounds = clamped.third
                )
            )
        }

        if (singleEntries.isNotEmpty()) {
            hasInferred = true
            val sortedSingles = singleEntries.sortedBy { it.startMs }
            sortedSingles.forEachIndexed { idx, entry ->
                val rawEndMs = when (val fixed = singleMode.fixedDurationMs) {
                    null -> {
                        // Next timestamp mode
                        val nextStart = sortedSingles.getOrNull(idx + 1)?.startMs
                        when {
                            nextStart != null && nextStart > entry.startMs -> nextStart
                            videoDurationMs != null && videoDurationMs > entry.startMs -> videoDurationMs
                            else -> entry.startMs + 60_000L
                        }
                    }
                    else -> entry.startMs + fixed
                }

                val clamped = clampToDuration(entry.startMs, rawEndMs, videoDurationMs)
                // Avoid duplicate if an explicit entry already covers the exact same start
                val alreadyExists = combined.any { kotlin.math.abs(it.startMs - clamped.first) < 1000L }
                if (!alreadyExists && clamped.second > clamped.first) {
                    combined.add(
                        ParsedClip(
                            id = "sng_${clamped.first}_${clamped.second}_${combined.size}",
                            index = combined.size + 1,
                            title = entry.title.ifBlank { "Clip ${combined.size + 1}" },
                            note = entry.note,
                            startMs = clamped.first,
                            endMs = clamped.second,
                            isSelected = !clamped.third,
                            isSingleTimestampInferred = true,
                            isOutOfBounds = clamped.third
                        )
                    )
                }
            }
        }

        val sortedFinal = combined
            .sortedBy { it.startMs }
            .mapIndexed { idx, clip ->
                clip.copy(
                    index = idx + 1,
                    title = if (clip.title.startsWith("Clip ")) "Clip ${idx + 1}" else clip.title
                )
            }

        return ParseResult(
            clips = sortedFinal,
            hasInferredSingleTimestamps = hasInferred
        )
    }

    private fun clampToDuration(
        startMs: Long,
        endMs: Long,
        videoDurationMs: Long?
    ): Triple<Long, Long, Boolean> {
        if (videoDurationMs == null || videoDurationMs <= 0L) {
            return Triple(startMs, max(endMs, startMs + 1000L), false)
        }
        if (startMs >= videoDurationMs) {
            return Triple(startMs, endMs, true)
        }
        val safeEnd = endMs.coerceAtMost(videoDurationMs)
        return Triple(startMs, safeEnd, safeEnd <= startMs)
    }

    private fun extractOptionalNote(lines: List<String>, nextIndex: Int): String {
        if (nextIndex >= lines.size) return ""
        val nextLine = lines[nextIndex].trim()
        if (nextLine.isEmpty()) return ""
        if (singleTimeRegex.containsMatchIn(nextLine)) return ""
        val cleaned = cleanTitleText(nextLine)
        return if (cleaned.length in 10..180 && !isIntroChatter(cleaned)) cleaned else ""
    }

    private fun cleanTitleText(raw: String): String {
        var text = raw.trim()
        // Remove markdown emphasis and brackets
        text = text.replace("**", "")
            .replace("__", "")
            .replace("`", "")
            .replace(markdownNoiseRegex, "")
            .trim()
        text = leadingNumberingRegex.replace(text, "").trim()
        text = timestampPrefixNoise.replace(text, "").trim()
        // Strip leading/trailing separators like ":", "-", "|", "–", "—"
        text = text.trimStart(':', '-', '–', '—', '|', '•', '.', ')', ']', ' ')
            .trimEnd(':', '-', '–', '—', '|', '(', '[', ' ')
            .trim()
        // Collapse double spaces
        return text.replace(Regex("""\s{2,}"""), " ")
    }

    private fun isBoilerplateLabel(text: String): Boolean {
        val lower = text.lowercase(Locale.ROOT)
        return lower in setOf(
            "timestamp", "timestamps", "time", "clip", "segment", "range", "start", "end",
            "टाइमस्टैम्प", "समय", "क्लिप"
        )
    }

    private fun isIntroChatter(text: String): Boolean {
        val lower = text.lowercase(Locale.ROOT)
        return lower.startsWith("here are") ||
            lower.startsWith("sure") ||
            lower.startsWith("certainly") ||
            lower.startsWith("below are") ||
            lower.contains("key moments from the video") ||
            lower.contains("यहाँ वीडियो के मुख्य")
    }

    fun normalizeNumerals(input: String): String {
        val sb = StringBuilder(input.length)
        for (ch in input) {
            when (ch) {
                in '०'..'९' -> sb.append(('0'.code + (ch.code - '०'.code)).toChar())
                '：' -> sb.append(':')
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    fun parseTimecodeToMs(raw: String): Long? {
        val cleaned = normalizeNumerals(raw).trim()
        val dotParts = cleaned.split('.')
        val mainPart = dotParts[0]
        val fractionalMs = if (dotParts.size > 1) {
            dotParts[1].padEnd(3, '0').take(3).toLongOrNull() ?: 0L
        } else {
            0L
        }
        val parts = mainPart.split(':')
        return when (parts.size) {
            2 -> {
                val minutes = parts[0].toLongOrNull() ?: return null
                val seconds = parts[1].toLongOrNull() ?: return null
                if (seconds >= 60) return null
                ((minutes * 60L) + seconds) * 1000L + fractionalMs
            }
            3 -> {
                val hours = parts[0].toLongOrNull() ?: return null
                val minutes = parts[1].toLongOrNull() ?: return null
                val seconds = parts[2].toLongOrNull() ?: return null
                if (minutes >= 60 || seconds >= 60) return null
                ((hours * 3600L) + (minutes * 60L) + seconds) * 1000L + fractionalMs
            }
            else -> null
        }
    }

    fun formatDurationMs(ms: Long, forceHours: Boolean = false): String {
        val totalSeconds = (ms.coerceAtLeast(0L)) / 1000L
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        val seconds = totalSeconds % 60L
        return if (hours > 0L || forceHours) {
            String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%02d:%02d", minutes, seconds)
        }
    }

    fun formatHumanLength(ms: Long): String {
        val totalSeconds = (ms.coerceAtLeast(0L)) / 1000L
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        val seconds = totalSeconds % 60L
        return buildString {
            if (hours > 0) append("${hours}h ")
            if (minutes > 0 || hours > 0) append("${minutes}m ")
            append("${seconds}s")
        }.trim()
    }

    fun formatFileSize(bytes: Long): String {
        if (bytes <= 0L) return "—"
        val kb = bytes / 1024.0
        val mb = kb / 1024.0
        val gb = mb / 1024.0
        return when {
            gb >= 1.0 -> String.format(Locale.US, "%.2f GB", gb)
            mb >= 1.0 -> String.format(Locale.US, "%.1f MB", mb)
            else -> String.format(Locale.US, "%.0f KB", kb)
        }
    }

    fun sanitizeFileName(raw: String): String {
        val cleaned = raw
            .replace(Regex("""[\\/:*?"<>|\r\n]+"""), "_")
            .replace(Regex("""\s+"""), "_")
            .trim('_')
            .take(48)
        return cleaned.ifBlank { "Clip" }
    }

    /**
     * Built-in sample prompt demonstrating real YouTube "Ask Gemini" multi-hour podcast output
     * (10-hour video) so users can test timestamp parsing immediately.
     */
    const val SAMPLE_YOUTUBE_GEMINI_PROMPT: String = """Here are the top viral moments & key highlights from this 10-hour podcast stream:

* **00:14:20 - 00:16:45** - Powerful opening story on building focus
  Great hook for YouTube Shorts and Reels.
* **01:42:10 - 01:45:30** - How to scale a creator studio with zero team
* **04:08:15 – 04:11:05** - Funny behind-the-scenes breakdown with the guest
* **06:55:40 - 06:58:20** - Step-by-step monetization strategy explained
* **09:34:12 - 09:37:50** - Emotional Q&A climax and final life advice"""

    private data class RawEntry(
        val startMs: Long,
        val endMs: Long,
        val title: String,
        val note: String
    )

    private data class SingleEntry(
        val startMs: Long,
        val title: String,
        val note: String
    )
}
