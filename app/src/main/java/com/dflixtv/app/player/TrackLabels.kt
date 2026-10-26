package com.dflixtv.app.player

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import java.util.Locale

/** Names for audio and subtitle tracks as the Audio & Subtitles panel shows them. */
object TrackLabels {
    /** Subtitles are English only; tracks without a language tag are usually English too. */
    fun isEnglishOrUnknown(language: String?): Boolean =
        language == null || language == "und" || language.substringBefore('-').lowercase() in setOf("en", "eng")

    fun languageName(tag: String?): String? =
        tag?.takeUnless { it == "und" }?.let { Locale.forLanguageTag(it).getDisplayLanguage(Locale.ENGLISH) }?.takeIf { it.isNotBlank() }

    /** "English [Original] · Dolby Digital 5.1" */
    fun audioLabel(format: Format, original: String?, fallback: String): String {
        val name = languageName(format.language) ?: format.label ?: fallback
        val codec = when (format.sampleMimeType) {
            MimeTypes.AUDIO_AC3 -> "Dolby Digital"
            MimeTypes.AUDIO_E_AC3, MimeTypes.AUDIO_E_AC3_JOC -> "Dolby Digital Plus"
            MimeTypes.AUDIO_TRUEHD -> "Dolby TrueHD"
            MimeTypes.AUDIO_DTS, MimeTypes.AUDIO_DTS_HD, MimeTypes.AUDIO_DTS_EXPRESS -> "DTS"
            else -> null
        }
        val channels = when (format.channelCount) {
            1 -> "Mono"
            2 -> "Stereo"
            6 -> "5.1"
            8 -> "7.1"
            else -> null
        }
        val isOriginal = original != null && format.language?.substringBefore('-') == original
        val extra = format.label?.takeIf { languageName(format.language) != null && !it.equals(name, ignoreCase = true) }
        return buildString {
            append(name)
            if (isOriginal) append(" [Original]")
            listOfNotNull(codec, channels).joinToString(" ").takeIf { it.isNotEmpty() }?.let { append(" · ").append(it) }
            extra?.let { append(" · ").append(it.take(30)) }
        }
    }

    /** "English", "English · Online" (downloaded), "English · CC". */
    fun subtitleLabel(format: Format): String {
        val name = languageName(format.language)
        // Downloaded subtitles are labelled with their release name; that's noise here.
        val downloaded = format.id?.contains("local-") == true
        val label = format.label?.takeIf { !downloaded && it.isNotBlank() && (name == null || !it.startsWith(name, ignoreCase = true)) }
        val tags = buildList {
            if (downloaded) add("Online")
            if (format.roleFlags and C.ROLE_FLAG_DESCRIBES_MUSIC_AND_SOUND != 0) add("CC")
            if (format.selectionFlags and C.SELECTION_FLAG_FORCED != 0) add("Forced")
        }
        return listOfNotNull(name ?: label ?: "Subtitles", label?.takeIf { name != null }, tags.joinToString(" ").ifEmpty { null })
            .joinToString(" · ")
    }
}
