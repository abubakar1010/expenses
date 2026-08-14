package com.app.finance.core.text

import java.text.Normalizer
import java.util.Locale

/**
 * Normalises a user-typed name into the key that uniqueness is enforced on.
 *
 * 03-database-design.md §4.2: this is computed by the application on write,
 * never in SQL, because SQLite's `LOWER()` is ASCII-only and would leave
 * `বাড়ি ভাড়া` untouched — silently permitting duplicate Bengali source and
 * category names past a unique index that believes it is doing its job.
 *
 * The unique indexes `ux_income_source_key` and `ux_category_parent_key` are
 * what make FR-IS-02 and FR-CAT-07 structurally impossible to violate, and they
 * are only as good as this function.
 */
object NameKey {

    /**
     * `"  Salary "`, `"salary"` and `"Salary"` all collapse to `"salary"`.
     *
     * Three steps, each load-bearing:
     *
     * 1. **Unicode NFC normalisation.** Bengali conjuncts and vowel signs have
     *    both composed and decomposed encodings that look identical on screen.
     *    Without this, two visually identical names produce different keys and
     *    the unique index lets both through.
     * 2. **Whitespace collapse**, across all Unicode space characters, then trim.
     * 3. **Case folding with [Locale.ROOT]** — deliberately *not* the device
     *    locale. Turkish lowercases `I` to `ı`, so a device-locale fold would
     *    make the same name key differently on a Turkish phone and silently
     *    break the constraint after a locale change or an export/import
     *    round-trip. The doc's requirement is Unicode-correct folding, which
     *    `Locale.ROOT` gives; locale-*sensitive* folding is what must be
     *    avoided. Bengali has no case, so the fold is a no-op there and the
     *    NFC step above is what does the real work.
     */
    fun of(raw: String): String =
        Normalizer.normalize(raw, Normalizer.Form.NFC)
            .replace(WHITESPACE, " ")
            .trim()
            .lowercase(Locale.ROOT)

    /** True when two names would collide on the unique index. */
    fun sameAs(a: String, b: String): Boolean = of(a) == of(b)

    /** Rejects names that are empty once normalised, such as `"   "`. */
    fun isBlank(raw: String): Boolean = of(raw).isEmpty()

    private val WHITESPACE = Regex("\\s+")
}
