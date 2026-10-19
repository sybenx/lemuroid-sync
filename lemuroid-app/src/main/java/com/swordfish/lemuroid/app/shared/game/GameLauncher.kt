package com.swordfish.lemuroid.app.shared.game

import android.app.Activity
import android.content.res.Configuration
import androidx.appcompat.app.AlertDialog
import com.swordfish.lemuroid.R
import com.swordfish.lemuroid.app.shared.main.GameLaunchTaskHandler
import com.swordfish.lemuroid.app.shared.link.GameLink
import com.swordfish.lemuroid.app.shared.link.LocalLink
import com.swordfish.lemuroid.common.displayToast
import com.swordfish.lemuroid.lib.core.CoreUpdater
import com.swordfish.lemuroid.lib.core.CoresSelection
import com.swordfish.lemuroid.lib.library.GameSystem
import com.swordfish.lemuroid.lib.library.SystemCoreConfig
import com.swordfish.lemuroid.lib.library.db.entity.Game
import com.swordfish.lemuroid.lib.savesync.SaveSyncManager
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

class GameLauncher(
    private val coresSelection: CoresSelection,
    private val gameLaunchTaskHandler: GameLaunchTaskHandler,
    private val saveSyncManager: SaveSyncManager,
    private val coreUpdater: CoreUpdater,
) {
    @OptIn(DelicateCoroutinesApi::class)
    fun launchGameAsync(
        activity: Activity,
        game: Game,
        loadSave: Boolean,
        leanback: Boolean,
    ): Boolean {
        if (GameProcessLock.isHeldByAnotherProcess(activity.applicationContext)) {
            activity.displayToast(R.string.game_process_another_game_running)
            return false
        }

        GlobalScope.launch {
            if (saveSyncManager.isRemoteSavesEmpty()) {
                withContext(Dispatchers.Main) { confirmEmptySyncFolder(activity, game, loadSave, leanback) }
            } else {
                startGame(activity, game, loadSave, leanback)
            }
        }

        return true
    }

    /** lemuroid-sync: plays [game] linked by cable with [linkedGame], both on this device. */
    fun launchLinkedGamesAsync(
        activity: Activity,
        game: Game,
        linkedGame: Game,
        leanback: Boolean,
    ): Boolean {
        val portrait = activity.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
        return launchLinkAsync(activity, LocalLink.coreConfig(game, portrait)) { coreConfig ->
            BaseGameActivity.launchGame(activity, coreConfig, game, false, leanback, linkedGame = linkedGame)
        }
    }

    /** lemuroid-sync: plays [game] linked by cable with a game on another device. */
    fun launchNetLinkAsync(
        activity: Activity,
        game: Game,
        connection: GameLink.Connection,
        leanback: Boolean,
    ): Boolean {
        val isHost = connection is GameLink.Connection.Host || connection is GameLink.Connection.Online && connection.isHost
        val player = if (isHost) 0 else 1
        return launchLinkAsync(activity, LocalLink.coreConfig(game, false, player)) { coreConfig ->
            BaseGameActivity.launchGame(activity, coreConfig, game, false, leanback, netLink = connection)
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    private fun launchLinkAsync(
        activity: Activity,
        coreConfig: SystemCoreConfig,
        launch: (SystemCoreConfig) -> Unit,
    ): Boolean {
        if (GameProcessLock.isHeldByAnotherProcess(activity.applicationContext)) {
            activity.displayToast(R.string.game_process_another_game_running)
            return false
        }

        GlobalScope.launch {
            val coreAvailable =
                runCatching { coreUpdater.downloadCores(activity.applicationContext, listOf(coreConfig.coreID)) }
                    .onFailure { Timber.e(it, "Unable to download the link cable core") }
                    .isSuccess
            if (!coreAvailable) {
                withContext(Dispatchers.Main) { activity.displayToast(R.string.game_link_local_core_failed) }
                return@launch
            }
            gameLaunchTaskHandler.handleGameStart(activity.applicationContext)
            launch(coreConfig)
        }

        return true
    }

    private suspend fun startGame(
        activity: Activity,
        game: Game,
        loadSave: Boolean,
        leanback: Boolean,
    ) {
        val system = GameSystem.findById(game.systemId)
        val coreConfig = coresSelection.getCoreConfigForSystem(system)
        gameLaunchTaskHandler.handleGameStart(activity.applicationContext)
        BaseGameActivity.launchGame(activity, coreConfig, game, loadSave, leanback)
    }

    /** lemuroid-sync: the synced folder has no saves, so saves from other devices have not arrived yet. */
    @OptIn(DelicateCoroutinesApi::class)
    private fun confirmEmptySyncFolder(
        activity: Activity,
        game: Game,
        loadSave: Boolean,
        leanback: Boolean,
    ) {
        if (activity.isFinishing) return
        AlertDialog.Builder(activity)
            .setTitle(R.string.save_sync_empty_title)
            .setMessage(R.string.save_sync_empty_message)
            .setPositiveButton(R.string.save_sync_empty_play) { _, _ ->
                GlobalScope.launch { startGame(activity, game, loadSave, leanback) }
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                if (activity is ExternalGameLauncherActivity) activity.finish()
            }
            .setCancelable(false)
            .show()
    }
}
