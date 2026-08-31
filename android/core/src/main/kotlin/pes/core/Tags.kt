package pes.core

/**
 * Tag parsing and normalisation (spec §7).
 *
 * Shared by every path that can produce tags — the desktop answer form, the
 * Android answer form, and Android's inline notification reply — so the same
 * typed text yields the same stored tags whichever client and whichever route
 * the owner used. Mirrors `desktop/pes/core/tags.py`.
 *
 * Tags are **case-folded on ingest**. The notification reply box is drawn by
 * the system IME, which capitalises the first word and cannot be told not to,
 * so without folding the same tag enters `tag_vocab` as both `email` and
 * `Email`, splitting suggestion counts and splitting the exported data for
 * analysis (Tier 3 charter C1 F6).
 */
val TAG_RE = Regex("^[A-Za-z0-9_.\\-]{1,64}$")

fun normalizeTag(tag: String): String = tag.lowercase()

/** Whitespace-separated tags, normalised. Order and duplicates preserved. */
fun splitTags(text: String): List<String> =
    text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.map { normalizeTag(it) }

/** Tokens that are not valid tags, in input order (after normalisation). */
fun invalidTags(text: String): List<String> = splitTags(text).filter { !it.matches(TAG_RE) }
