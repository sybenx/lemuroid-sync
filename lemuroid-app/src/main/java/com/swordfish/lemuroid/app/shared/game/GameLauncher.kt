package com.swordfish.lemuroid.app.shared.game

import android.app.Activity
import androidx.appcompat.app.AlertDialog
import com.swordfish.lemuroid.R
import com.swordfish.lemuroid.app.shared.main.GameLaunchTaskHandler
import com.swordfish.lemuroid.common.displayToast
import com.swordfish.lemuroid.lib.core.CoresSelection
import com.swordfish.lemuroid.lib.library.GameSystem
import com.swordfish.lemuroid.lib.library.db.entity.Game
import com.swordfish.lemuroid.lib.storage.SaveStorageManager
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class GameLauncher(
    private val coresSelection: CoresSelection,
    private val gameLaunchTaskHandler: GameLaunchTaskHandler,
    private val saveStorageManager: SaveStorageManager,
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
            if (saveStorageManager.isMirrorSavesFolderEmpty()) {
                withContext(Dispatchers.Main) { confirmEmptyMirrorFolder(activity, game, loadSave, leanback) }
            } else {
                startGame(activity, game, loadSave, leanback)
            }
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

    /** lemuroid-sync: the mirrored saves folder is empty, so saves from other devices have not arrived yet. */
    @OptIn(DelicateCoroutinesApi::class)
    private fun confirmEmptyMirrorFolder(
        activity: Activity,
        game: Game,
        loadSave: Boolean,
        leanback: Boolean,
    ) {
        if (activity.isFinishing) return
        AlertDialog.Builder(activity)
            .setTitle(R.string.save_storage_empty_title)
            .setMessage(R.string.save_storage_empty_message)
            .setPositiveButton(R.string.save_storage_empty_play) { _, _ ->
                GlobalScope.launch { startGame(activity, game, loadSave, leanback) }
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                if (activity is ExternalGameLauncherActivity) activity.finish()
            }
            .setCancelable(false)
            .show()
    }
}
