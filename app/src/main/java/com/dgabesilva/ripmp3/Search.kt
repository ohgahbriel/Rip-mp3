package com.dgabesilva.ripmp3

import java.text.Normalizer
import java.util.Locale

/**
 * The one text matcher every search box in the app runs through — the player's
 * queue filter and the browser's library search both call [matches] so search
 * behaves identically everywhere.
 *
 * Two things it does that a plain `contains` doesn't:
 *  - accent-insensitive: "sao" matches "São", "beyonce" matches "Beyoncé". We
 *    fold the combining marks off the NFD-decomposed string instead of keeping
 *    a hand-list of characters, so every diacritic is handled uniformly.
 *  - multi-token AND: "metallica pup" matches "Master of Puppets" by Metallica
 *    because each whitespace-separated token only has to appear *somewhere* in
 *    the combined fields, in any order — that's how people actually type a
 *    half-remembered "artist + a word from the title" query.
 */
object Search {

    private val COMBINING = Regex("\\p{Mn}+")
    private val SPLIT = Regex("\\s+")

    /** Lowercased, accent-stripped form used on both sides of a match. */
    fun normalize(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD)
            .replace(COMBINING, "")
            .lowercase(Locale.ROOT)

    /** True when every token in [query] appears (accent-insensitively) across [fields]. Blank query matches all. */
    fun matches(query: String, vararg fields: String): Boolean {
        val q = query.trim()
        if (q.isEmpty()) return true
        val hay = fields.joinToString(" ") { normalize(it) }
        return normalize(q).split(SPLIT).all { it.isEmpty() || hay.contains(it) }
    }
}
