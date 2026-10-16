package org.livingworld.ui.sprites

import org.livingworld.core.events.GameEvent
import org.livingworld.core.ids.*
import org.livingworld.core.npc.*
import org.livingworld.core.relationships.Obligation
import org.livingworld.core.relationships.Relationship
import org.livingworld.core.time.WorldTime
import org.livingworld.core.world.EconomyState
import org.livingworld.core.world.Location
import org.livingworld.core.world.PlayerState
import org.livingworld.core.world.WorldState
import org.livingworld.ui.adapters.EventVisualAdapter
import org.livingworld.ui.adapters.ItemVisualAdapter
import org.livingworld.ui.adapters.MapVisualAdapter
import org.livingworld.ui.adapters.NpcVisualAdapter
import org.livingworld.ui.adapters.VillageDashboardAdapter
import org.livingworld.ui.theme.ChimeraSpritePalette
import kotlin.test.*

class SpriteSystemTest {

    private val manifest = SpriteManifest.createDefault()

    @Test
    fun test_sprite_manifest_resolution_and_fallbacks() {
        val elaraHostile = manifest.resolveNpcPortrait("elara", PortraitExpression.HOSTILE)
        assertNotNull(elaraHostile)
        assertEquals(SpriteCategory.NPC_PORTRAIT, elaraHostile.category)
        assertEquals("npc_elara_hostile", elaraHostile.baseName)

        val unknownExpr = manifest.resolveNpcPortrait("thorne", PortraitExpression.WOUNDED)
        assertNotNull(unknownExpr)
        assertEquals(SpriteCategory.NPC_PORTRAIT, unknownExpr.category)

        val unknownNpc = manifest.resolveNpcPortrait("random_stranger_npc", PortraitExpression.TENSE)
        assertNotNull(unknownNpc)
        assertEquals("npc_generic_neutral", unknownNpc.id.value)

        val elaraToken = manifest.resolveNpcToken("elara")
        assertNotNull(elaraToken)
        assertEquals(SpriteCategory.NPC_TOKEN, elaraToken.category)

        val strikePlayer = manifest.resolveCombatStance(isPlayer = true, stance = CombatStance.STRIKE, wounded = false)
        assertNotNull(strikePlayer)
        val strikeWounded = manifest.resolveCombatStance(isPlayer = true, stance = CombatStance.STRIKE, wounded = true)
        assertNotNull(strikeWounded)
        assertTrue(strikeWounded.baseName.contains("wounded"))

        val sword = manifest.resolveCampItem("iron_sword")
        assertNotNull(sword)
        assertEquals(SpriteCategory.CAMP_ITEM, sword.category)

        val rareSeal = manifest.resolveRaritySeal(ItemRarity.RARE)
        assertNotNull(rareSeal)
        assertEquals(SpriteCategory.SHARED_UI, rareSeal.category)
    }

    @Test
    fun test_palette_color_channels_and_tints() {
        val parchment = ChimeraSpritePalette.PARCHMENT_DARK
        assertEquals(1.0f, parchment.alpha)
        assertTrue(parchment.red > 0.05f)
        assertEquals("#FF1A1410", parchment.hex)

        val crimson = ChimeraSpritePalette.ACCENT_CRIMSON
        assertEquals("#FF8B2500", crimson.hex)
        assertTrue(crimson.red > crimson.blue)
    }

