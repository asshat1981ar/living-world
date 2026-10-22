package org.livingworld.core.dialogue

/**
 * Conservative G3 check for free text (for example a model's reply).
 *
 * Every word of a line has to be known: it appears in the speakable facts, in the speaker's name or the place
 * ([extraAllowed]), or it is everyday small talk ([SMALL_TALK]). The old rule only looked at names, ids and numbers,
 * so an invented sentence in lower case ("alden poisoned the well") passed it; now it does not. A rejected line costs
 * the built-in kernel line, never a wrong fact, so the policy can be strict.
 */
object GroundingCheck {
    private val word = Regex("[A-Za-z0-9_]+")
    private val sentenceBreak = Regex("(?<=[.!?])\\s+")

    fun isGrounded(line: String, facts: List<SpeakableFact>, extraAllowed: Collection<String> = emptyList()): Boolean {
        val allowed = mutableSetOf<String>()
        facts.forEach { f -> word.findAll(f.line).forEach { allowed += it.value.lowercase() } }
        extraAllowed.forEach { e -> word.findAll(e).forEach { allowed += it.value.lowercase() } }
        return word.findAll(line).all { m ->
            val token = m.value.lowercase()
            token in allowed || token in SMALL_TALK
        }
    }

    /**
     * The sentences of [line] that are grounded, joined with single spaces, or null if none is. Lets a reply with one
     * invented sentence keep the rest instead of being discarded whole; every kept sentence passes [isGrounded].
     */
    fun groundedPart(line: String, facts: List<SpeakableFact>, extraAllowed: Collection<String> = emptyList()): String? =
        line.trim().split(sentenceBreak)
            .filter { it.isNotBlank() && isGrounded(it, facts, extraAllowed) }
            .joinToString(" ")
            .ifBlank { null }

    /**
     * Everyday words an NPC may use without the facts saying them: function words, greetings, period speech and the
     * small talk of a village (the hour, the weather, the road). Anything a fact would have to carry - a name, a
     * place, an item, a number, an act - is not here, so it has to come from the facts.
     */
    private val SMALL_TALK: Set<String> = linkedSetOf(
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
        "very", "best", "better", "times", "word", "rumor", "rumour", "news", "careful", "quiet", "hush",
        // Period speech the model reaches for in this setting.
        "verily", "forsooth", "hark", "lo", "behold", "prithee", "mayhap", "methinks", "ye", "thou", "thee", "thy",
        "thine", "tis", "twas", "nigh", "bah", "heh", "fie", "woe", "alack", "aught", "anon", "hence", "whence",
        "wherefore", "begone", "hold", "peace", "stranger", "traveler", "traveller", "friend", "sir", "madam",
        // The small talk of a village: the hour, the weather, the road, how one is doing.
        "met", "meets", "brings", "brought", "cold", "warm", "hot", "cool", "elsewhere", "night", "nights",
        "morning", "morning", "evening", "dawn", "dusk", "day", "days", "hour", "hours", "weather", "wind",
        "rain", "road", "roads", "voice", "down", "up", "light", "dark", "tired", "hungry", "thirsty", "busy",
        "late", "soon", "safe", "wet", "dry", "ill", "sleep", "rest", "water", "fire", "work", "works",
        "hurry", "wait", "walks", "walked", "rested", "slept", "woke", "again", "soon", "away", "back",
        // Knowing and not knowing, and the simple goods of a village shop.
        "know", "knows", "knew", "unknown", "herbs", "herb", "nothing", "someone"
    )
}
