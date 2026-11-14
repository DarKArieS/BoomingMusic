package com.mardous.booming.data.repository

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.util.Log
import android.util.LruCache
import com.mardous.booming.data.local.EditTarget
import com.mardous.booming.data.local.MetadataReader
import com.mardous.booming.data.local.MetadataWriter
import com.mardous.booming.data.local.lyrics.LyricsInfo
import com.mardous.booming.data.local.lyrics.LyricsLineLocator
import com.mardous.booming.data.local.lyrics.LyricsParser
import com.mardous.booming.data.local.lyrics.lrc.LrcLyricsParser
import com.mardous.booming.data.local.lyrics.srt.SrtLyricsParser
import com.mardous.booming.data.local.lyrics.ttml.TtmlLyricsParser
import com.mardous.booming.data.local.room.LyricsDao
import com.mardous.booming.data.model.Song
import com.mardous.booming.data.model.lyrics.LyricsFile
import com.mardous.booming.data.model.lyrics.LyricsLineEdit
import com.mardous.booming.data.model.lyrics.LyricsLineEditResult
import com.mardous.booming.data.model.lyrics.LyricsTextKind
import com.mardous.booming.data.model.lyrics.LyricsSource
import com.mardous.booming.data.model.lyrics.ParsedLyrics
import com.mardous.booming.data.model.lyrics.RawLyrics
import com.mardous.booming.data.remote.lyrics.LyricsDownloadService
import com.mardous.booming.data.remote.lyrics.LyricsProviderParams
import com.mardous.booming.extensions.hasR
import com.mardous.booming.extensions.media.isArtistNameUnknown
import com.mardous.booming.util.MARK_INSTRUMENTAL_TRACKS_BY_TITLE
import com.mardous.booming.util.Preferences.requireString
import org.mozilla.universalchardet.UniversalDetector
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.nio.charset.Charset
import java.util.regex.Pattern

interface LyricsRepository {
    suspend fun parseRawLyrics(song: Song, rawLyrics: RawLyrics): ParsedLyrics

    suspend fun fileLyrics(song: Song): RawLyrics.File?
    suspend fun embeddedLyrics(song: Song): RawLyrics.Embedded?
    suspend fun storedLyrics(song: Song, allowDownload: Boolean): RawLyrics.Stored?
    suspend fun downloadLyrics(song: Song, searchTitle: String, searchArtist: String, providerParams: LyricsProviderParams): RawLyrics.Remote?

    suspend fun setUserOffset(song: Song, offset: Long)
    suspend fun saveLyrics(
        song: Song,
        originalLyricsBySource: Map<LyricsSource, RawLyrics?>,
        newContentBySource: Map<LyricsSource, String>
    ): Boolean?

    suspend fun writableUris(song: Song): List<Uri>
    suspend fun deleteAllLyrics()

    /** The external file that holds the given text of the song's lyrics, if they come from one. */
    suspend fun lyricsFileForEdit(song: Song, kind: LyricsTextKind): File?

    /** Replaces one line's text in [file] and hands the new bytes to [write]. */
    suspend fun editLyricsFileLine(
        song: Song,
        file: File,
        edit: LyricsLineEdit,
        write: (ByteArray) -> Boolean
    ): LyricsLineEditResult
}

