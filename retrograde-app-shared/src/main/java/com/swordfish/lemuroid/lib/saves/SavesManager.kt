package com.swordfish.lemuroid.lib.saves

import com.swordfish.lemuroid.common.kotlin.runCatchingWithRetry
import com.swordfish.lemuroid.common.kotlin.writeBytesAtomic
import com.swordfish.lemuroid.lib.library.SystemCoreConfig
import com.swordfish.lemuroid.lib.library.SystemID
import com.swordfish.lemuroid.lib.library.db.entity.Game
import com.swordfish.lemuroid.lib.saves.migrators.getSavesMigrator
import com.swordfish.lemuroid.lib.storage.DirectoriesManager
import com.swordfish.lemuroid.lib.storage.SaveStorageManager
import dagger.Lazy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class SavesManager(
    private val directoriesManager: DirectoriesManager,
    private val saveStorageManager: Lazy<SaveStorageManager>,
) {
    suspend fun getSaveRAM(
        game: Game,
        systemCoreConfig: SystemCoreConfig,
    ): ByteArray? {
        return withContext(Dispatchers.IO) {
            val result =
                runCatchingWithRetry(FILE_ACCESS_RETRIES) {
                    val saveFile = getSaveRAMFile(game)
                    val legacyFile = getLegacySaveRAMFile(game)
                    saveStorageManager.get().pull(*listOfNotNull(saveFile, legacyFile).toTypedArray())
                    if (saveFile.exists() && saveFile.length() > 0) {
                        saveFile.readBytes()
                    } else if (legacyFile != null && legacyFile.exists() && legacyFile.length() > 0) {
                        // DSperate layout just switched on: start from the game's existing .srm save.
                        legacyFile.readBytes()
                    } else {
                        val savesMigrator = systemCoreConfig.getSavesMigrator()
                        savesMigrator?.loadPreviousSaveForGame(game, directoriesManager)
                    }
                }
            result.getOrNull()
        }
    }

    suspend fun setSaveRAM(
        game: Game,
        data: ByteArray,
    ) {
        withContext(Dispatchers.IO) {
            val result =
                runCatchingWithRetry(FILE_ACCESS_RETRIES) {
                    if (data.isEmpty()) {
                        return@runCatchingWithRetry
                    }

                    val saveFile = getSaveRAMFile(game)
                    saveFile.parentFile?.mkdirs()
                    saveFile.writeBytesAtomic(data)
                    saveStorageManager.get().push(saveFile)
                }
            result.getOrNull()
        }
    }

    suspend fun getSaveRAMInfo(game: Game): SaveInfo {
        return withContext(Dispatchers.IO) {
            val saveFile = getSaveRAMFile(game)
            val fileExists = saveFile.exists() && saveFile.length() > 0
            SaveInfo(fileExists, saveFile.lastModified())
        }
    }

    /** The save RAM file for [game], honoring the DSperate layout for DS games when it is enabled. */
    fun getSaveRAMFile(game: Game): File {
        return File(directoriesManager.getSavesDirectory(), getSaveRAMFileName(game))
    }

    /** The file the current save RAM was read from: the DSperate one, or the legacy .srm until it is first written. */
    fun getLoadedSaveRAMFile(game: Game): File {
        val saveFile = getSaveRAMFile(game)
        val legacyFile = getLegacySaveRAMFile(game)
        return if (!saveFile.exists() && legacyFile != null && legacyFile.exists()) legacyFile else saveFile
    }

    /** The plain "<rom>.srm" file a DS game used before the DSperate layout was enabled, if it differs. */
    private fun getLegacySaveRAMFile(game: Game): File? {
        if (!usesDsperateLayout(game)) return null
        return File(directoriesManager.getSavesDirectory(), "${game.fileName.substringBeforeLast(".")}.srm")
    }

    private fun usesDsperateLayout(game: Game): Boolean {
        return game.systemId == SystemID.NDS.dbname && saveStorageManager.get().isDsperateLayoutEnabled()
    }

    /** This name should make it compatible with RetroArch so that users can freely sync saves across the two application. */
    private fun getSaveRAMFileName(game: Game): String {
        val baseName = game.fileName.substringBeforeLast(".")
        return if (usesDsperateLayout(game)) "$DSPERATE_DIRECTORY/$baseName.sav" else "$baseName.srm"
    }

    companion object {
        private const val FILE_ACCESS_RETRIES = 3
        private const val DSPERATE_DIRECTORY = "dsperate"
    }
}
