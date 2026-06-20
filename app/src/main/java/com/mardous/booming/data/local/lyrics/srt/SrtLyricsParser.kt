package com.mardous.booming.data.local.lyrics.srt

import com.mardous.booming.data.LyricsParser
import com.mardous.booming.data.model.lyrics.LyricsFile
import com.mardous.booming.data.model.lyrics.SyncedLyrics
import java.io.Reader

class SrtLyricsParser : LyricsParser {

    override fun handles(file: LyricsFile) = file.format == LyricsFile.Format.SRT

    override fun handles(reader: Reader): Boolean {
        return reader.buffered().use { br ->
            // Skip blank lines and sequence number, then check for a timestamp line
            br.lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .dropWhile { it.matches(Regex("\\d+")) }
                .firstOrNull()
                ?.let { TIMESTAMP_PATTERN.containsMatchIn(it) } == true
        }
    }

    override fun parse(reader: Reader, trackLength: Long, ignoreBlankLines: Boolean): SyncedLyrics? {
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
                        words = emptyList()
                    ),
                    translation = null,
                    actor = null
                )
            )
        }

        return SyncedLyrics(lines = syncedLines)
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
        // HH:MM:SS,mmm --> HH:MM:SS,mmm
        private val TIMESTAMP_PATTERN = Regex(
            "(\\d{2}):(\\d{2}):(\\d{2})[,\\.](\\d{3})\\s*-->\\s*(\\d{2}):(\\d{2}):(\\d{2})[,\\.](\\d{3})"
        )
    }
}
