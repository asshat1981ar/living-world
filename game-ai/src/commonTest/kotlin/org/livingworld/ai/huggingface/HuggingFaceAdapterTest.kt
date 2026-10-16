package org.livingworld.ai.huggingface

import kotlinx.coroutines.test.runTest
import org.livingworld.ai.ondevice.GgufBinaryHeaderParser
import org.livingworld.ai.reasoning.DeterministicAiFallback
import org.livingworld.ai.reasoning.ReasoningContext
import org.livingworld.core.beliefs.Belief
import org.livingworld.core.beliefs.KnowledgeSource
import org.livingworld.core.beliefs.Proposition
import org.livingworld.core.ids.*
import org.livingworld.core.npc.Goal
import org.livingworld.core.npc.Identity
import org.livingworld.core.npc.Npc
import org.livingworld.core.relationships.Relationship
import org.livingworld.core.simulation.ActionCandidate
import org.livingworld.core.simulation.Intent
import org.livingworld.core.time.WorldTime
import org.livingworld.core.world.Location
import org.livingworld.core.world.WorldSnapshot
import kotlin.test.*

class HuggingFaceAdapterTest {

    private fun createTestContext(): ReasoningContext {
        val loc = Location(LocationId("village_square"), "Village Square")
        val npc = Npc(
            id = NpcId("merchant"),
            identity = Identity("Alden", "Merchant", loc.id, loc.id)
        )
        val snapshot = WorldSnapshot(
            time = WorldTime(1440L),
            location = loc,
            presentNpcs = listOf(npc.id),
            controllingFaction = null,
            recentEvents = emptyList()
        )
        val candidate0 = ActionCandidate(action = Intent.Rest)
        val candidate1 = ActionCandidate(action = Intent.Trade(partner = EntityId.Player("player"), item = ItemId("bread"), price = 5.0f))
        val candidate2 = ActionCandidate(action = Intent.Work(loc.id))

        return ReasoningContext(
            npc = npc,
            relevantWorldState = snapshot,
            memories = emptyList(),
            beliefs = listOf(
                Belief(
                    proposition = Proposition.PriceChanged(ItemId("bread"), 5.0f, 10.0f),
                    confidence = 0.9f,
                    source = KnowledgeSource.DirectObservation,
                    reliability = 1.0f,
                    acquiredAt = WorldTime(1440L)
                )
            ),
            relationships = listOf(Relationship(trust = 0.5f, affection = 0.4f)),
            goals = listOf(Goal("profit", "Accumulate silver", priority = 0.8f, urgency = 0.6f, conditions = emptyList(), successConditions = emptyList(), failureConditions = emptyList())),
            candidates = listOf(candidate0, candidate1, candidate2)
        )
    }

    @Test
    fun test_huggingface_reasoning_success_with_mock_transport() = runTest {
        val mockResponse = """
        {
            "id": "chatcmpl-12345",
            "choices": [
                {
                    "message": {
                        "role": "assistant",
                        "content": "{\"selected_index\": 1, \"confidence\": 0.92, \"explanation\": \"Trading bread directly fulfills the profit goal while maintaining good player relations.\"}"
                    },
                    "finish_reason": "stop"
                }
            ]
        }
        """.trimIndent()

        val mockTransport = MockHfTransport { url, body ->
            assertTrue(url.contains("router.huggingface.co") || url.contains("chat/completions"))
            assertTrue(body.contains("Alden"))
            Result.success(mockResponse)
        }

        val adapter = HuggingFaceReasoningAdapter(
            config = HuggingFaceEndpointConfig.defaultFreeTier(apiToken = "hf_test_token"),
            transport = mockTransport
        )

        val context = createTestContext()
        val proposal = adapter.reason(context)

        assertNotNull(proposal)
        assertTrue(proposal.selectedAction is Intent.Trade)
        assertEquals(0.92f, proposal.confidence)
        assertTrue(proposal.explanation?.contains("profit goal") == true)
    }

    @Test
    fun test_huggingface_offline_graceful_fallback() = runTest {
        val adapter = HuggingFaceReasoningAdapter(
            transport = OfflineHfTransport()
        )

        val context = createTestContext()
        val proposal = adapter.reason(context)
        assertNotNull(proposal)
        assertTrue(proposal.confidence > 0f)
        assertTrue(DeterministicAiFallback.isFallbackProposal(proposal))
    }

    @Test
    fun test_huggingface_malformed_json_fallback() = runTest {
        val mockTransport = MockHfTransport { _, _ ->
            Result.success("I think Alden should rest because he is tired.")
        }

        val adapter = HuggingFaceReasoningAdapter(transport = mockTransport)
        val context = createTestContext()
        val proposal = adapter.reason(context)

        assertNotNull(proposal)
        assertNotNull(proposal.selectedAction)
    }

    @Test
    fun test_huggingface_dialogue_adapter_prompt_and_fallback() = runTest {
        val loc = Location(LocationId("village_square"), "Village Square")
        val npc = Npc(id = NpcId("elara"), identity = Identity("Elara", "Herbalist", loc.id, loc.id))
        val belief = Belief(
            proposition = Proposition.PlayerStole(ItemId("iron_sword")),
            confidence = 0.85f,
            source = KnowledgeSource.Rumor(NpcId("guard_captain")),
            reliability = 0.8f,
            acquiredAt = WorldTime(1440L)
        )

        val dialogueAdapter = HuggingFaceDialogueAdapter(transport = OfflineHfTransport())
        val prompt = dialogueAdapter.buildDialoguePrompt(npc, "Have you seen my sword?", loc.id, listOf(belief), Relationship.NEUTRAL)

        assertTrue(prompt.contains("Elara"))
        assertTrue(prompt.contains("PlayerStole(iron_sword)"))

        val reply = dialogueAdapter.generateDialogueLine(npc, "Have you seen my sword?", loc.id, listOf(belief), Relationship.NEUTRAL)
        assertNotNull(reply)
        assertTrue(reply.contains("whispered") || reply.contains("PlayerStole"))
    }

    @Test
    fun test_gguf_binary_header_parser_validation() {
        val validHeader = byteArrayOf(
            0x47, 0x47, 0x55, 0x46, // "GGUF"
            0x03, 0x00, 0x00, 0x00, // version 3
            0x1E, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, // 30 tensors
            0x10, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00  // 16 KV pairs
        )
        val parsed = GgufBinaryHeaderParser.parseHeaderBytes(validHeader, "qwen2.5-0.5b-instruct").getOrThrow()
        assertEquals("qwen2", parsed.architecture)
        assertEquals(3, parsed.formatVersion)
        assertEquals(30L, parsed.tensorCount)
        assertEquals(16L, parsed.metadataKvCount)

        val invalidHeader = ByteArray(24) { 0x00 }
        assertTrue(GgufBinaryHeaderParser.parseHeaderBytes(invalidHeader).isFailure)
    }

    @Test
    fun test_on_device_model_catalog_and_memory_budgets() {
        assertEquals(5, OnDeviceModelCatalog.ALL_ON_DEVICE_MODELS.size)

        val lowMemoryModels = OnDeviceModelCatalog.findForMemoryBudget(250)
        assertEquals(2, lowMemoryModels.size)
        assertTrue(lowMemoryModels.any { it.modelId.contains("135M") })
        assertTrue(lowMemoryModels.any { it.modelId.contains("360M") })

        val midMemoryModels = OnDeviceModelCatalog.findForMemoryBudget(800)
        assertEquals(4, midMemoryModels.size)
        assertTrue(midMemoryModels.any { it.modelId.contains("Llama-3.2-1B") })
    }
}
