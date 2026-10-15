package com.swordfish.lemuroid.app.shared.link

import android.content.Context
import java.io.Serializable
import java.util.UUID
import kotlin.random.Random

/**
 * The friendly name a player shows to nearby players, e.g. "Coral Fox".
 * Picked at random the first time and changeable from a fixed palette, so names stay short and readable.
 */
data class LinkIdentity(
    val color: LinkColor,
    val creature: LinkCreature,
) : Serializable {
    val displayName: String
        get() = "${color.label} ${creature.label}"

    companion object {
        fun random(random: Random = Random.Default): LinkIdentity {
            return LinkIdentity(LinkColor.entries.random(random), LinkCreature.entries.random(random))
        }

        fun fromIds(
            colorId: String?,
            creatureId: String?,
        ): LinkIdentity? {
            val color = LinkColor.entries.firstOrNull { it.name == colorId } ?: return null
            val creature = LinkCreature.entries.firstOrNull { it.name == creatureId } ?: return null
            return LinkIdentity(color, creature)
        }
    }
}

enum class LinkColor(val label: String, val argb: Long) {
    CORAL("Coral", 0xFFFF7F6E),
    CHERRY("Cherry", 0xFFE5484D),
    PEACH("Peach", 0xFFFFB38A),
    AMBER("Amber", 0xFFFFB224),
    LEMON("Lemon", 0xFFF5D90A),
    MOSS("Moss", 0xFF8DB600),
    MINT("Mint", 0xFF5BD6A0),
    TEAL("Teal", 0xFF12A594),
    SKY("Sky", 0xFF68C7F5),
    OCEAN("Ocean", 0xFF3E7BFA),
    INDIGO("Indigo", 0xFF5B5BD6),
    LILAC("Lilac", 0xFFB592F5),
    PLUM("Plum", 0xFF9E4CB8),
    ROSE("Rose", 0xFFF06EAA),
    COCOA("Cocoa", 0xFF9C6644),
    SLATE("Slate", 0xFF7C8796),
}

enum class LinkCreature(val label: String, val emoji: String) {
    FOX("Fox", "🦊"),
    OTTER("Otter", "🦦"),
    OWL("Owl", "🦉"),
    PENGUIN("Penguin", "🐧"),
    HEDGEHOG("Hedgehog", "🦔"),
    KOALA("Koala", "🐨"),
    PANDA("Panda", "🐼"),
    FROG("Frog", "🐸"),
    BUNNY("Bunny", "🐰"),
    TURTLE("Turtle", "🐢"),
    SEAL("Seal", "🦭"),
    WHALE("Whale", "🐳"),
    OCTOPUS("Octopus", "🐙"),
    SLOTH("Sloth", "🦥"),
    RACCOON("Raccoon", "🦝"),
    BEAVER("Beaver", "🦫"),
    LLAMA("Llama", "🦙"),
    DUCK("Duck", "🦆"),
    FLAMINGO("Flamingo", "🦩"),
    PARROT("Parrot", "🦜"),
    HAMSTER("Hamster", "🐹"),
    BEAR("Bear", "🐻"),
    DOLPHIN("Dolphin", "🐬"),
    CRAB("Crab", "🦀"),
}

/** Stores the player's link name and a random install id, used to tell this device apart from others. */
class LinkIdentityStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun getIdentity(): LinkIdentity {
        val stored =
            LinkIdentity.fromIds(
                preferences.getString(KEY_COLOR, null),
                preferences.getString(KEY_CREATURE, null),
            )
        return stored ?: LinkIdentity.random().also { setIdentity(it) }
    }

    fun setIdentity(identity: LinkIdentity) {
        preferences.edit()
            .putString(KEY_COLOR, identity.color.name)
            .putString(KEY_CREATURE, identity.creature.name)
            .apply()
    }

    fun getDeviceId(): String {
        return preferences.getString(KEY_DEVICE_ID, null)
            ?: UUID.randomUUID().toString().take(8).also {
                preferences.edit().putString(KEY_DEVICE_ID, it).apply()
            }
    }

    companion object {
        private const val PREFERENCES_NAME = "game_link"
        private const val KEY_COLOR = "color"
        private const val KEY_CREATURE = "creature"
        private const val KEY_DEVICE_ID = "device_id"
    }
}
