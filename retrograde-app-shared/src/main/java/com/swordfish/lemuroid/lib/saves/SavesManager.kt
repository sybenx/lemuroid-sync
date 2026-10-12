package com.swordfish.lemuroid.lib.saves

import android.content.Context
import android.content.SharedPreferences
import com.swordfish.lemuroid.common.kotlin.runCatchingWithRetry
import com.swordfish.lemuroid.common.kotlin.writeBytesAtomic
import com.swordfish.lemuroid.lib.R
import com.swordfish.lemuroid.lib.library.SystemCoreConfig
import com.swordfish.lemuroid.lib.library.SystemID
import com.swordfish.lemuroid.lib.library.db.entity.Game
import com.swordfish.lemuroid.lib.saves.migrators.getSavesMigrator
import com.swordfish.lemuroid.lib.storage.DirectoriesManager
import dagger.Lazy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class SavesManager(
    private val appContext: Context,
    private val directoriesManager: DirectoriesManager,
    private val sharedPreferences: Lazy<SharedPreferences>,
) {
    suspend fun getSaveRAM(
        game: Game,
        systemCoreConfig: SystemCoreConfig,
    ): ByteArray? {
        return withContext(Dispatchers.IO) {
            val result =
                runCatchingWithRetry(FILE_ACCESS_RETRIES) {
                    val saveFile = getLoadedSaveRAMFile(game)
                    if (saveFile.exists() && saveFile.length() > 0) {
                        saveFile.readBytes()
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

                    val saveFile = getSaveRAMCandidates(game).first()

                    // Keep the timestamp of an unchanged save, so that sync only sees real progress as newer.
                    if (saveFile.length() == data.size.toLong() && saveFile.readBytes().contentEquals(data)) {
                        return@runCatchingWithRetry
                    }

                    saveFile.parentFile?.mkdirs()
                    saveFile.writeBytesAtomic(data)
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

    /**
     * lemuroid-sync: the file save RAM is read from. DS games may have saves in any of the DS save
     * locations, so the newest non-blank one wins and switching the setting never loads a stale copy.
     */
    private fun getLoadedSaveRAMFile(game: Game): File {
        val candidates = getSaveRAMCandidates(game)
        return candidates
            .filter { it.isFile && it.length() > 0 }
            .sortedWith(compareBy<File> { isBlankSave(it) }.thenByDescending { it.lastModified() })
            .firstOrNull()
            ?: candidates.first()
    }

    private fun isBlankSave(file: File): Boolean {
        val data = file.readBytes()
        return data.all { it == data[0] }
    }

    /** lemuroid-sync: the file save RAM is written to first, then the other locations a DS save may be in. */
    private fun getSaveRAMCandidates(game: Game): List<File> {
        val savesDirectory = directoriesManager.getSavesDirectory()
        val baseName = game.fileName.substringBeforeLast(".")
        // This name should make it compatible with RetroArch so that users can freely sync saves across the two application.
        val defaultFile = File(savesDirectory, "$baseName.srm")
        if (game.systemId != SystemID.NDS.dbname) return listOf(defaultFile)

        val folderFile = File(savesDirectory, "$DS_DIRECTORY/$baseName.srm")
        val dsperateFile = File(savesDirectory, "$DSPERATE_DIRECTORY/$baseName.sav")
        return when (getDsSaveLocation()) {
            DS_SAVE_LOCATION_FOLDER -> listOf(folderFile, defaultFile, dsperateFile)
            DS_SAVE_LOCATION_DSPERATE -> listOf(dsperateFile, defaultFile, folderFile)
            else -> listOf(defaultFile, folderFile, dsperateFile)
        }
    }

    /** "default" (<rom>.srm), "folder" (nds/<rom>.srm) or "dsperate" (dsperate/<rom>.sav, as on the Miyoo Flip). */
    private fun getDsSaveLocation(): String {
        val preferences = sharedPreferences.get()
        val location = preferences.getString(appContext.getString(R.string.pref_key_ds_save_location), null)
        return when {
            location != null -> location
            // Older Lemuroid Sync builds stored this as an on/off DSperate toggle.
            preferences.getBoolean(appContext.getString(R.string.pref_key_dsperate_ds_saves), false) -> DS_SAVE_LOCATION_DSPERATE
            else -> DS_SAVE_LOCATION_DEFAULT
        }
    }

    companion object {
        private const val FILE_ACCESS_RETRIES = 3
        private const val DS_DIRECTORY = "nds"
        private const val DSPERATE_DIRECTORY = "dsperate"
        private const val DS_SAVE_LOCATION_DEFAULT = "default"
        private const val DS_SAVE_LOCATION_FOLDER = "folder"
        private const val DS_SAVE_LOCATION_DSPERATE = "dsperate"
    }
}
