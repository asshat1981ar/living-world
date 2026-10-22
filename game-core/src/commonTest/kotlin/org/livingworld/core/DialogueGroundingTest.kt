package org.livingworld.core

import org.livingworld.core.beliefs.Belief
import org.livingworld.core.beliefs.KnowledgeSource
import org.livingworld.core.beliefs.Proposition
import org.livingworld.core.dialogue.DialogueGrounding
import org.livingworld.core.dialogue.GroundingCheck
import org.livingworld.core.ids.*
import org.livingworld.core.time.WorldTime
import kotlin.test.*

/** G3: what an NPC may say comes from what it believes. */
class DialogueGroundingTest {
    private val t = WorldTime(0)
    private fun belief(p: Proposition, c: Float, src: KnowledgeSource, r: Float = 1f) = Belief(p, c, src, r, t)
    private val theft = Proposition.PlayerStole(ItemId("iron_sword"))
    private val death = Proposition.NpcDied(NpcId("merchant"))

    @Test
    fun orders_by_confidence_times_reliability_then_text_and_applies_the_floor() {
        val facts = DialogueGrounding.speakableFacts(listOf(
            belief(Proposition.GeneralFact("b", "x"), 0.9f, KnowledgeSource.Deduction),
            belief(Proposition.GeneralFact("a", "x"), 0.9f, KnowledgeSource.Deduction),
            belief(theft, 0.3f, KnowledgeSource.DirectObservation),
            belief(death, 0.95f, KnowledgeSource.DirectObservation)
        ))
        assertEquals(listOf(death.describe(), "[a] x", "[b] x"), facts.map { it.belief.proposition.describe() })
    }

    @Test
    fun wording_follows_the_source() {
        val seen = DialogueGrounding.render(belief(death, 1f, KnowledgeSource.DirectObservation))
        val told = DialogueGrounding.render(belief(death, 0.85f, KnowledgeSource.Hearsay(NpcId("guard_captain"))))
        val doubted = DialogueGrounding.render(belief(death, 0.45f, KnowledgeSource.Hearsay(NpcId("guard_captain"))))
        assertTrue(seen.startsWith("I saw it myself"))
        assertTrue(told.startsWith("guard_captain told me that"))
        assertTrue("doubt" in doubted)
    }

    @Test
    fun npc_with_no_beliefs_has_nothing_to_say() {
        assertTrue(DialogueGrounding.speakableFacts(TestWorld.create().npc(NpcId("merchant"))).isEmpty())
    }

    @Test
    fun npc_does_not_speak_of_a_death_it_never_learned() {
        val world = TestWorld.create()
        world.kill(NpcId("tanner_garth"), killer = null)
        val stranger = world.npc(NpcId("guard_captain"))
        assertTrue(DialogueGrounding.speakableFacts(stranger).none { it.belief.proposition is Proposition.NpcDied })
    }

    @Test
    fun an_invented_lowercase_sentence_is_rejected() {
        val facts = DialogueGrounding.speakableFacts(listOf(belief(theft, 0.9f, KnowledgeSource.DirectObservation)))
        assertFalse(
            GroundingCheck.isGrounded("alden poisoned the well and plans to blame the miller", facts),
            "an invented lower-case sentence passed the grounding check"
        )
        assertTrue(
            GroundingCheck.isGrounded("I saw it myself: PlayerStole(iron_sword).", facts),
            "a line that only uses the words of the facts was rejected"
        )
    }

    @Test
    fun grounding_check_rejects_invented_names_and_numbers_and_accepts_grounded_lines() {
        val facts = DialogueGrounding.speakableFacts(listOf(belief(theft, 0.9f, KnowledgeSource.DirectObservation)))
        assertTrue(GroundingCheck.isGrounded("Well met, traveler. I saw it myself: PlayerStole(iron_sword).", facts))
        assertTrue(GroundingCheck.isGrounded("Greetings. What brings you here?", facts))
        assertFalse(GroundingCheck.isGrounded("The Iron Guild grows bolder each night.", facts))
        assertFalse(GroundingCheck.isGrounded("Captain Vane took 40 coins.", facts))
        assertTrue(GroundingCheck.isGrounded("Ask Elara.", facts, extraAllowed = listOf("Elara")))
    }

    @Test
    fun an_invented_name_at_the_start_of_a_sentence_is_still_a_claim() {
        val facts = DialogueGrounding.speakableFacts(listOf(belief(theft, 0.9f, KnowledgeSource.DirectObservation)))
        assertFalse(GroundingCheck.isGrounded("Mara poisoned the well.", facts))
        assertFalse(GroundingCheck.isGrounded("Aye. Mara poisoned the well.", facts))
        assertTrue(GroundingCheck.isGrounded("Aye. Perhaps you should ask elsewhere.", facts))
        assertTrue(GroundingCheck.isGrounded("Elara knows herbs.", facts, extraAllowed = listOf("Elara")))
    }

    @Test
    fun period_openers_are_not_names() {
        val facts = DialogueGrounding.speakableFacts(listOf(belief(theft, 0.9f, KnowledgeSource.DirectObservation)))
        assertTrue(GroundingCheck.isGrounded("Verily, the night is cold. Hark, prithee keep your voice down.", facts))
    }

    @Test
    fun grounded_part_keeps_only_the_grounded_sentences() {
        val facts = DialogueGrounding.speakableFacts(listOf(belief(theft, 0.9f, KnowledgeSource.DirectObservation)))
        assertEquals(
            "Hark! I saw it myself: PlayerStole(iron_sword).",
            GroundingCheck.groundedPart("Hark! Mara poisoned the well. I saw it myself: PlayerStole(iron_sword).", facts)
        )
        assertEquals("Aye, it is cold.", GroundingCheck.groundedPart("Aye, it is cold.", facts))
        assertNull(GroundingCheck.groundedPart("Mara poisoned the well. Captain Vane fled.", facts))
    }
}
