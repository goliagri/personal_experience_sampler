package pes.core

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Tag parsing and folding (spec §7). Mirrors
 * `desktop/tests/scenarios/test_store_unit.py::test_tags_are_case_folded_on_ingest`
 * — the two clients must turn the same typed text into the same stored tags.
 */
class TagsTest {
    @Test
    fun `tags are case folded on ingest`() {
        // The notification reply box is drawn by the system IME, which
        // capitalises the first word and cannot be told not to; folding is
        // what stops `email` and `Email` becoming two `tag_vocab` rows
        // depending on which answer path was used (Tier 3 charter C1 F6).
        assertEquals(listOf("email", "work.writing", "coding"), splitTags("Email Work.Writing  coding"))
        assertEquals("email", normalizeTag("EMAIL"))
        // Folding does not make an invalid tag valid, and order is preserved.
        assertEquals(listOf("bad!tag"), invalidTags("ok Bad!Tag also_fine"))
        assertEquals(emptyList(), splitTags(""))
        assertEquals(1, splitTags("Email email EMAIL").toSet().size)
    }
}
