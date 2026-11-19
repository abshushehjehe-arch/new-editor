package com.example.media

import com.example.domain.model.SubtitleCue

/**
 * Abstraction for cloud AI speech-to-subtitle transcription.
 * Because Firebase setup was declined, this uses a transparent placeholder implementation
 * while real on-device SpeechRecognizer dictation, YouTube caption fetching, and SRT/VTT
 * transcript slicing power live subtitle creation without Firebase.
 */
interface AiCaptionRepository {
    val isCloudAiAvailable: Boolean
    suspend fun transcribeClip(clipDurationMs: Long, clipTitle: String): AiTranscriptionResult
}

data class AiTranscriptionResult(
    val cues: List<SubtitleCue>,
    val requiresFirebaseNotice: Boolean,
    val noticeMessageHindi: String,
    val noticeMessageEnglish: String
)

class LocalFallbackAiCaptionRepository : AiCaptionRepository {
    override val isCloudAiAvailable: Boolean = false

    override suspend fun transcribeClip(
        clipDurationMs: Long,
        clipTitle: String
    ): AiTranscriptionResult {
        val safeDur = clipDurationMs.coerceAtLeast(6_000L)
        val step = (safeDur / 3L).coerceAtLeast(2_000L)
        val sampleCues = listOf(
            SubtitleCue(
                id = "ph_0",
                startMs = 0L,
                endMs = step,
                text = clipTitle.ifBlank { "यहाँ वीडियो का पहला सबटाइटल दिखेगा" }
            ),
            SubtitleCue(
                id = "ph_1",
                startMs = step,
                endMs = (step * 2L).coerceAtMost(safeDur),
                text = "ऑन-डिवाइस माइक, SRT/VTT या YouTube ट्रांसक्रिप्ट से असली कैप्शन जोड़ें"
            )
        )
        return AiTranscriptionResult(
            cues = sampleCues,
            requiresFirebaseNotice = true,
            noticeMessageHindi = "क्लाउड Gemini ऑटो स्पीच-टू-टेक्स्ट के लिए Firebase AI Logic ज़रूरी है। अभी सैंपल सबटाइटल लोड हुए हैं — असली कैप्शन के लिए 'माइक से बोलें', 'YouTube ट्रांसक्रिप्ट पेस्ट', या 'SRT इम्पोर्ट' उपयोग करें।",
            noticeMessageEnglish = "Cloud Gemini auto speech-to-text requires Firebase AI Logic. Sample cues loaded — use Live Mic Dictation, YouTube Transcript Paste, or SRT/VTT Import for real synced captions."
        )
    }
}
