package com.mardous.booming.data.local.lyrics

import com.mardous.booming.data.model.lyrics.LyricsFile
import kotlin.math.abs

/**
 * Finds a displayed lyrics line inside the raw text of a lyrics file and replaces it.
 *
 * The same text may appear several times (e.g. a chorus), so among all occurrences the one whose
 * nearest preceding timestamp is closest to the line's start time is chosen.
 */
object LyricsLineLocator {

    // [mm:ss.xx] (LRC) | hh:mm:ss,mmm --> (SRT) | begin="..." (TTML)
    private val TIMESTAMP_PATTERN = Regex(
        "\\[(\\d+):(\\d{1,2})(?:[.:](\\d{1,3}))?\\]" +
                "|(?:(\\d+):)?(\\d{1,2}):(\\d{1,2})[,.](\\d{1,3})\\s*-->" +
                "|begin=\"([^\"]+)\""
    )

    /**
     * @return the new file content, or null if [oldText] couldn't be located.
     */
    fun replace(
        content: String,
        format: LyricsFile.Format,
        lineStart: Long,
        oldText: String,
        newText: String
    ): String? {
        val searchText = if (format == LyricsFile.Format.TTML) oldText.escapeXml() else oldText
        val parts = searchText.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.isEmpty()) return null

        // The text must fill a whole line (or sit between tags/timestamps), so that a short
        // line doesn't match inside a longer one.
        val pattern = Regex(
            "(?:^|(?<=[\\]>]))[ \\t]*(" +
                    parts.joinToString("[ \\t]*\\r?\\n[ \\t]*") { Regex.escape(it) } +
                    ")[ \\t]*(?=$|[\\[<\\r\\n])",
            RegexOption.MULTILINE
        )

        val timestamps = TIMESTAMP_PATTERN.findAll(content)
            .mapNotNull { match -> parseTimestamp(match)?.let { match.range.first to it } }
            .toList()

        val target = pattern.findAll(content).minByOrNull { match ->
            val time = timestamps.lastOrNull { it.first < match.range.first }?.second
            if (time == null) Long.MAX_VALUE else abs(time - lineStart)
        } ?: return null

        val range = target.groups[1]?.range ?: return null
        val lineSeparator = if (content.contains("\r\n")) "\r\n" else "\n"
        val replacement = formatReplacement(newText, format, lineSeparator)
        return content.replaceRange(range, replacement)
    }

    private fun formatReplacement(text: String, format: LyricsFile.Format, lineSeparator: String): String {
        val lines = text.lines().map { it.trim() }
        return when (format) {
            // A blank line would end the SRT cue
            LyricsFile.Format.SRT -> lines.filter { it.isNotEmpty() }.joinToString(lineSeparator)
            // Extra lines would have no timestamp
            LyricsFile.Format.LRC -> lines.filter { it.isNotEmpty() }.joinToString(" ")
            LyricsFile.Format.TTML -> lines.filter { it.isNotEmpty() }.joinToString(" ").escapeXml()
        }
    }

    private fun parseTimestamp(match: MatchResult): Long? {
        val g = match.groupValues
        return when {
            g[2].isNotEmpty() -> {
                val minutes = g[1].toLong()
                val seconds = g[2].toLong()
                val fraction = g[3].padEnd(3, '0').take(3).ifEmpty { "0" }.toLong()
                (minutes * 60 + seconds) * 1000 + fraction
            }
            g[6].isNotEmpty() -> {
                val hours = g[4].ifEmpty { "0" }.toLong()
                val minutes = g[5].toLong()
                val seconds = g[6].toLong()
                val millis = g[7].padEnd(3, '0').take(3).toLong()
                ((hours * 60 + minutes) * 60 + seconds) * 1000 + millis
            }
            g[8].isNotEmpty() -> parseTtmlTime(g[8])
            else -> null
        }
    }

    private fun parseTtmlTime(value: String): Long? {
        val v = value.trim()
        return runCatching {
            when {
                v.endsWith("ms") -> v.removeSuffix("ms").toDouble().toLong()
                v.endsWith("s") -> (v.removeSuffix("s").toDouble() * 1000).toLong()
                v.contains(':') -> {
                    val parts = v.split(':').map { it.toDouble() }
                    (parts.fold(0.0) { acc, part -> acc * 60 + part } * 1000).toLong()
                }
                else -> (v.toDouble() * 1000).toLong()
            }
        }.getOrNull()
    }

    private fun String.escapeXml(): String =
        replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
