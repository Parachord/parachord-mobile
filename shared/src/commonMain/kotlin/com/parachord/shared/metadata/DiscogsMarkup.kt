package com.parachord.shared.metadata

/**
 * Discogs profile markup. Artist profiles use their own markup: `[b]bold[/b]`,
 * `[i]…[/i]`, `[u]…[/u]`, `[url=https://…]text[/url]`, and references to
 * other entities either by name (`[a=Artist]`, `[l=Label]`, `[r=Release]`,
 * `[m=Master]`) or by id (`[a17632854]`). The old cleanup handled only the
 * by-name forms, so a band whose profile listed its members by id showed
 * "[a17632854] – vocals" in its bio.
 *
 * Port of desktop's `src/renderer/lib/discogs-markup.js` (parachord#1124).
 * Desktop keeps the formatting tags and renders them; mobile shows bios as
 * plain text, so [normalizeProfile] also strips them.
 */
object DiscogsMarkup {

    /** At most this many references are looked up per profile. */
    const val MAX_REF_LOOKUPS = 8

    /** The API path for a reference type. */
    val REF_PATH = mapOf('a' to "artists", 'l' to "labels", 'r' to "releases", 'm' to "masters")

    private val REF_ID = Regex("""\[([alrm])(\d+)\]""")
    private val REF_NAME = Regex("""\[([alrm])=([^\]]+)\]""")
    private val URL_TAG = Regex("""\[url=[^\]]*\](.*?)\[/url\]""", RegexOption.IGNORE_CASE)
    private val FORMAT_TAG = Regex("""\[/?(b|i|u|url)(=[^\]]*)?\]""", RegexOption.IGNORE_CASE)
    private val DISAMBIGUATION = Regex("""\s+\(\d+\)$""")

    data class Ref(val type: Char, val id: String) {
        /** The key a name map uses, e.g. `a17632854`. */
        val key: String get() = "$type$id"
    }

    /** Discogs disambiguates same-named artists with a " (2)" suffix. */
    fun stripDisambiguation(name: String): String = name.replace(DISAMBIGUATION, "").trim()

    /** Distinct id references in a profile, in order. */
    fun refIds(profile: String?): List<Ref> =
        REF_ID.findAll(profile.orEmpty())
            .map { Ref(it.groupValues[1].first(), it.groupValues[2]) }
            .distinct()
            .toList()

    /**
     * Resolve references to plain names ([names] maps [Ref.key] to a name) and
     * strip the formatting tags. A reference with no name is dropped rather
     * than shown as a bare id.
     */
    fun normalizeProfile(profile: String?, names: Map<String, String> = emptyMap()): String =
        profile.orEmpty()
            .replace(Regex("\r\n?"), "\n")
            .replace(REF_ID) { stripDisambiguation(names[it.groupValues[1] + it.groupValues[2]].orEmpty()) }
            .replace(REF_NAME) { stripDisambiguation(it.groupValues[2]) }
            .replace(URL_TAG) { it.groupValues[1] }
            .replace(FORMAT_TAG, "")
            .replace(Regex("[ \t]+\n"), "\n")
            .replace(Regex("\n[ \t]+"), "\n") // a dropped reference at a line start leaves a space
            .trim()
}
