package org.livingworld.core.dialogue

/**
 * Conservative G3 check for free text (for example a model's reply). Names, ids and numbers are the
 * checkable claims: a line is grounded only if every such token also appears in the speakable facts or in
 * [extraAllowed] (for example the speaker's own name and the place). A capitalised word that opens a sentence
 * is a claim too, unless it is an everyday opener ([COMMON_OPENERS]); an unknown opener only costs a fallback
 * to the kernel line. Lower-case invented claims with no names in them are NOT caught; this is a floor, not a
 * proof, so callers should still prefer kernel-rendered lines where they can.
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
            t.any { it.isDigit() || it == '_' } ||
                (t[0].isUpperCase() && t != "I" && !(startsSentence(line, m.range.first) && t.lowercase() in COMMON_OPENERS))
        }.map { it.value }.toList()

    private fun startsSentence(line: String, index: Int): Boolean {
        var i = index - 1
        while (i >= 0 && line[i].isWhitespace()) i--
        return i < 0 || line[i] in sentenceEnders
    }

    /** Words that may open a sentence without naming anyone: function words, greetings and period interjections. */
    private val COMMON_OPENERS: Set<String> = linkedSetOf(
        "a", "an", "the", "this", "that", "these", "those", "there", "here", "it", "its", "i", "me", "my", "mine",
        "you", "your", "yours", "we", "our", "us", "they", "their", "them", "he", "she", "his", "her", "him",
        "what", "who", "whom", "whose", "why", "how", "when", "where", "which", "whether",
        "and", "but", "or", "so", "yet", "if", "then", "now", "still", "though", "although", "because", "since",
        "once", "only", "even", "also", "too", "not", "no", "nor", "none", "nothing", "naught", "nobody", "some",
        "someone", "something", "many", "few", "all", "every", "each", "any", "anyone", "more", "most", "much",
        "is", "are", "was", "were", "be", "been", "am", "do", "does", "did", "have", "has", "had", "can", "could",
        "would", "should", "will", "shall", "may", "might", "must", "let", "ask", "go", "come", "tell", "look",
        "listen", "beware", "mind", "take", "leave", "keep", "stay", "forgive", "pray", "trust", "seek",
        "yes", "aye", "nay", "well", "ah", "oh", "hmm", "alas", "hail", "greetings", "welcome", "farewell",
        "good", "sorry", "perhaps", "maybe", "indeed", "truly", "certainly", "surely", "please", "thank", "thanks",
        "very", "best", "better", "times", "word", "rumor", "rumour", "news", "careful", "quiet", "hush"
    )
}
