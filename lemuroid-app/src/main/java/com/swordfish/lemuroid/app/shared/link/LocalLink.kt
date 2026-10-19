package com.swordfish.lemuroid.app.shared.link

import android.view.KeyEvent
import com.swordfish.lemuroid.lib.core.CoreVariable
import com.swordfish.lemuroid.lib.library.CoreID
import com.swordfish.lemuroid.lib.library.GameSystem
import com.swordfish.lemuroid.lib.library.SystemCoreConfig
import com.swordfish.lemuroid.lib.library.SystemID
import com.swordfish.lemuroid.lib.library.db.entity.Game

/**
 * Two Game Boy games joined by a link cable on this device. TGB Dual runs both of them side by side, each
 * with its own save, and one controller drives either player.
 */
object LocalLink {
    /** RETRO_GAME_TYPE_GAMEBOY_LINK_2P: TGB Dual's subsystem that loads two games. */
    const val GAME_TYPE_LINK_2P = 0x101

    /** Each Game Boy's save RAM inside that subsystem (RETRO_MEMORY_SAVE_RAM tagged with the player). */
    const val MEMORY_PLAYER_1_SAVE_RAM = 1 shl 8
    const val MEMORY_PLAYER_2_SAVE_RAM = 3 shl 8

    val SYSTEMS = listOf(SystemID.GB.dbname, SystemID.GBC.dbname)
    private const val SCREEN_PLACEMENT = "tgbdual_screen_placement"
    private const val SCREEN_PLACEMENT_STACKED = "top-down"

    fun isSupported(game: Game): Boolean = game.systemId in SYSTEMS

    /**
     * @param netPlayer this device's player when the other Game Boy is on another device: only its screen
     * and sound are played here.
     */
    fun coreConfig(
        game: Game,
        portrait: Boolean,
        netPlayer: Int? = null,
    ): SystemCoreConfig {
        val controllerConfigs = GameSystem.findById(game.systemId).systemCoreConfigs.first().controllerConfigs
        val screens =
            if (netPlayer != null) {
                listOf(
                    CoreVariable("tgbdual_single_screen_mp", if (netPlayer == 0) "player 1 only" else "player 2 only"),
                    audioVariable(netPlayer),
                )
            } else {
                listOf(CoreVariable(SCREEN_PLACEMENT, if (portrait) SCREEN_PLACEMENT_STACKED else "left-right"))
            }
        return SystemCoreConfig(
            CoreID.TGBDUAL,
            controllerConfigs = controllerConfigs,
            defaultSettings = listOf(CoreVariable("tgbdual_gblink_enable", "enabled")) + screens,
            // A state would hold both games, and the next link could be with another one.
            statesSupported = false,
        )
    }

    /** Whether [coreConfig] stacks the two screens, rather than placing them side by side. */
    fun isStacked(coreConfig: SystemCoreConfig): Boolean {
        return coreConfig.defaultSettings.any { it.key == SCREEN_PLACEMENT && it.value == SCREEN_PLACEMENT_STACKED }
    }

    /** The player a key selects: L for the first Game Boy, R for the second. */
    fun playerForKey(keyCode: Int): Int? {
        return when (keyCode) {
            KeyEvent.KEYCODE_BUTTON_L1 -> 0
            KeyEvent.KEYCODE_BUTTON_R1 -> 1
            else -> null
        }
    }

    fun audioVariable(player: Int): CoreVariable {
        return CoreVariable("tgbdual_audio_output", if (player == 0) "Game Boy #1" else "Game Boy #2")
    }
}
