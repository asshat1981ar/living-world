package org.livingworld.ui.audio

import kotlin.jvm.JvmInline
import kotlinx.serialization.Serializable

@JvmInline
@Serializable
value class SoundscapeId(val value: String) {
    override fun toString(): String = value
}

@Serializable
enum class AudioChannel {
    MASTER,
    MUSIC,
    AMBIENCE,
    SFX,
    UI
}

@Serializable
enum class DynamicTension(val label: String) {
    PEACEFUL("Peaceful & Pastoral"),
    SUSPICIOUS("Tense & Suspicious"),
    OMINOUS("Ominous & Unsettling"),
    HOSTILE("Active Hostility & War"),
    LAMENT("Somber Mourning & Loss")
}

@Serializable
data class AudioCue(
    val id: String,
    val name: String,
    val channel: AudioChannel = AudioChannel.SFX,
    val volume: Float = 1.0f,
    val looping: Boolean = false,
    val priority: Int = 1
)

@Serializable
data class AudioLayer(
    val id: String,
    val soundscapeId: SoundscapeId,
    val volume: Float = 1.0f,
    val pitch: Float = 1.0f,
    val description: String = ""
)

@Serializable
data class AmbienceState(
    val primarySoundscape: SoundscapeId,
    val layers: List<AudioLayer>,
    val tension: DynamicTension,
    val tensionIntensity: Float,
    val activeCues: List<AudioCue>,
    val description: String
)
