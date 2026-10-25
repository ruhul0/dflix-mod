package com.dflixtv.app.subs

import androidx.media3.common.MimeTypes
import java.util.Locale

/** What we know about the video when searching for subtitles. */
data class SubtitleQuery(
    val title: String,
    val year: Int?,
    val imdbId: String?,
    val tmdbId: Long?,
    val season: Int?,
    val episode: Int?,
    val language: Language,
    val fileName: String?,
    val fileSize: Long?,
    val streamUrl: String,
) {
    val isEpisode: Boolean get() = season != null && episode != null
}

data class SubtitleResult(
    val provider: String,
    val fileName: String,
    val languageCode: String,
    val downloads: Int,
    val hearingImpaired: Boolean,
    val hashMatch: Boolean,
    val format: String,
    /** Provider-specific data needed to download the file. */
    val downloadRef: String,
    val encodingHint: String? = null,
) {
    val displayName: String
        get() = buildString {
            if (hashMatch) append("[exact match] ")
            append(fileName)
        }

    val detail: String
        get() = listOfNotNull(
            provider,
            languageCode.uppercase(),
            if (downloads > 0) "$downloads downloads" else null,
            if (hearingImpaired) "HI" else null,
        ).joinToString(" · ")
}

interface SubtitleProvider {
    val name: String
    val isConfigured: Boolean
    suspend fun search(query: SubtitleQuery): List<SubtitleResult>

    /** Returns the subtitle file name and raw bytes. */
    suspend fun download(result: SubtitleResult, query: SubtitleQuery): Pair<String, ByteArray>
}

data class Language(val code3: String, val code2: String, val name: String) {
    companion object {
        val ALL = listOf(
            Language("eng", "en", "English"),
            Language("ben", "bn", "Bengali"),
            Language("hin", "hi", "Hindi"),
            Language("ara", "ar", "Arabic"),
            Language("spa", "es", "Spanish"),
            Language("fre", "fr", "French"),
            Language("ger", "de", "German"),
            Language("ita", "it", "Italian"),
            Language("por", "pt", "Portuguese"),
            Language("pob", "pt-br", "Portuguese (Brazil)"),
            Language("rus", "ru", "Russian"),
            Language("tur", "tr", "Turkish"),
            Language("ind", "id", "Indonesian"),
            Language("may", "ms", "Malay"),
            Language("chi", "zh-cn", "Chinese (Simplified)"),
            Language("zht", "zh-tw", "Chinese (Traditional)"),
            Language("jpn", "ja", "Japanese"),
            Language("kor", "ko", "Korean"),
            Language("tam", "ta", "Tamil"),
            Language("tel", "te", "Telugu"),
            Language("mal", "ml", "Malayalam"),
            Language("urd", "ur", "Urdu"),
            Language("per", "fa", "Persian"),
            Language("dut", "nl", "Dutch"),
            Language("pol", "pl", "Polish"),
            Language("swe", "sv", "Swedish"),
            Language("gre", "el", "Greek"),
            Language("vie", "vi", "Vietnamese"),
            Language("tha", "th", "Thai"),
        )

        fun byCode3(code: String) = ALL.firstOrNull { it.code3 == code } ?: ALL.first()

        /** Two-letter BCP-47 language tag for ExoPlayer track metadata. */
        fun tagFor(code: String): String =
            ALL.firstOrNull { it.code3 == code || it.code2 == code }?.code2?.substringBefore('-')
                ?: runCatching { Locale(code).language }.getOrDefault(code)
    }
}

object SubtitleFormats {
    val SUPPORTED = setOf("srt", "vtt", "ass", "ssa")

    fun mimeType(fileName: String): String = when (fileName.substringAfterLast('.').lowercase()) {
        "vtt" -> MimeTypes.TEXT_VTT
        "ass", "ssa" -> MimeTypes.TEXT_SSA
        else -> MimeTypes.APPLICATION_SUBRIP
    }
}