    @Test
    fun test_npc_visual_adapter_health_and_death_states() {
        val livingNpc = Npc(
            id = NpcId("merchant"),
            identity = Identity(
                name = "Alden the Merchant",
                occupation = "Merchant",
                homeLocation = LocationId("village_square"),
                currentLocation = LocationId("village_square"),
                isAlive = true
            ),
            resources = ResourceState(health = 100f)
        )

        val livingVisual = NpcVisualAdapter.createVisualModel(livingNpc, manifest)
        assertTrue(livingVisual.isAlive)
        assertEquals(PortraitExpression.NEUTRAL, livingVisual.expression)
        assertFalse(livingVisual.needsInkWashOverlay)

        val woundedNpc = livingNpc.copy(resources = ResourceState(health = 20f))
        val woundedVisual = NpcVisualAdapter.createVisualModel(woundedNpc, manifest)
        assertEquals(PortraitExpression.WOUNDED, woundedVisual.expression)
        assertTrue(woundedVisual.needsInkWashOverlay)
        assertTrue(woundedVisual.statusBadges.contains("Wounded"))

        val deadNpc = livingNpc.copy(
            identity = livingNpc.identity.copy(isAlive = false),
            resources = ResourceState(health = 0f)
        )
        val deadVisual = NpcVisualAdapter.createVisualModel(deadNpc, manifest)
        assertFalse(deadVisual.isAlive)
        assertEquals(PortraitExpression.DECEASED, deadVisual.expression)
        assertTrue(deadVisual.statusBadges.contains("Deceased"))
    }

    @Test
    fun test_npc_visual_adapter_relationship_expressions() {
        val npc = Npc(
            id = NpcId("apprentice"),
            identity = Identity(
                name = "Finn",
                occupation = "Apprentice",
                homeLocation = LocationId("village_square"),
                currentLocation = LocationId("village_square")
            )
        )

        val friendlyRel = Relationship(trust = 0.8f, affection = 0.7f, respect = 0.6f)
        val friendlyVisual = NpcVisualAdapter.createVisualModel(npc, manifest, friendlyRel)
        assertEquals(PortraitExpression.GRATEFUL, friendlyVisual.expression)
        assertTrue(friendlyVisual.statusBadges.contains("Friendly"))

        val oathRel = Relationship(
            trust = 0.4f,
            obligations = listOf(
                Obligation(
                    id = "life_debt",
                    debtor = npc.id.toEntityId(),
                    creditor = EntityId.Player("player"),
                    description = "Caravan rescue",
                    weight = 1.0f
                )
            )
        )
        val oathVisual = NpcVisualAdapter.createVisualModel(npc, manifest, oathRel)
        assertEquals(PortraitExpression.OATHBOUND, oathVisual.expression)
        assertTrue(oathVisual.statusBadges.contains("Oathbound"))

        val hostileRel = Relationship(trust = -0.9f, resentment = 0.95f)
        val hostileVisual = NpcVisualAdapter.createVisualModel(npc, manifest, hostileRel)
        assertEquals(PortraitExpression.HOSTILE, hostileVisual.expression)
        assertTrue(hostileVisual.needsInkWashOverlay)
        assertTrue(hostileVisual.statusBadges.contains("Hostile"))
    }

    @Test
    fun test_map_and_event_visual_adapters() {
        val loc = Location(LocationId("village_square"), "Village Square")
        val npc = Npc(
            id = NpcId("elara"),
            identity = Identity(
                name = "Elara",
                occupation = "Herbalist",
                homeLocation = LocationId("village_square"),
                currentLocation = LocationId("village_square")
            )
        )
        val world = WorldState(
            locations = mapOf(loc.id to loc),
            npcs = mapOf(npc.id to npc),
            player = PlayerState(currentLocation = loc.id)
        )

        val nodeModel = MapVisualAdapter.createNodeModel(loc, world, manifest)
        assertEquals("Village Square", nodeModel.displayName)
        assertTrue(nodeModel.isPlayerPresent)
        assertEquals(1, nodeModel.occupantCount)
        assertEquals(1, nodeModel.occupantTokens.size)

        val killEvent = GameEvent.NpcKilled(
            id = EventId(10L),
            time = WorldTime(1440L * 5),
            victim = NpcId("merchant"),
            killer = EntityId.Npc(NpcId("miner_derek")),
            witnesses = listOf(NpcId("apprentice")),
            motive = "Merchant died"
        )
        val eventModel = EventVisualAdapter.createEventModel(killEvent, manifest)
        assertTrue(eventModel.isViolentOrCritical)
        assertEquals("Day 5, 00:00", eventModel.timeFormatted)
        assertEquals(ChimeraSpritePalette.TINT_WOUNDED, eventModel.tintColor)
    }

