package com.mohammadjafari.sortview.fs

/**
 * Pure scan-view helpers ported from desktop/main.py (upstream v1.10.3):
 *   parse_extension_filter  L1276
 *   sort_key_for_path       L1308 (natural_key L1304 via [Paths])
 * Session state only — the desktop never persists the filter text or the
 * sort mode in config.json (they live on the widgets, main.py L2260-2265);
 * only thumb_size is persisted (L2098).
 */
object Filters {

    /** Desktop sort combo items are exactly ["Name", "Type"] (main.py L2264). */
    enum class Mode { NAME, TYPE }

    /**
     * Port of parse_extension_filter (main.py L1276-1301): accepts
     * ".jpg,.png", "jpg png", "*.jpg;*.mp4"; "*" / ".*" / "*.*" disable
     * filtering (empty list). Tokens normalize to ".ext" — lowercased,
     * deduplicated, order preserved. The desktop quirk "*png" (star prefix
     * without a dot) becomes ".*png", which matches nothing, and is kept
     * verbatim for behavior parity.
     */
    fun parseExtensionFilter(text: String): List<String> {
        val result = mutableListOf<String>()
        for (raw in text.trim().lowercase().split(Regex("[,;\\s]+"))) {
            var token = raw.trim()
            if (token.isEmpty()) continue
            if (token == "*" || token == ".*" || token == "*.*") return emptyList()
            token = when {
                token.startsWith("*.") -> token.substring(1)
                token.startsWith("*") && token.contains('.') -> token.substring(token.lastIndexOf('.'))
                else -> token
            }
            if (token.startsWith(".") && token.contains('*')) token = token.replace("*", "")
            if (token.isEmpty() || token == ".") continue
            if (!token.startsWith(".")) token = ".$token"
            if (token !in result) result.add(token)
        }
        return result
    }

    /**
     * The suffix rule behind Python's Path.suffix, lowercased for the Type
     * sort: "a.b.c" -> ".c", ".profile" -> "", "name" -> "" (leading-dot
     * files have no suffix, like pathlib). Empty suffixes sort first, since
     * "" compares before ".jpg" in both Python tuples and Kotlin strings.
     */
    fun suffixOf(name: String): String = Paths.splitName(name).second.lowercase()

    /**
     * Desktop L2900-2902: Name mode = natural_key(name); Type mode =
     * (suffix.lower(), natural_key(name)) — suffix compares FIRST, then the
     * natural name (desktop L1309-1311). Note the Python key never mixes
     * int/str comparisons position-wise (re.split alternates kinds), so a
     * plain suffix string compare followed by [Paths.compareNatural] is an
     * exact behavioral match.
     */
    fun compare(a: String, b: String, mode: Mode): Int =
        if (mode == Mode.TYPE) {
            val bySuffix = suffixOf(a).compareTo(suffixOf(b))
            if (bySuffix != 0) bySuffix else Paths.compareNatural(a, b)
        } else {
            Paths.compareNatural(a, b)
        }
}