class RealLyricsRepository(
    private val context: Context,
    private val preferences: SharedPreferences,
    private val lyricsDownloadService: LyricsDownloadService,
    private val lyricsDao: LyricsDao
) : LyricsRepository {

    private val cachedRawLyrics = LruCache<Long, Map<LyricsSource, RawLyrics>>(20)
    private val cachedUserOffsets = LruCache<Long, Long>(1000)

    private val charsetDetector = UniversalDetector()

    private val lrcLyricsParser = LrcLyricsParser()
    private val ttmlLyricsParser = TtmlLyricsParser()
    private val srtLyricsParser = SrtLyricsParser()

    // SRT goes first: its "-->" timing lines are unambiguous, while the LRC parser would
    // otherwise accept SRT text containing "[...]" (e.g. "[Music]") as plain LRC lyrics.
    private val lyricsParsers = listOf(srtLyricsParser, lrcLyricsParser, ttmlLyricsParser)

    override suspend fun parseRawLyrics(song: Song, rawLyrics: RawLyrics): ParsedLyrics {
        val ignoreBlankLines = preferences.getBoolean(IGNORE_BLANK_LINES, false)
        val markInstrumentalByTitle = preferences.getBoolean(MARK_INSTRUMENTAL_TRACKS_BY_TITLE, false)
        try {
            return when (rawLyrics) {
                is RawLyrics.File -> {
                    val parsed = parseTextLyrics(
                        song = song,
                        lyrics = rawLyrics.lyrics,
                        ignoreBlankLines = ignoreBlankLines,
                        markInstrumentalByTitle = markInstrumentalByTitle,
                        lyricsParsers = listOf(
                            when (rawLyrics.file.format) {
                                LyricsFile.Format.LRC -> lrcLyricsParser
                                LyricsFile.Format.TTML -> ttmlLyricsParser
                                LyricsFile.Format.SRT -> srtLyricsParser
                            }
                        )
                    )
                    if (rawLyrics.file.format == LyricsFile.Format.SRT && parsed is ParsedLyrics.Synced) {
                        withSrtOriginal(song, rawLyrics.file, parsed, ignoreBlankLines)
                    } else {
                        parsed
                    }
                }

                is RawLyrics.Embedded -> {
                    parseTextLyrics(
                        song = song,
                        lyrics = rawLyrics.lyrics,
                        ignoreBlankLines = ignoreBlankLines,
                        markInstrumentalByTitle = markInstrumentalByTitle,
                    )
                }

                is RawLyrics.Stored -> {
                    if (rawLyrics.instrumental) {
                        ParsedLyrics.Instrumental
                    } else {
                        parseTextLyrics(
                            song = song,
                            lyrics = rawLyrics.lyrics,
                            ignoreBlankLines = ignoreBlankLines,
                            markInstrumentalByTitle = markInstrumentalByTitle,
                            provider = rawLyrics.provider,
                            userOffset = rawLyrics.userOffset
                        )
                    }
                }

                is RawLyrics.Edited -> {
                    if (rawLyrics.instrumental) {
                        ParsedLyrics.Instrumental
                    } else {
                        parseTextLyrics(
                            song = song,
                            lyrics = rawLyrics.lyrics,
                            ignoreBlankLines = ignoreBlankLines,
                            markInstrumentalByTitle = markInstrumentalByTitle,
                            provider = rawLyrics.newContentProvider
                        )
                    }
                }

                is RawLyrics.Remote -> {
                    parseTextLyrics(
                        song = song,
                        lyrics = rawLyrics.lyrics,
                        ignoreBlankLines = ignoreBlankLines,
                        markInstrumentalByTitle = markInstrumentalByTitle
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Couldn't parse lyrics for song ${song.data}", e)
        }
        return ParsedLyrics.Empty
    }

    /**
     * If a file with the same name exists in a `rawSRT` folder next to the SRT file, that file
     * holds the original lyrics: it becomes the main content and the SRT file's lines are
     * shown as its translation.
     */
    private fun withSrtOriginal(
        song: Song,
        file: LyricsFile,
        translated: ParsedLyrics.Synced,
        ignoreBlankLines: Boolean
    ): ParsedLyrics {
        val originalFile = findSrtOriginal(File(file.path)) ?: return translated

        val originalText = runCatching {
            originalFile.inputStream().buffered().use { stream ->
                val charset = detectEncoding(stream)
                stream.reader(charset).use { it.readText() }
            }
        }.getOrNull()
        if (originalText.isNullOrBlank()) return translated

        val original = srtLyricsParser.parse(
            originalText,
            song.duration,
            translated.lyrics.offset,
            ignoreBlankLines
        ) ?: return translated

        return ParsedLyrics.Synced(srtLyricsParser.mergeTranslation(original, translated.lyrics))
    }

    /** The same-named file in a `rawSRT` folder next to [srtFile] (names compared ignoring case). */
    private fun findSrtOriginal(srtFile: File): File? {
        return srtFile.parentFile
            ?.listFiles { dir -> dir.isDirectory && dir.name.equals("rawSRT", ignoreCase = true) }
            ?.firstNotNullOfOrNull { dir ->
                dir.listFiles { f -> f.isFile && f.name.equals(srtFile.name, ignoreCase = true) }?.firstOrNull()
            }
    }

    override suspend fun lyricsFileForEdit(song: Song, kind: LyricsTextKind): File? {
        val lyricsFile = fileLyrics(song)?.file ?: return null
        val file = File(lyricsFile.path)
        // With a rawSRT original, the main text lives there and the outer SRT holds the translation
        if (lyricsFile.format == LyricsFile.Format.SRT && kind != LyricsTextKind.Translation) {
            findSrtOriginal(file)?.let { return it }
        }
        return file
    }

    override suspend fun editLyricsFileLine(
        song: Song,
        file: File,
        edit: LyricsLineEdit,
        write: (ByteArray) -> Boolean
    ): LyricsLineEditResult {
        if (edit.newText == edit.oldText) return LyricsLineEditResult.NoChanges
        return try {
            val format = LyricsFile.Format.entries.firstOrNull { it.value == file.extension.lowercase() }
                ?: return LyricsLineEditResult.Failed
            val bytes = file.readBytes()
            val charset = bytes.inputStream().buffered().use { detectEncoding(it) }
            val content = String(bytes, charset)

            val newContent = LyricsLineLocator.replace(
                content = content,
                format = format,
                lineStart = edit.lineStart,
                oldText = edit.oldText,
                newText = edit.newText
            ) ?: return LyricsLineEditResult.NotFound
            if (newContent == content) return LyricsLineEditResult.NoChanges

            if (write(newContent.toByteArray(charset))) {
                removeCachedLyrics(LyricsSource.File, song.id)
                LyricsLineEditResult.Success
            } else {
                LyricsLineEditResult.Failed
            }
        } catch (e: Exception) {
            Log.e(TAG, "Couldn't edit lyrics file ${file.path}", e)
            LyricsLineEditResult.Failed
        }
    }

    private suspend fun getUserOffset(songId: Long): Long? {
        val cachedOffset = cachedUserOffsets[songId]
        if (cachedOffset == null) {
            val offset = lyricsDao.getOffset(songId) ?: 0
            synchronized(cachedUserOffsets) {
                cachedUserOffsets.put(songId, offset)
            }
            return offset
        } else {
            return cachedOffset
        }
    }

    private suspend fun parseTextLyrics(
        song: Song,
        lyrics: String?,
        ignoreBlankLines: Boolean,
        markInstrumentalByTitle: Boolean,
        lyricsParsers: List<LyricsParser> = this.lyricsParsers,
        provider: String? = null,
        userOffset: Long? = null
    ): ParsedLyrics {
        if (lyrics.isNullOrBlank()) return ParsedLyrics.Empty

        for (parser in lyricsParsers) {
            return when (val info = parser.getInfo(lyrics)) {
                is LyricsInfo.Instrumental -> ParsedLyrics.Instrumental
                is LyricsInfo.Valid -> {
                    if (info.actuallySynced) {
                        val customOffset = userOffset ?: getUserOffset(song.id) ?: 0
                        val synced = parser.parse(lyrics, song.duration, customOffset, ignoreBlankLines)
                            ?.let { if (provider != null) it.copy(provider = provider) else it }
                        if (synced != null) ParsedLyrics.Synced(synced) else ParsedLyrics.Empty
                    } else {
                        val plain = parser.parseAsPlain(lyrics)
                        if (plain != null) ParsedLyrics.Plain(plain) else ParsedLyrics.Empty
                    }
                }
                is LyricsInfo.Invalid -> continue
            }
        }
        return if (markInstrumentalByTitle && INSTRUMENTAL_REGEX.matches(song.title)) {
            ParsedLyrics.Instrumental
        } else {
            ParsedLyrics.Plain(lyrics)
        }
    }

    override suspend fun fileLyrics(song: Song): RawLyrics.File? {
        getCachedLyrics<RawLyrics.File>(LyricsSource.File, song.id)?.let { return it }
        try {
            val preferredFormatValue =
                preferences.requireString("preferred_lyrics_file_format", "ttml")
            val preferredFormat =
                LyricsFile.Format.entries.firstOrNull { it.value == preferredFormatValue }

            val rawLyricsList = mutableListOf<RawLyrics.File>()
            for (file in findLyricsFiles(song)) {
                val actualFile = File(file.path)
                val lyrics = runCatching {
                    actualFile.inputStream().buffered().use { stream ->
                        val charset = detectEncoding(stream)
                        stream.reader(charset).use { it.readText() }
                    }
                }.getOrNull() ?: continue

                if (lyrics.isNotEmpty()) {
                    val rawLyrics = RawLyrics.File(file, lyrics)
                    if (file.format == preferredFormat) {
                        return cacheLyrics(song.id, rawLyrics)
                    }
                    rawLyricsList.add(rawLyrics)
                }
            }

            return rawLyricsList.firstOrNull()?.let {
                cacheLyrics(song.id, it)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Couldn't find/read lyrics files for song ${song.data}", e)
        }
        return null
    }

    override suspend fun embeddedLyrics(song: Song): RawLyrics.Embedded? {
        if (song.id != Song.emptySong.id) {
            getCachedLyrics<RawLyrics.Embedded>(LyricsSource.Embedded, song.id)?.let { return it }
            try {
                val metadataReader = MetadataReader(song.uri)
                var lyrics = metadataReader.value(MetadataReader.LYRICS)
                if (lyrics.isNullOrEmpty()) {
                    lyrics = metadataReader.value("UNSYNCEDLYRICS")
                }
                return cacheLyrics(song.id, RawLyrics.Embedded(lyrics))
            } catch (e: Exception) {
                Log.e(TAG, "Couldn't read embedded lyrics for song ${song.data}", e)
            }
        }
        return null
    }

    override suspend fun storedLyrics(song: Song, allowDownload: Boolean): RawLyrics.Stored? {
        if (song.id != Song.emptySong.id) try {
            getCachedLyrics<RawLyrics.Stored>(LyricsSource.Downloaded, song.id)?.let { return it }
            val storedLyrics = lyricsDao.getLyrics(song.id)
            if (storedLyrics == null && allowDownload) {
                val storableLyrics = lyricsDownloadService.remoteLyrics(song).prepareToStore()
                if (storableLyrics != null) {
                    lyricsDao.upsertDownloadedLyrics(
                        songId = song.id,
                        lyrics = storableLyrics.lyrics,
                        provider = storableLyrics.provider,
                        instrumental = storableLyrics.instrumental
                    )
                    return cacheLyrics(song.id, storableLyrics)
                }
            } else if (storedLyrics != null) {
                return cacheLyrics(song.id, RawLyrics.Stored(
                    lyrics = storedLyrics.lyrics,
                    provider = storedLyrics.provider,
                    instrumental = storedLyrics.instrumental,
                    userOffset = storedLyrics.offset
                ))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Couldn't fetch/download lyrics for song ${song.data}", e)
        }
        return null
    }

    override suspend fun downloadLyrics(
        song: Song,
        searchTitle: String,
        searchArtist: String,
        providerParams: LyricsProviderParams
    ): RawLyrics.Remote? {
        if (song.id == Song.emptySong.id || searchArtist.isArtistNameUnknown()) {
            return null
        }
        return try {
            lyricsDownloadService.remoteLyrics(song, searchTitle, searchArtist, providerParams)
        } catch (_: Exception) {
            null
        }
    }

    override suspend fun setUserOffset(song: Song, offset: Long) {
        if (song.id == Song.emptySong.id) return
        lyricsDao.upsertOffset(song.id, offset)
        synchronized(cachedUserOffsets) {
            cachedUserOffsets.put(song.id, offset)
        }
    }

    override suspend fun saveLyrics(
        song: Song,
        originalLyricsBySource: Map<LyricsSource, RawLyrics?>,
        newContentBySource: Map<LyricsSource, String>
    ): Boolean? {
        try {
            val editedLyrics = newContentBySource.mapNotNull { (source, content) ->
                if (source == LyricsSource.File) return@mapNotNull null

                val originalLyrics = originalLyricsBySource[source] ?: when (source) {
                    LyricsSource.Embedded -> RawLyrics.Embedded(null)
                    LyricsSource.Downloaded -> RawLyrics.Stored()
                }
                if (originalLyrics.lyrics != content) {
                    RawLyrics.Edited(
                        originalLyrics = originalLyrics,
                        newContent = content
                    )
                } else null
            }

            if (editedLyrics.isEmpty()) {
                return null
            }

            return editedLyrics.all {
                when (it.originalLyrics) {
                    is RawLyrics.Embedded -> {
                        runCatching {
                            val metadataWriter = MetadataWriter()
                            metadataWriter.propertyMap(hashMapOf(MetadataReader.LYRICS to it.newContent))
                            metadataWriter.write(this.context, EditTarget.song(song)).isSuccess
                        }.getOrDefault(false).also { success ->
                            if (success) removeCachedLyrics(LyricsSource.Embedded, song.id)
                        }
                    }

                    is RawLyrics.Stored -> {
                        runCatching {
                            lyricsDao.upsertDownloadedLyrics(
                                songId = song.id,
                                lyrics = it.newContent,
                                provider = it.newContentProvider,
                                instrumental = it.instrumental
                            )
                            true
                        }.getOrDefault(false).also { success ->
                            if (success) removeCachedLyrics(LyricsSource.Downloaded, song.id)
                        }
                    }

                    else -> false
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error while saving lyrics for song ${song.data}", e)
        }
        return false
    }

    override suspend fun writableUris(song: Song): List<Uri> {
        if (hasR()) {
            return listOf(song.uri).filterNot { it == Uri.EMPTY }
        }
        return emptyList()
    }

    override suspend fun deleteAllLyrics() {
        lyricsDao.removeLyrics()
        cachedRawLyrics.evictAll()
    }

    private inline fun <reified T : RawLyrics> getCachedLyrics(
        source: LyricsSource,
        songId: Long
    ): T? {
        val cachedLyrics = cachedRawLyrics[songId]
        if (cachedLyrics != null && cachedLyrics.containsKey(source)) {
            return cachedLyrics[source] as? T
        }
        return null
    }

    private fun <T : RawLyrics> cacheLyrics(songId: Long, lyrics: T): T {
        val source = when (lyrics) {
            is RawLyrics.Embedded -> LyricsSource.Embedded
            is RawLyrics.Stored -> LyricsSource.Downloaded
            is RawLyrics.File -> LyricsSource.File
            else -> null
        }
        if (source != null) {
            val cachedLyrics = cachedRawLyrics[songId]?.toMutableMap() ?: mutableMapOf()
            cachedLyrics[source] = lyrics
            cachedRawLyrics.put(songId, cachedLyrics)
        }
        return lyrics
    }

    private fun removeCachedLyrics(source: LyricsSource, songId: Long) {
        val cachedLyrics = cachedRawLyrics[songId]?.toMutableMap()
        if (cachedLyrics != null) {
            cachedLyrics.remove(source)
            cachedRawLyrics.put(songId, cachedLyrics)
        }
    }

    private fun findLyricsFiles(song: Song): List<LyricsFile> {
        val songFile = File(song.data)
        val parentDir = songFile.parentFile ?: return emptyList()

        val baseNames = listOf(
            songFile.nameWithoutExtension,
            "${song.artistName} - ${song.title}"
        ).filter { it.isNotBlank() }.map { Pattern.quote(it) }

        val patterns = baseNames.map { base ->
            Regex(".*$base.*\\.(lrc|ttml|srt)", RegexOption.IGNORE_CASE)
        }

        return parentDir.listFiles()
            ?.filter { file -> file.isFile && patterns.any { it.matches(file.name) } }
            ?.mapNotNull { file ->
                val extension = file.extension.lowercase()
                LyricsFile.Format.entries.firstOrNull { it.value == extension }?.let { format ->
                    LyricsFile(file.absolutePath, format)
                }
            }
            .orEmpty()
    }

    private fun detectEncoding(bis: BufferedInputStream): Charset {
        return if (preferences.getBoolean(FORCE_UTF_8_ENCODING, true)) {
            Charsets.UTF_8
        } else try {
            charsetDetector.reset()
            bis.mark(BUFFER_SIZE)

            val buf = ByteArray(BUFFER_SIZE)
            var nread: Int
            while ((bis.read(buf).also { nread = it }) > 0 && !charsetDetector.isDone) {
                charsetDetector.handleData(buf, 0, nread)
            }

            charsetDetector.dataEnd()
            charsetDetector.detectedCharset?.let {
                Charset.forName(it)
            } ?: Charsets.UTF_8
        } catch (e: IOException) {
            Log.e(TAG, "Couldn't detect lyrics file encoding", e)
            Charsets.UTF_8
        } finally {
            bis.reset()
            charsetDetector.reset()
        }
    }

    companion object {
        private const val TAG = "LyricsRepository"

        private val INSTRUMENTAL_REGEX = Regex("""(?i)[(\[\-]\s*(?:acoustic(?:\s+version)?|instr?|instrumental|karaoke|orchestral)(?:\s+version)?\s*[)\]]?\s*$""")

        private const val BUFFER_SIZE = 4096
        private const val FORCE_UTF_8_ENCODING = "force_utf8_encoding_for_lyrics"
        private const val IGNORE_BLANK_LINES = "ignore_blank_lines_in_lyrics"
    }
}