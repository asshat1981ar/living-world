package org.livingworld.core.dialogue

import org.livingworld.core.beliefs.Belief
import org.livingworld.core.beliefs.KnowledgeSource
import org.livingworld.core.npc.Npc

/** One thing an NPC may truthfully say: the belief behind it and a template sentence worded by its source. */
data class SpeakableFact(val belief: Belief, val line: String)

/**
 * G3: dialogue facts come from explicit NPC beliefs. This is the only place the kernel decides what an NPC
 * may assert; AI or UI text is checked against it ([GroundingCheck]) or built from it.
 */
object DialogueGrounding {
    const val CONFIDENCE_FLOOR = 0.4f
    const val NOTHING_TO_TELL = "I have nothing to tell you."

    fun speakableFacts(npc: Npc): List<SpeakableFact> = speakableFacts(npc.beliefs.all())

    fun speakableFacts(beliefs: Collection<Belief>): List<SpeakableFact> =
        beliefs.filter { it.confidence >= CONFIDENCE_FLOOR }
            .sortedWith(compareByDescending<Belief> { it.confidence * it.reliability }.thenBy { it.proposition.describe() })
            .map { SpeakableFact(it, render(it)) }

    fun render(belief: Belief): String {
        val body = belief.proposition.describe()
        return when (val source = belief.source) {
            is KnowledgeSource.DirectObservation, is KnowledgeSource.Observation ->
                if (belief.confidence >= 0.9f) "I saw it myself: $body." else "I think I saw this: $body."
            is KnowledgeSource.Hearsay ->
                if (belief.confidence >= 0.6f) "${source.sourceNpc.value} told me that $body."
                else "${source.sourceNpc.value} told me, though I doubt it, that $body."
            is KnowledgeSource.FactionReport -> "The ${source.faction.value} says that $body."
            is KnowledgeSource.Deduction -> "I reckon that $body."
        }
    }
}
