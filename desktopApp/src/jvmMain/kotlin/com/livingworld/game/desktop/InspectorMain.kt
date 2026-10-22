package com.livingworld.game.desktop

import kotlinx.coroutines.runBlocking
import org.livingworld.core.events.GameEvent
import org.livingworld.core.ids.ItemId
import org.livingworld.core.simulation.SimulationEngine
import org.livingworld.core.time.WorldDuration
import org.livingworld.core.world.DefaultVillageFactory
import org.livingworld.persistence.SaveManager
import org.livingworld.ui.compose.ChimeraUiLayoutEngine
import org.livingworld.ui.sprites.SpriteManifest
import java.io.File

fun main(args: Array<String>) = runBlocking {
    // Usage: <days> <tail> [--save=<path>]   (positional args keep INSTRUCTIONS.md's `--args="30 10"` working)
    val savePath = args.firstOrNull { it.startsWith("--save=") }?.removePrefix("--save=")
    val positional = args.filterNot { it.startsWith("--") }
    val goldenPath = args.firstOrNull { it.startsWith("--golden=") }?.removePrefix("--golden=")
    val dumpPath = args.firstOrNull { it.startsWith("--dump=") }?.removePrefix("--dump=")
    val stateGoldenPath = args.firstOrNull { it.startsWith("--state-golden=") }?.removePrefix("--state-golden=")
    val stateDumpPath = args.firstOrNull { it.startsWith("--state-dump=") }?.removePrefix("--state-dump=")
    val scenario = args.firstOrNull { it.startsWith("--scenario=") }?.removePrefix("--scenario=") ?: "default"
    val days = positional.getOrNull(0)?.toIntOrNull() ?: 30
    val tail = positional.getOrNull(1)?.toIntOrNull() ?: 10

    println("=== Living-World Inspector (Milestone quest-procedural-005 & compose-combat-006) ===")
    println("world=marketVillage  scenario=$scenario  duration=${days}d  tail=$tail")
    println()

    val engine = SimulationEngine()
    var world = DefaultVillageFactory.marketVillage()
    // The default run has no deaths, rumors or thefts, so its fingerprint says nothing about those paths (G8 blind spot).
    when (scenario) {
        "turmoil" -> world = TurmoilScenario.seed(world)
        "feud" -> world = TurmoilScenario.seedFeud(world)
        "famine" -> world = TurmoilScenario.seedFamine(world)
        "schism" -> world = TurmoilScenario.seedSchism(world)
        else -> require(scenario == "default") { "unknown scenario '$scenario' (default, turmoil, feud, famine, schism)" }
    }

    val startNanos = System.nanoTime()
    world = engine.advance(world, WorldDuration.days(days.toLong()))
    val elapsedMs = (System.nanoTime() - startNanos) / 1_000_000

    println("Ticks simulated : ${world.time.tick}")
    println("Events recorded : ${world.events.size}")
    println("Alive NPCs      : ${world.npcs.values.count { it.isAlive }} / ${world.npcs.size}")
    println("Wall time       : ${elapsedMs}ms")
    println("Fingerprint     : ${SaveManager.computeDeterministicFingerprint(world)}")
    if (goldenPath != null) println("Golden          : ${GoldenCheck.compare(goldenPath, CanonicalDump.dump(world))}")
    if (dumpPath != null) {
        File(dumpPath).apply { parentFile?.mkdirs() }.writeText(CanonicalDump.dump(world).joinToString("\n") + "\n")
        println("Canonical dump  : $dumpPath")
    }
    if (stateGoldenPath != null) println("StateGolden     : ${GoldenCheck.compare(stateGoldenPath, CanonicalStateDump.dump(world))}")
    if (stateDumpPath != null) {
        File(stateDumpPath).apply { parentFile?.mkdirs() }.writeText(CanonicalStateDump.dump(world).joinToString("\n") + "\n")
        println("State dump      : $stateDumpPath")
    }
    if (savePath != null) {
        File(savePath).apply { parentFile?.mkdirs() }.writeText(SaveManager.serializeEnvelope(world))
        println("Saved envelope  : $savePath")
    }
    println()

    println("=== Event census ===")
    println("NpcRoutineCompleted     : ${world.events.ofType<GameEvent.NpcRoutineCompleted>().size}")
    println("TradeCompleted          : ${world.events.ofType<GameEvent.TradeCompleted>().size}")
    println("RelationshipShifted     : ${world.events.ofType<GameEvent.RelationshipShifted>().size}")
    println("AllegianceChanged       : ${world.events.ofType<GameEvent.AllegianceChanged>().size}")
    println("FactionHostilityDeclared: ${world.events.ofType<GameEvent.FactionHostilityDeclared>().size}")
    println("PriceAdjusted           : ${world.events.ofType<GameEvent.PriceAdjusted>().size}")
    println("NpcAttacked             : ${world.events.ofType<GameEvent.NpcAttacked>().size}")
    println("NpcKilled               : ${world.events.ofType<GameEvent.NpcKilled>().size}")
    println("RumorTransmitted        : ${world.events.ofType<GameEvent.RumorTransmitted>().size}")
    println("ItemStolen              : ${world.events.ofType<GameEvent.ItemStolen>().size}")
    println("OpportunityDispatched   : ${world.events.ofType<GameEvent.OpportunityDispatched>().size}")
    println()

    println("=== Active Problems (${world.activeProblems().size}) ===")
    for (problem in world.activeProblems()) {
        println("  [${problem.id}] (${problem.category}) severity=${problem.severity}: ${problem.summary}")
    }
    println()

    println("=== Derived Quest Opportunities (${world.generateOpportunities().size}) ===")
    for (opp in world.generateOpportunities()) {
        println("  [${opp.id}] title=\"${opp.title}\" reward=${opp.reward} status=${opp.status}")
    }
    println()

    val manifest = SpriteManifest.createDefault()
    println("=== UI Presentation Inspection (SpriteManifest: ${manifest.count()} assets) ===")

    val mapCanvas = ChimeraUiLayoutEngine.createMapCanvasState(world, manifest)
    println("\n--- [Canvas Map Layout] ${mapCanvas.title} (Active Crises: ${mapCanvas.activeCrisisCount}) ---")
    for (node in mapCanvas.nodes) {
        val playerMarker = if (node.isPlayerPresent) " [PLAYER]" else ""
        println("  Node '${node.label}' at (${node.position.x}, ${node.position.y}) state=${node.state} sprite=${node.spriteRef?.id}$playerMarker")
        println("    Occupants (${node.residentTokens.size} tokens): ${node.residentTokens.joinToString { it.id.value }}")
    }

    println("\n--- [NPC Portrait Gallery (Top 5 Villagers)] ---")
    for (npc in world.npcs.values.take(5)) {
        val card = ChimeraUiLayoutEngine.createPortraitCardState(npc, manifest)
        val inkFlag = if (card.showInkWashBleed) " [INK_WASH_OVERLAY]" else ""
        println("  * ${card.name} (${card.roleTitle}) - Expr: ${card.expression} (Tint: ${card.tint.hex})$inkFlag")
        println("    Portrait: ${card.portraitSprite?.id?.value ?: "None"} | Badges: ${card.badges.joinToString(", ")}")
    }

    val commodities = listOf(ItemId("bread"), ItemId("iron_sword"), ItemId("herb_bundle"), ItemId("iron_ingot"))
    val marketCatalog = ChimeraUiLayoutEngine.createMarketCatalog(commodities, world, manifest)
    println("\n--- [Marketplace Catalog UI] ---")
    for (itemCard in marketCatalog) {
        val trend = if (itemCard.priceDeltaPercent >= 0) "+${itemCard.priceDeltaPercent.toInt()}%" else "${itemCard.priceDeltaPercent.toInt()}%"
        println("  * [${itemCard.rarity}] ${itemCard.name}: ${itemCard.currentPrice} coins ($trend from base ${itemCard.basePrice})")
        println("    ItemSprite: ${itemCard.itemSprite?.id?.value} | Seal: ${itemCard.sealSprite?.id?.value} | Scarce: ${itemCard.isScarce}")
    }

    println("\n=== Tail chains (last $tail events as Causal UI Cards) ===")
    val causalCards = ChimeraUiLayoutEngine.createCausalTimeline(world, manifest, limit = tail)
    for (card in causalCards) {
        val indent = "  ".repeat(card.causalDepth)
        val critMarker = if (card.isCritical) " [CRITICAL]" else ""
        val parentRef = card.causalParentId?.let { " <- [Event #${it.value}]" } ?: ""
        println("$indent[${card.timestamp}] Event #${card.eventId.value} (${card.title})$critMarker$parentRef")
        println("$indent  Summary : ${card.summary}")
        println("$indent  Portrait: ${card.actorPortrait?.id?.value ?: "None"} (Border: ${card.borderTint.hex})")
        println()
    }
}
