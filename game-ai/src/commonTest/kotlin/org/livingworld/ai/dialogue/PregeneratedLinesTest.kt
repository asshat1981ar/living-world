package org.livingworld.ai.dialogue

import kotlinx.coroutines.test.runTest
import org.livingworld.core.beliefs.Belief
import org.livingworld.core.beliefs.KnowledgeSource
import org.livingworld.core.beliefs.Proposition
import org.livingworld.core.ids.*
import org.livingworld.core.npc.Identity
import org.livingworld.core.npc.Npc
import org.livingworld.core.relationships.Relationship
import org.livingworld.core.time.WorldTime
import kotlin.test.*

/** Lines are written ahead of time and only ever shown when ready, so the player never sees text change mid-read. */
class PregeneratedLinesTest {

    private class CountingModel(private val reply: (String) -> Result<String>) : ChatModel {
        var calls = 0
        override suspend fun complete(system: String?, user: String, temperature: Float, maxTokens: Int): Result<String> {
            calls++
            return reply(user)
        }
        override fun describeFailure(error: Throwable) = error.message ?: "failed"
    }

    private val home = LocationId("village_square")
    private val npc = Npc(id = NpcId("elara"), identity = Identity("Elara", "Herbalist", home, home))
    private fun theft(item: String) = Belief(Proposition.PlayerStole(ItemId(item)), 0.9f, KnowledgeSource.DirectObservation, 1f, WorldTime(0L))
    private fun request(topic: String = "Any news?", beliefs: List<Belief> = listOf(theft("iron_sword"))) =
        LineRequest(npc, topic, home, beliefs, Relationship.NEUTRAL)

    @Test
    fun a_prepared_grounded_line_is_served_only_for_the_same_npc_topic_and_facts() = runTest {
        val model = CountingModel { Result.success("Aye, it is cold.") }
        val lines = PregeneratedLines(GroundedDialogueVoice(model))
        assertNull(lines.lineFor(request()), "nothing is shown before it is ready")

        lines.prepare(request())
        assertEquals("Aye, it is cold.", lines.lineFor(request()))
        assertNull(lines.lineFor(request(topic = "Farewell.")))
        assertNull(lines.lineFor(request(beliefs = listOf(theft("gold_ring")))), "new knowledge makes the old line stale")
        assertNull(lines.lineFor(request(beliefs = emptyList())))
    }

    @Test
    fun the_same_request_reaches_the_model_once_even_when_it_failed_or_was_rejected() = runTest {
        val model = CountingModel { Result.success("Mara poisoned the well.") }
        val lines = PregeneratedLines(GroundedDialogueVoice(model))
        repeat(3) { lines.prepare(request()) }
        assertEquals(1, model.calls)
        assertNull(lines.lineFor(request()), "an ungrounded reply is never cached")

        val offline = CountingModel { Result.failure(IllegalStateException("offline")) }
        val noModel = PregeneratedLines(GroundedDialogueVoice(offline))
        repeat(2) { noModel.prepare(request()) }
        assertEquals(1, offline.calls)
        assertNull(noModel.lineFor(request()))
    }

    @Test
    fun the_cache_is_bounded_and_drops_the_oldest_lines() = runTest {
        val lines = PregeneratedLines(GroundedDialogueVoice(CountingModel { Result.success("Aye, it is cold.") }), capacity = 2)
        lines.prepare(request(topic = "one"))
        lines.prepare(request(topic = "two"))
        lines.prepare(request(topic = "three"))
        assertNull(lines.lineFor(request(topic = "one")))
        assertNotNull(lines.lineFor(request(topic = "two")))
        assertNotNull(lines.lineFor(request(topic = "three")))
    }

    @Test
    fun stats_come_from_the_voice() = runTest {
        val lines = PregeneratedLines(GroundedDialogueVoice(CountingModel { Result.success("Aye, it is cold.") }))
        lines.prepare(request())
        assertEquals(1, lines.stats.accepted)
    }
}
