package com.swordfish.lemuroid.app.shared.link

import com.swordfish.lemuroid.lib.library.db.entity.Game
import java.text.Normalizer

/**
 * What two games can do over the link cable, for the few games where that is certain: the Pokémon
 * generations. Anything else gets no hint rather than a guess, and neither do games of different regions,
 * since Japanese and Western cartridges can't link.
 */
object LinkCompatibility {
    enum class Link { TRADE_AND_BATTLE, TIME_CAPSULE }

    private enum class Generation { FIRST, SECOND }

    private enum class Region { WESTERN, JAPANESE }

    private data class Known(val generation: Generation, val region: Region)

    private val FIRST = listOf("pokemon red", "pokemon blue", "pokemon yellow")
    private val SECOND = listOf("pokemon gold", "pokemon silver", "pokemon crystal")

    private val WESTERN_TAGS = setOf("usa", "u", "europe", "e", "ue", "world")
    private val JAPANESE_TAGS = setOf("japan", "j")

    fun describe(
        game: Game,
        other: Game,
    ): Link? {
        val first = identify(game) ?: return null
        val second = identify(other) ?: return null
        if (first.region != second.region) return null
        return if (first.generation == second.generation) Link.TRADE_AND_BATTLE else Link.TIME_CAPSULE
    }

    private fun identify(game: Game): Known? {
        val name = normalize(game.fileName.substringBeforeLast('.'))
        val generation =
            when {
                FIRST.any { name.startsWith(it) } -> Generation.FIRST
                SECOND.any { name.startsWith(it) } -> Generation.SECOND
                else -> return null
            }
        return Known(generation, region(game.fileName) ?: return null)
    }

    /** "Pokémon - Yellow Version - Special Pikachu Edition" -> "pokemon yellow special pikachu edition". */
    private fun normalize(name: String): String {
        val plain = Normalizer.normalize(name.substringBefore('(').substringBefore('['), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}"), "")
            .lowercase()
        return plain.replace(" version", " ").replace(Regex("[^a-z0-9]+"), " ").trim()
    }

    /** The region from the file name's tags, e.g. "(USA, Europe)" or "(UE)". Unknown when unclear. */
    private fun region(fileName: String): Region? {
        val tags =
            Regex("\\(([^)]*)\\)").findAll(fileName)
                .flatMap { it.groupValues[1].split(',') }
                .map { it.trim().lowercase() }
                .toSet()
        val western = tags.any { it in WESTERN_TAGS }
        val japanese = tags.any { it in JAPANESE_TAGS }
        return when {
            western && !japanese -> Region.WESTERN
            japanese && !western -> Region.JAPANESE
            else -> null
        }
    }
}
