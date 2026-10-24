package com.parachord.shared.resolver

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * #404 — `normalizeStr` must be Unicode-aware and byte-aligned with desktop's
 * (`sync-providers/confidence-scoring.js`, parachord#943). Lives in commonTest so
 * it runs on BOTH the JVM (Android) and Kotlin/Native (iOS simulator): the port
 * leans on `\p{L}` / `\p{N}` / `\p{M}` regex classes and a per-platform NFKD,
 * and only running on both proves they agree.
 *
 * The first block is desktop's own test vectors (tests/resolver/confidence-scoring.test.js),
 * so both clients check the same outputs.
 */
class NormalizeStrTest {

    // ── desktop's vectors ──────────────────────────────────────────────

    @Test fun japaneseIsPreserved() = assertEquals("動物寓意譚", normalizeStr("動物寓意譚"))
    @Test fun cyrillicIsPreservedAndLowercasedWithSpacesStripped() = assertEquals("влунномсиянии", normalizeStr("В лунном сиянии"))
    @Test fun umlautFoldsToBaseLetter() = assertEquals("osterreich", normalizeStr("österreich"))
    @Test fun digitsAreKept() = assertEquals("99luftballons", normalizeStr("99 Luftballons"))
    @Test fun nullIsEmpty() = assertEquals("", normalizeStr(null))
    @Test fun emptyIsEmpty() = assertEquals("", normalizeStr(""))

    // ── behaviour the fix is for ───────────────────────────────────────

    @Test fun accentedLatinFoldsInsteadOfLosingLetters() {
        // Pre-#404: "beyonc" / "bjrk" — the accented letter was deleted.
        assertEquals("beyonce", normalizeStr("Beyoncé"))
        assertEquals("bjork", normalizeStr("Björk"))
        assertEquals("sigurros", normalizeStr("Sigur Rós"))
    }

    @Test fun asciiOutputIsUnchanged() {
        // The common case must not move, or every cached comparison shifts.
        assertEquals("comeasyouarefeatdavegrohlremaster", normalizeStr("Come As You Are (feat. Dave Grohl) [Remaster]"))
        assertEquals("helloworld", normalizeStr("Hello, World!"))
    }

    @Test fun arabicIsPreserved() = assertEquals("فيروز", normalizeStr("فيروز"))

    @Test fun hangulDecomposesToJamo_exactlyLikeDesktop() {
        // NFKD splits each Hangul syllable into its conjoining jamo (방 → ᄇ ᅡ ᆼ).
        // Jamo are \p{L}, not marks, so they're KEPT: the result renders the same
        // as the input but is 14 code points, not 5. Desktop's normalize('NFKD')
        // does exactly this, so it's byte-alignment, not a bug — and matching
        // still works because both sides of a comparison are normalized the same
        // way. Expected code points computed independently (Python unicodedata).
        val expected = "방탄소년단"
        assertEquals(expected, normalizeStr("방탄소년단"))
        assertEquals(0.95, scoreConfidence("Dynamite", "방탄소년단", "Dynamite", "방탄소년단"))
    }

    @Test fun otherNumberCategoryIsKept() {
        // \p{No}: desktop's \p{N} keeps it. Guards the Kotlin/Native workaround —
        // N is spelled Nd+Nl+No there because Native rejects \p{N}; dropping the
        // No arm would silently delete this character on both platforms.
        assertEquals("௰", normalizeStr("௰")) // TAMIL NUMBER TEN (No, no decomposition)
    }

    @Test fun nfkdFoldsCompatibilityForms() {
        // Fullwidth Latin (common in Japanese catalog metadata) folds to ASCII,
        // matching what desktop's .normalize('NFKD') produces.
        assertEquals("abc123", normalizeStr("ＡＢＣ１２３"))
    }

    // ── the end-to-end consequence: non-Latin tracks now pass the floor ──

    @Test fun cyrillicTrackMatchingOnBothAxesScoresPointNineFive() {
        // Pre-#404 this was 0.50 (both sides normalized to "") — below the 0.60
        // floor, so the source was dropped and the track couldn't resolve by search.
        val score = scoreConfidence(
            targetTitle = "В лунном сиянии",
            targetArtist = "Кино",
            matchedTitle = "В лунном сиянии",
            matchedArtist = "Кино",
        )
        assertEquals(0.95, score)
    }

    @Test fun differentNonLatinTitleStillScoresPointFive() {
        // Unicode-aware must not mean "everything non-Latin matches": a different
        // Cyrillic title by the same artist is still a wrong-song candidate.
        val score = scoreConfidence(
            targetTitle = "Группа крови",
            targetArtist = "Кино",
            matchedTitle = "Звезда по имени Солнце",
            matchedArtist = "Кино",
        )
        assertEquals(0.50, score)
    }

    @Test fun accentDifferencesNoLongerBlockAMatch() {
        // A catalog that drops the accent now matches the accented target.
        assertEquals(0.95, scoreConfidence("Hyperballad", "Björk", "Hyperballad", "Bjork"))
    }

    @Test fun selectBestMatchFindsANonLatinCandidate() {
        data class R(val title: String, val artist: String)
        val results = listOf(R("Something Else", "Other"), R("動物寓意譚", "坂本龍一"))

        val best = selectBestMatch(results, "動物寓意譚", "坂本龍一", { it.title }, { it.artist })

        assertNotNull(best)
        assertEquals("動物寓意譚", best.title)
    }

    @Test fun selectBestMatchStillReturnsNullForPunctuationOnlyInput() {
        // Input that normalizes to "" must still be rejected, not match everything.
        data class R(val title: String, val artist: String)
        assertNull(selectBestMatch(listOf(R("a", "b")), "!!!", "???", { it.title }, { it.artist }))
    }
}