    @Test
    fun test_item_visual_adapter() {
        val economy = EconomyState(
            itemPrices = mapOf("bread" to 14.0f),
            scarcityFactors = mapOf("bread" to 1.5f)
        )
        val itemModel = ItemVisualAdapter.createItemModel(ItemId("bread"), economy, manifest, ItemRarity.COMMON)
        assertEquals("Bread", itemModel.displayName)
        assertEquals(21.0f, itemModel.currentPrice)
        assertNotNull(itemModel.itemSprite)
        assertNotNull(itemModel.raritySealSprite)
    }

    @Test
    fun test_village_dashboard_adapter() {
        val loc = Location(LocationId("village_square"), "Village Square")
        val livingNpc = Npc(
            id = NpcId("elara"),
            identity = Identity(
                name = "Elara",
                occupation = "Herbalist",
                homeLocation = LocationId("village_square"),
                currentLocation = LocationId("village_square")
            )
        )
        val deadNpc = Npc(
            id = NpcId("merchant"),
            identity = Identity(
                name = "Alden",
                occupation = "Merchant",
                homeLocation = LocationId("village_square"),
                currentLocation = LocationId("village_square"),
                isAlive = false
            ),
            resources = ResourceState(health = 0f)
        )
        val world = WorldState(
            locations = mapOf(loc.id to loc),
            npcs = mapOf(livingNpc.id to livingNpc, deadNpc.id to deadNpc),
            player = PlayerState(currentLocation = loc.id)
        )

        val dashboard = VillageDashboardAdapter.createDashboard(world, manifest)
        assertEquals(2, dashboard.totalNpcs)
        assertEquals(1, dashboard.aliveNpcs)
        assertEquals(1, dashboard.deadNpcs)
        assertTrue(dashboard.criticalAlerts.any { it.contains("deceased") })
    }

    @Test
    fun test_chimera_ui_layout_engine_map_canvas() {
        val locSquare = Location(LocationId("village_square"), "Village Square", connectedLocations = listOf(LocationId("market")))
        val locMarket = Location(LocationId("market"), "Market", connectedLocations = listOf(LocationId("village_square")))
        val npc = Npc(
            id = NpcId("elara"),
            identity = Identity(
                name = "Elara",
                occupation = "Herbalist",
                homeLocation = locSquare.id,
                currentLocation = locSquare.id
            )
        )
        val world = WorldState(
            locations = mapOf(locSquare.id to locSquare, locMarket.id to locMarket),
            npcs = mapOf(npc.id to npc),
            player = PlayerState(currentLocation = locSquare.id)
        )

        val canvas = org.livingworld.ui.compose.ChimeraUiLayoutEngine.createMapCanvasState(world, manifest)
        assertEquals(2, canvas.nodes.size)
        assertEquals(1, canvas.connections.size)
        val squareNode = canvas.nodes.first { it.locationId == locSquare.id }
        assertTrue(squareNode.isPlayerPresent)
        assertEquals(1, squareNode.residentTokens.size)
    }

    @Test
    fun test_chimera_ui_layout_engine_market_catalog() {
        val economy = EconomyState(
            itemPrices = mapOf("bread" to 15.0f, "iron_sword" to 75.0f),
            scarcityFactors = mapOf("bread" to 1.5f)
        )
        val world = WorldState(
            economy = economy,
            player = PlayerState(currentLocation = LocationId("village_square"))
        )

        val catalog = org.livingworld.ui.compose.ChimeraUiLayoutEngine.createMarketCatalog(
            listOf(ItemId("bread"), ItemId("iron_sword")),
            world,
            manifest
        )
        assertEquals(2, catalog.size)
        val bread = catalog.first { it.itemId.value == "bread" }
        assertEquals(22.5f, bread.currentPrice)
        assertTrue(bread.isScarce)
        assertTrue(bread.priceDeltaPercent > 0f)
    }
}
