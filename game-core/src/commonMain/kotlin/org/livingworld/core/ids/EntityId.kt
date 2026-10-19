package org.livingworld.core.ids

import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline

@Serializable
sealed interface EntityId {
    val identifier: String

    // Subclasses are data classes, not value classes: a value class cannot carry the polymorphic "type"
    // discriminator, and kotlinx.serialization then wrote it into the next JSON structure (corrupt, unloadable saves).
    @Serializable
    data class Npc(val id: NpcId) : EntityId {
        override val identifier: String get() = id.value
        override fun toString(): String = "Npc($identifier)"
    }

    @Serializable
    data class Player(val id: String = "player") : EntityId {
        override val identifier: String get() = id
        override fun toString(): String = "Player($id)"
    }

    @Serializable
    data class Faction(val id: FactionId) : EntityId {
        override val identifier: String get() = id.value
        override fun toString(): String = "Faction($identifier)"
    }

    @Serializable
    data class Location(val id: LocationId) : EntityId {
        override val identifier: String get() = id.value
        override fun toString(): String = "Location($identifier)"
    }
}

@JvmInline
@Serializable
value class NpcId(val value: String) : Comparable<NpcId> {
    fun toEntityId(): EntityId = EntityId.Npc(this)
    override fun compareTo(other: NpcId): Int = value.compareTo(other.value)
}

@JvmInline
@Serializable
value class EventId(val value: Long) : Comparable<EventId> {
    override fun compareTo(other: EventId): Int = value.compareTo(other.value)
}

@JvmInline
@Serializable
value class FactionId(val value: String) : Comparable<FactionId> {
    fun toEntityId(): EntityId = EntityId.Faction(this)
    override fun compareTo(other: FactionId): Int = value.compareTo(other.value)
}

@JvmInline
@Serializable
value class ItemId(val value: String) : Comparable<ItemId> {
    override fun compareTo(other: ItemId): Int = value.compareTo(other.value)
}

@JvmInline
@Serializable
value class LocationId(val value: String) : Comparable<LocationId> {
    fun toEntityId(): EntityId = EntityId.Location(this)
    override fun compareTo(other: LocationId): Int = value.compareTo(other.value)
}

@JvmInline
@Serializable
value class FactId(val value: String)
