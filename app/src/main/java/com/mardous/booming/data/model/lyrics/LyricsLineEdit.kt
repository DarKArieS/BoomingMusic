package com.mardous.booming.data.model.lyrics

/** Which text of a synced line was long-pressed. */
enum class LyricsTextKind { Main, Background, Transliteration, Translation }

/** An edit of one line's text, located in the lyrics file by [lineStart] and [oldText]. */
data class LyricsLineEdit(
    val lineStart: Long,
    val kind: LyricsTextKind,
    val oldText: String,
    val newText: String = oldText
)

enum class LyricsLineEditResult { Success, NoChanges, NotFound, Failed }
