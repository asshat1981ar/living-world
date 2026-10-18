package org.livingworld.core.dialogue

/**
 * Conservative G3 check for free text (for example a model's reply). Names, ids and numbers are the
 * checkable claims: a line is grounded only if every such token also appears in the speakable facts or in
 * [extraAllowed] (the speaker's own name, the place, words the player used). Lower-case invented claims
 * with no names in them are NOT caught; this is a floor, not a proof, so callers should still prefer
 * kernel-rendered lines where they can.
 */
object GroundingCheck {
    private val word = Regex("[A-Za-z0-9_]+")
    private val sentenceEnders = setOf('.', '!', '?', '"', '“')

    fun isGrounded(line: String, facts: List<SpeakableFact>, extraAllowed: Collection<String> = emptyList()): Boolean {
        val allowed = mutableSetOf<String>()
        facts.forEach { f -> word.findAll(f.line).forEach { allowed += it.value.lowercase() } }
        extraAllowed.forEach { e -> word.findAll(e).forEach { allowed += it.value.lowercase() } }
        return claimTokens(line).all { it.lowercase() in allowed }
    }

    internal fun claimTokens(line: String): List<String> =
        word.findAll(line).filter { m ->
            val t = m.value
            t.any { it.isDigit() || it == '_' } || (t[0].isUpperCase() && t != "I" && !startsSentence(line, m.range.first))
        }.map { it.value }.toList()

    private fun startsSentence(line: String, index: Int): Boolean {
        var i = index - 1
        while (i >= 0 && line[i].isWhitespace()) i--
        return i < 0 || line[i] in sentenceEnders
    }
}
