package com.mardous.booming.data.local.lyrics.srt

import android.util.Log
import com.mardous.booming.data.local.lyrics.LyricsInfo
import com.mardous.booming.data.local.lyrics.LyricsParser
import com.mardous.booming.data.model.lyrics.SyncedLyrics
import java.io.Reader

class SrtLyricsParser : LyricsParser {

    override fun getInfo(lyrics: String): LyricsInfo {
        // An SRT cue timing line ("00:00:01,000 --> 00:00:02,000") is distinctive enough
        // that finding one anywhere is a reliable signal.
        val isSrt = lyrics.lineSequence().any { TIMESTAMP_PATTERN.containsMatchIn(it.clean()) }
        return if (isSrt) LyricsInfo.Valid(actuallySynced = true) else LyricsInfo.Invalid
    }

    override fun parseAsPlain(input: String): String? {
        val text = input.lineSequence()
            .map { it.clean() }
            .filterNot { it.isEmpty() || it.matches(SEQUENCE_PATTERN) || TIMESTAMP_PATTERN.containsMatchIn(it) }
            .joinToString("\n") { it.stripHtmlTags() }
        return text.ifBlank { null }
    }

    override fun parseAsPlain(reader: Reader): String? = parseAsPlain(reader.readText())

    override fun parse(
        reader: Reader,
        trackLength: Long,
        userOffset: Long,
        ignoreBlankLines: Boolean
    ): SyncedLyrics? {
        data class Block(val start: Long, val end: Long, val text: String)

        val blocks = mutableListOf<Block>()
        try {
            val rawLines = reader.buffered().use { it.readLines() }.map { it.clean() }
            var i = 0
            while (i < rawLines.size) {
                val timeMatch = TIMESTAMP_PATTERN.find(rawLines[i++]) ?: continue
                val start = parseTime(timeMatch, groupOffset = 1)
                val end = parseTime(timeMatch, groupOffset = 5)

                val textLines = mutableListOf<String>()
                while (i < rawLines.size) {
                    val textLine = rawLines[i]
                    // A cue ends at a blank line, or at the next cue (sequence number + timing line,
                    // or a timing line alone when the file has no blank separators).
                    if (textLine.isEmpty() || TIMESTAMP_PATTERN.containsMatchIn(textLine)) break
                    if (textLine.matches(SEQUENCE_PATTERN) &&
                        rawLines.getOrNull(i + 1)?.let { TIMESTAMP_PATTERN.containsMatchIn(it) } == true
                    ) break
                    textLines.add(textLine.stripHtmlTags())
                    i++
                }

                if (start < 0 || end <= start) continue
                val text = textLines.joinToString("\n").trim()
                if (text.isEmpty() && ignoreBlankLines) continue
                blocks.add(Block(start, end, text))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Couldn't parse SRT lyrics", e)
            return null
        }

        if (blocks.isEmpty()) return null
        blocks.sortBy { it.start }

        val syncedLines = mutableListOf<SyncedLyrics.Line>()

        if (blocks.first().start > SyncedLyrics.MIN_OFFSET_TIME) {
            syncedLines.add(
                SyncedLyrics.Line(
                    start = 0,
                    end = blocks.first().start,
                    content = SyncedLyrics.EmptyContent,
                    transliteration = null,
                    translation = null,
                    actor = null
                )
            )
        }

        for (block in blocks) {
            syncedLines.add(
                SyncedLyrics.Line(
                    start = block.start,
                    end = block.end,
                    content = SyncedLyrics.TextContent(
                        content = block.text,
                        backgroundContent = null,
                        rawContent = block.text,
                        syllables = emptyList()
                    ),
                    transliteration = null,
                    translation = null,
                    actor = null
                )
            )
        }

        return SyncedLyrics(lines = syncedLines, offset = userOffset)
    }

    /**
     * Attaches [translation] lines to [original] lines as translations. Each original line
     * takes the translated line whose time range overlaps it the most.
     */
    fun mergeTranslation(original: SyncedLyrics, translation: SyncedLyrics): SyncedLyrics {
        val translatedLines = translation.lines.filterNot { it.isEmpty }
        if (translatedLines.isEmpty()) return original

        val mergedLines = original.lines.map { line ->
            if (line.isEmpty) return@map line
            val match = translatedLines
                .map { it to (minOf(line.end, it.end) - maxOf(line.start, it.start)) }
                .filter { (_, overlap) -> overlap > 0 }
                .maxByOrNull { (_, overlap) -> overlap }
                ?.first
            if (match == null || match.content.content == line.content.content) {
                line
            } else {
                line.copy(translation = match.content)
            }
        }
        return original.copy(lines = mergedLines)
    }

    // groupOffset: 1-based index of the first capture group (hours) for this timestamp
    private fun parseTime(match: MatchResult, groupOffset: Int): Long {
        return try {
            val h = match.groupValues[groupOffset].ifEmpty { "0" }.toLong()
            val m = match.groupValues[groupOffset + 1].toLong()
            val s = match.groupValues[groupOffset + 2].toLong()
            // Fraction digits: "5" means 500ms, "05" means 50ms
            val ms = match.groupValues[groupOffset + 3].padEnd(3, '0').toLong()
            h * 3_600_000L + m * 60_000L + s * 1_000L + ms
        } catch (_: Exception) {
            -1L
        }
    }

    /** Trims whitespace plus BOM / zero-width characters that `trim()` keeps. */
    private fun String.clean() = trim { it.isWhitespace() || it in INVISIBLE_CHARS }

    private fun String.stripHtmlTags() = replace(HTML_TAG_PATTERN, "").replace(ASS_TAG_PATTERN, "")

    companion object {
        private const val TAG = "SrtLyricsParser"

        private const val INVISIBLE_CHARS = "﻿​‌‍⁠"

        private val SEQUENCE_PATTERN = Regex("\\d+")
        private val HTML_TAG_PATTERN = Regex("<[^>]+>")
        private val ASS_TAG_PATTERN = Regex("\\{\\\\[^\\}]*\\}")

        // [H]H:MM:SS,mmm --> [H]H:MM:SS,mmm (hours optional, ',' or '.' before 1-3 fraction digits;
        // trailing cue settings such as "X1:… Y1:…" are allowed)
        private const val TIME = "(?:(\\d+):)?(\\d{1,2}):(\\d{1,2})[,.](\\d{1,3})"
        private val TIMESTAMP_PATTERN = Regex("^$TIME\\s*-->\\s*$TIME")
    }
}
