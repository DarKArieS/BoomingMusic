package com.mardous.booming.data.local.lyrics.srt

import com.mardous.booming.data.local.lyrics.LyricsInfo
import com.mardous.booming.data.local.lyrics.LyricsParser
import com.mardous.booming.data.model.lyrics.SyncedLyrics
import java.io.Reader

class SrtLyricsParser : LyricsParser {

    override fun getInfo(lyrics: String): LyricsInfo {
        // Skip blank lines and sequence number, then check for a timestamp line
        val isSrt = lyrics.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .dropWhile { it.matches(SEQUENCE_PATTERN) }
            .firstOrNull()
            ?.let { TIMESTAMP_PATTERN.containsMatchIn(it) } == true
        return if (isSrt) LyricsInfo.Valid(actuallySynced = true) else LyricsInfo.Invalid
    }

    override fun parseAsPlain(input: String): String? {
        val text = input.lineSequence()
            .map { it.trim() }
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
        data class Block(val start: Long, val end: Long, val lines: List<String>)

        val blocks = mutableListOf<Block>()
        try {
            reader.buffered().use { br ->
                val rawLines = br.readLines()
                var i = 0
                while (i < rawLines.size) {
                    val line = rawLines[i].trim()
                    i++

                    // Skip blank lines and sequence numbers
                    if (line.isEmpty() || line.matches(Regex("\\d+"))) continue

                    val timeMatch = TIMESTAMP_PATTERN.find(line) ?: continue
                    val start = parseTime(timeMatch, groupOffset = 1)
                    val end = parseTime(timeMatch, groupOffset = 5)
                    if (start < 0 || end < 0 || end <= start) continue

                    val textLines = mutableListOf<String>()
                    while (i < rawLines.size) {
                        val textLine = rawLines[i].trim()
                        i++
                        if (textLine.isEmpty()) break
                        // Skip if next sequence number
                        if (textLine.matches(Regex("\\d+")) &&
                            i < rawLines.size && TIMESTAMP_PATTERN.containsMatchIn(rawLines[i].trim())
                        ) break
                        textLines.add(textLine.stripHtmlTags())
                    }

                    if (textLines.isNotEmpty() || !ignoreBlankLines) {
                        blocks.add(Block(start, end, textLines))
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }

        if (blocks.isEmpty()) return null

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
            val text = block.lines.joinToString("\n")
            if (text.isBlank() && ignoreBlankLines) continue

            syncedLines.add(
                SyncedLyrics.Line(
                    start = block.start,
                    end = block.end,
                    content = SyncedLyrics.TextContent(
                        content = text,
                        backgroundContent = null,
                        rawContent = text,
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

    // groupOffset: 1-based index of the first capture group for this timestamp
    private fun parseTime(match: MatchResult, groupOffset: Int): Long {
        return try {
            val h = match.groupValues[groupOffset].toLong()
            val m = match.groupValues[groupOffset + 1].toLong()
            val s = match.groupValues[groupOffset + 2].toLong()
            val ms = match.groupValues[groupOffset + 3].toLong()
            h * 3_600_000L + m * 60_000L + s * 1_000L + ms
        } catch (_: Exception) {
            -1L
        }
    }

    private fun String.stripHtmlTags() = replace(Regex("<[^>]+>"), "")

    companion object {
        private val SEQUENCE_PATTERN = Regex("\\d+")

        // HH:MM:SS,mmm --> HH:MM:SS,mmm
        private val TIMESTAMP_PATTERN = Regex(
            "(\\d{2}):(\\d{2}):(\\d{2})[,\\.](\\d{3})\\s*-->\\s*(\\d{2}):(\\d{2}):(\\d{2})[,\\.](\\d{3})"
        )
    }
}
