package com.sikamikaniko.sonora.data

/** A saved AI mix — stored as its *criteria* (a living query), not a frozen tracklist. */
data class AiMix(
    val id: String,
    val name: String,
    val emoji: String,
    val criteria: String
)
