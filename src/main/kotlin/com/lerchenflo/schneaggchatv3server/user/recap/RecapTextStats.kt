package com.lerchenflo.schneaggchatv3server.user.recap

import com.lerchenflo.schneaggchatv3server.user.recap.model.EmojiCount
import java.text.BreakIterator

/** Emoji statistics over the texts a user typed, for the recap's chat habits. */
internal object RecapTextStats {

    private const val TOP_EMOJI = 5

    /** The emoji used most in [texts], most first, at most 5. */
    fun topEmoji(texts: List<String>): List<EmojiCount> = texts
        .flatMap(::emojiIn)
        .groupingBy { it }.eachCount()
        .entries
        .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
        .take(TOP_EMOJI)
        .map { EmojiCount(it.key, it.value.toLong()) }

    /** Emoji as whole grapheme clusters, so skin tones, ZWJ sequences and flags stay one emoji. */
    private fun emojiIn(text: String): List<String> {
        // Every emoji starts at U+00A9 or above, plain text needs no cluster walk.
        if (text.none { it.code >= 0xA9 }) return emptyList()

        val result = mutableListOf<String>()
        val clusters = BreakIterator.getCharacterInstance().apply { setText(text) }
        var start = clusters.first()
        var end = clusters.next()
        while (end != BreakIterator.DONE) {
            val cluster = text.substring(start, end)
            val codePoint = cluster.codePointAt(0)
            if (Character.isExtendedPictographic(codePoint) || codePoint in 0x1F1E6..0x1F1FF) result += cluster
            start = end
            end = clusters.next()
        }
        return result
    }
}
