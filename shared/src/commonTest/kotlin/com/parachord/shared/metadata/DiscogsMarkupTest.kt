package com.parachord.shared.metadata

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Discogs profile markup (desktop parachord#1124): id references resolve to
 * names, unnamed ones are dropped, by-name references lose their " (2)"
 * suffix, and formatting tags are stripped for mobile's plain-text bios.
 */
class DiscogsMarkupTest {

    @Test
    fun idReferencesAreListedOnceInOrder() {
        val refs = DiscogsMarkup.refIds("[a17632854] – vocals\n[a42] – bass\n[a17632854] again [l99]")
        assertEquals(listOf("a17632854", "a42", "l99"), refs.map { it.key })
    }

    @Test
    fun idReferencesResolveToNamesAndUnnamedOnesAreDropped() {
        val profile = "Members:\n[a17632854] – vocals\n[a42] – bass"
        assertEquals(
            "Members:\nIryna Panchuk – vocals\n– bass",
            DiscogsMarkup.normalizeProfile(profile, mapOf("a17632854" to "Iryna Panchuk")),
        )
    }

    @Test
    fun disambiguationSuffixesAreStripped() {
        assertEquals(
            "Formed by John Smith and Jane Doe on Merge.",
            DiscogsMarkup.normalizeProfile("Formed by [a=John Smith (2)] and [a99] on [l=Merge].", mapOf("a99" to "Jane Doe (3)")),
        )
    }

    @Test
    fun formattingTagsAreStrippedAndLinksKeepTheirText() {
        assertEquals(
            "A bold claim, see their site.",
            DiscogsMarkup.normalizeProfile("A [b]bold[/b] [i]claim[/i], see [url=https://example.com]their site[/url]."),
        )
    }

    @Test
    fun windowsLineEndingsAndTrailingSpacesAreTidied() {
        assertEquals("One\nTwo", DiscogsMarkup.normalizeProfile("One  \r\nTwo\r\n"))
    }
}
