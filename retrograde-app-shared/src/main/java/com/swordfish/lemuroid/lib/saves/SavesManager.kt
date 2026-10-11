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
                    saveStorageManager.get().pull(*getSaveRAMCandidates(game).toTypedArray())
                    val loadedFile = getLoadedSaveRAMFile(game)
                    if (loadedFile.exists() && loadedFile.length() > 0) {
                        loadedFile.readBytes()
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
                    // An unchanged save keeps its timestamp: a synced device then only sees real progress
                    // as newer (and e.g. skips its own auto-resume state only when it should).
                    if (saveFile.isFile && saveFile.length() == data.size.toLong() && saveFile.readBytes().contentEquals(data)) {
                        return@runCatchingWithRetry
                    }
                    saveFile.parentFile?.mkdirs()
                    saveFile.writeBytesAtomic(data)
                    saveStorageManager.get().push(saveFile)
                }
            result.getOrNull()
        }
    }

    suspend fun getSaveRAMInfo(game: Game): SaveInfo {
        return withContext(Dispatchers.IO) {
            val saveFile = getLoadedSaveRAMFile(game)
            val fileExists = saveFile.exists() && saveFile.length() > 0
            SaveInfo(fileExists, saveFile.lastModified())
        }
    }

    /** The file save RAM for [game] is written to, following the DS save location setting. */
    fun getSaveRAMFile(game: Game): File {
        return getSaveRAMCandidates(game).first()
    }

    /**
     * The file the save RAM is read from: the newest non-empty file among every location the game may
     * have used, so switching the DS save location in either direction never picks up a stale copy.
     */
    fun getLoadedSaveRAMFile(game: Game): File {
        val candidates = getSaveRAMCandidates(game)
        return candidates
            .filter { it.isFile && it.length() > 0 }
            .sortedWith(compareBy<File> { isBlankSave(it) }.thenByDescending { it.lastModified() })
            .firstOrNull()
            ?: candidates.first()
    }

    /** A save RAM the game never wrote to (all one byte value) loses to any real save. */
    private fun isBlankSave(file: File): Boolean {
        val data = file.readBytes()
        return data.all { it == data[0] }
    }

    /** The selected save file first, then (for DS games) the other locations it may have lived in. */
    private fun getSaveRAMCandidates(game: Game): List<File> {
        val savesDirectory = directoriesManager.getSavesDirectory()
        val baseName = game.fileName.substringBeforeLast(".")
        // This name should make it compatible with RetroArch so that users can freely sync saves across the two application.
        val defaultFile = File(savesDirectory, "$baseName.srm")
        if (game.systemId != SystemID.NDS.dbname) return listOf(defaultFile)

        val folderFile = File(savesDirectory, "$DS_DIRECTORY/$baseName.srm")
        val dsperateFile = File(savesDirectory, "$DSPERATE_DIRECTORY/$baseName.sav")
        return when (saveStorageManager.get().getDsSaveLocation()) {
            SaveStorageManager.DS_SAVE_LOCATION_FOLDER -> listOf(folderFile, defaultFile, dsperateFile)
            SaveStorageManager.DS_SAVE_LOCATION_DSPERATE -> listOf(dsperateFile, defaultFile, folderFile)
            else -> listOf(defaultFile, folderFile, dsperateFile)
        }
    }

    companion object {
        private const val FILE_ACCESS_RETRIES = 3
        private const val DS_DIRECTORY = "nds"
        private const val DSPERATE_DIRECTORY = "dsperate"
    }
}
