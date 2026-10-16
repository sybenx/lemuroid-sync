package com.swordfish.lemuroid.ext.feature.savesync

import android.app.Activity
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import com.swordfish.lemuroid.common.kotlin.SharedPreferencesDelegates
import com.swordfish.lemuroid.common.kotlin.writeBytesAtomic
import com.swordfish.lemuroid.ext.R
import com.swordfish.lemuroid.lib.library.CoreID
import com.swordfish.lemuroid.lib.preferences.SharedPreferencesHelper
import com.swordfish.lemuroid.lib.savesync.SaveSyncManager
import com.swordfish.lemuroid.lib.storage.DirectoriesManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.text.SimpleDateFormat
import java.util.Properties

/**
 * Syncs saves and states with a directory chosen by the user through the Storage Access Framework, so that
 * they can be picked up by external backup and sync applications. Cores read and write the private
 * directories, so files are copied in either direction, and only when the other side is newer.
 *
 * Files present on both sides after a sync are remembered. When one of them later disappears from the
 * directory while the local copy is unchanged, it was deleted or renamed by another device, so the local
 * copy is moved to a trash folder instead of being copied back.
 */
class SaveSyncManagerImpl(
    private val appContext: Context,
    private val directoriesManager: DirectoriesManager,
) : SaveSyncManager() {
    private val sharedPreferences = SharedPreferencesHelper.getSharedPreferences(appContext)

    private var lastSyncTimestamp: Long by SharedPreferencesDelegates.LongDelegate(
        sharedPreferences,
        appContext.getString(com.swordfish.lemuroid.lib.R.string.pref_key_last_save_sync),
        0L,
    )

    override fun getProvider(): String = appContext.getString(R.string.external_directory_provider)

    override fun getSettingsActivity(): Class<out Activity> = ActivateExternalDirectoryActivity::class.java

    override fun isSupported(): Boolean = true

    override fun isConfigured(): Boolean = getDirectoryUri() != null

    override fun requiresNetwork(): Boolean = false

    override fun getLastSyncInfo(): String {
        val dateString =
            if (lastSyncTimestamp > 0) {
                SimpleDateFormat.getDateTimeInstance().format(lastSyncTimestamp)
            } else {
                "-"
            }
        return appContext.getString(R.string.external_directory_last_sync_completed, dateString)
    }

    override fun getConfigInfo(): String {
        val name = getDirectoryUri()?.let { getDirectoryName(it) }
        return if (name != null) {
            appContext.getString(R.string.external_directory_connected_summary, name)
        } else {
            appContext.getString(R.string.external_directory_connected_none_summary)
        }
    }

    override suspend fun sync(cores: Set<CoreID>): Unit =
        withContext(Dispatchers.IO) {
            synchronized(SYNC_LOCK) {
                val saveSyncResult =
                    runCatching {
                        performSaveSyncForCores(cores)
                    }

                saveSyncResult.onFailure {
                    Timber.e(it, "Error while performing save sync.")
                }
            }
        }

    private fun performSaveSyncForCores(cores: Set<CoreID>) {
        val tree = RemoteTree(getDirectoryUri() ?: return, loadSyncedFiles())

        tree.syncDirectory(directoriesManager.getSavesDirectory(), listOf(SAVES_DIRECTORY))

        cores.forEach {
            tree.syncDirectory(
                File(directoriesManager.getStatesDirectory(), it.coreName),
                listOf(STATES_DIRECTORY, it.coreName),
            )
            tree.syncDirectory(
                File(directoriesManager.getStatesPreviewDirectory(), it.coreName),
                listOf(STATE_PREVIEWS_DIRECTORY, it.coreName),
            )
        }

        tree.syncedFiles.storeTo(syncedFilesFile())
        lastSyncTimestamp = System.currentTimeMillis()
    }

    private fun syncedFilesFile() = File(appContext.filesDir, SYNCED_FILES_NAME)

    /** Remote path (e.g. "saves/dsperate/Game.sav") to the local modification time after the last sync. */
    private fun loadSyncedFiles(): Properties {
        val properties = Properties()
        runCatching { syncedFilesFile().takeIf { it.isFile }?.inputStream()?.use { properties.load(it) } }
            .onFailure { Timber.e(it, "Unable to read the synced files list.") }
        return properties
    }

    private fun Properties.storeTo(file: File) {
        val temp = File(file.parentFile, file.name + ".new")
        temp.outputStream().use { store(it, null) }
        temp.renameTo(file)
    }

    private fun moveToTrash(
        file: File,
        path: String,
    ) {
        val trash = File(appContext.getExternalFilesDir(null), TRASH_DIRECTORY)
        val target = File(trash, "${System.currentTimeMillis()}/$path")
        target.parentFile?.mkdirs()
        if (file.renameTo(target)) {
            Timber.i("Moved $path to the trash, since it was deleted from the sync directory.")
        } else {
            Timber.w("Unable to move $path to the trash.")
        }
    }

    override fun computeSavesSpace() = getSizeHumanReadable(directoriesManager.getSavesDirectory())

    override fun computeStatesSpace(core: CoreID) =
        getSizeHumanReadable(File(directoriesManager.getStatesDirectory(), core.coreName))

    private fun getSizeHumanReadable(directory: File): String {
        val size =
            directory.walkBottomUp()
                .fold(0L) { acc, file -> acc + file.length() }
        return android.text.format.Formatter.formatShortFileSize(appContext, size)
    }

    /** The selected directory, as long as we still hold read and write access to it. */
    private fun getDirectoryUri(): Uri? {
        val uri =
            sharedPreferences.getString(directoryPreferenceKey(appContext), null)
                ?.let { Uri.parse(it) }
                ?: return null

        val hasPermission =
            appContext.contentResolver.persistedUriPermissions.any {
                it.uri == uri && it.isReadPermission && it.isWritePermission
            }

        return uri.takeIf { hasPermission }
    }

    private fun getDirectoryName(treeUri: Uri): String? {
        return runCatching {
            val rootUri =
                DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))
            appContext.contentResolver.query(rootUri, arrayOf(Document.COLUMN_DISPLAY_NAME), null, null, null)
                ?.use { if (it.moveToFirst()) it.getString(0) else null }
        }.getOrNull()
    }

    private data class RemoteEntry(
        val uri: Uri,
        val name: String,
        val isDirectory: Boolean,
        val lastModified: Long,
    )

    /**
     * Talks to DocumentsContract directly, since DocumentFile issues a separate query for every attribute.
     * Directory listings are cached for the lifetime of a single sync.
     */
    private inner class RemoteTree(private val treeUri: Uri, val syncedFiles: Properties) {
        private val contentResolver = appContext.contentResolver
        private val childrenCache = mutableMapOf<Uri, List<RemoteEntry>>()

        private val rootUri: Uri =
            DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))

        /** Two-way sync of [localDir] with the remote directory at [segments], e.g. ["states", "snes9x"]. */
        fun syncDirectory(
            localDir: File,
            segments: List<String>,
        ) {
            val remoteDir = resolveDirectory(segments) ?: return
            val prefix = segments.joinToString("/", postfix = "/")
            localDir.mkdirs()
            trashRemotelyDeletedFiles(remoteDir, localDir, prefix)
            pullDirectory(remoteDir, localDir)
            pushDirectory(localDir, remoteDir)
            rememberSyncedFiles(remoteDir, localDir, prefix)
        }

        private fun trashRemotelyDeletedFiles(
            remoteDir: Uri,
            localDir: File,
            prefix: String,
        ) {
            val previous = syncedFiles.stringPropertyNames().filter { it.startsWith(prefix) }
            if (previous.isEmpty()) return

            // An empty listing more likely means the provider failed than that every file was deleted.
            val remoteFiles = listRemoteFiles(remoteDir)
            if (remoteFiles.isEmpty()) return

            val deleted =
                previous.filter { path ->
                    val relativePath = path.removePrefix(prefix)
                    val localFile = File(localDir, relativePath)
                    relativePath !in remoteFiles &&
                        localFile.isFile &&
                        localFile.lastModified().toString() == syncedFiles.getProperty(path)
                }
            if (deleted.size > MAX_DELETIONS_PER_SYNC) {
                Timber.w("${deleted.size} files are missing from $prefix, skipping deletions.")
                return
            }
            deleted.forEach { path ->
                moveToTrash(File(localDir, path.removePrefix(prefix)), path)
                syncedFiles.remove(path)
            }
        }

        private fun rememberSyncedFiles(
            remoteDir: Uri,
            localDir: File,
            prefix: String,
        ) {
            childrenCache.clear()
            val remoteFiles = listRemoteFiles(remoteDir)
            syncedFiles.stringPropertyNames().filter { it.startsWith(prefix) }.forEach { syncedFiles.remove(it) }
            localDir.walkTopDown()
                .filter { it.isFile }
                .forEach { localFile ->
                    val relativePath = localFile.relativeTo(localDir).invariantSeparatorsPath
                    if (relativePath in remoteFiles) {
                        syncedFiles.setProperty(prefix + relativePath, localFile.lastModified().toString())
                    }
                }
        }

        /** Relative paths of the files below [remoteDir], skipping the entries the sync ignores. */
        private fun listRemoteFiles(
            remoteDir: Uri,
            parentPath: String = "",
        ): Set<String> {
            return listChildren(remoteDir)
                .filter { isSafeFileName(it.name) && !isIgnoredFile(it.name) }
                .flatMap { child ->
                    val path = parentPath + child.name
                    if (child.isDirectory) listRemoteFiles(child.uri, "$path/") else listOf(path)
                }
                .toSet()
        }

        private fun listChildren(parent: Uri): List<RemoteEntry> {
            childrenCache[parent]?.let { return it }

            val childrenUri =
                DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, DocumentsContract.getDocumentId(parent))
            val projection =
                arrayOf(
                    Document.COLUMN_DOCUMENT_ID,
                    Document.COLUMN_DISPLAY_NAME,
                    Document.COLUMN_MIME_TYPE,
                    Document.COLUMN_LAST_MODIFIED,
                )
            val children =
                contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                    // Some providers do not return columns in projection order, so look them up by name.
                    val idIndex = cursor.getColumnIndexOrThrow(Document.COLUMN_DOCUMENT_ID)
                    val nameIndex = cursor.getColumnIndexOrThrow(Document.COLUMN_DISPLAY_NAME)
                    val mimeIndex = cursor.getColumnIndexOrThrow(Document.COLUMN_MIME_TYPE)
                    val modifiedIndex = cursor.getColumnIndexOrThrow(Document.COLUMN_LAST_MODIFIED)
                    buildList {
                        while (cursor.moveToNext()) {
                            val name = cursor.getString(nameIndex) ?: continue
                            val documentId = cursor.getString(idIndex)
                            val lastModified = if (cursor.isNull(modifiedIndex)) 0L else cursor.getLong(modifiedIndex)
                            val entry =
                                RemoteEntry(
                                    uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId),
                                    name = name,
                                    isDirectory = cursor.getString(mimeIndex) == Document.MIME_TYPE_DIR,
                                    lastModified = lastModified,
                                )
                            add(entry)
                        }
                    }
                } ?: emptyList()

            childrenCache[parent] = children
            return children
        }

        private fun resolveDirectory(segments: List<String>): Uri? {
            return segments.fold(rootUri as Uri?) { current, segment ->
                current ?: return null
                listChildren(current).firstOrNull { it.name == segment && it.isDirectory }?.uri
                    ?: createDocument(current, Document.MIME_TYPE_DIR, segment)
            }
        }

        private fun pushDirectory(
            localDir: File,
            remoteDir: Uri,
        ) {
            val remoteChildren = listChildren(remoteDir).associateBy { it.name }
            localDir.listFiles()?.forEach { localChild ->
                if (isIgnoredFile(localChild.name)) return@forEach
                val remoteChild = remoteChildren[localChild.name]
                if (localChild.isDirectory) {
                    val remoteChildDir =
                        remoteChild?.takeIf { it.isDirectory }?.uri
                            ?: createDocument(remoteDir, Document.MIME_TYPE_DIR, localChild.name)
                            ?: return@forEach
                    pushDirectory(localChild, remoteChildDir)
                } else {
                    pushFile(localChild, remoteDir, remoteChild?.takeIf { !it.isDirectory })
                }
            }
        }

        private fun pullDirectory(
            remoteDir: Uri,
            localDir: File,
        ) {
            listChildren(remoteDir).forEach { remoteChild ->
                if (!isSafeFileName(remoteChild.name) || isIgnoredFile(remoteChild.name)) return@forEach
                val localChild = File(localDir, remoteChild.name)
                if (remoteChild.isDirectory) {
                    localChild.mkdirs()
                    pullDirectory(remoteChild.uri, localChild)
                } else {
                    pullFile(remoteChild, localChild)
                }
            }
        }

        private fun pushFile(
            localFile: File,
            remoteDir: Uri,
            existing: RemoteEntry?,
        ) {
            if (!localFile.isFile) return
            if (existing != null && existing.lastModified >= localFile.lastModified()) return
            // A save the game never wrote to carries no progress. Copying it would make it the newest
            // version everywhere and replace a real save coming from another device.
            if (isBlankFile(localFile)) return

            val target = existing?.uri ?: createDocument(remoteDir, MIME_TYPE_BINARY, localFile.name) ?: return

            // "wt" is required, since plain "w" does not truncate on some providers. If the stream cannot be
            // opened, return before aligning timestamps so that the file is retried at the next sync.
            val outputStream = contentResolver.openOutputStream(target, "wt") ?: return
            outputStream.use { localFile.inputStream().use { input -> input.copyTo(it) } }

            // Providers set their own modification time. Align the local one so the file is not copied back.
            queryLastModified(target)?.let { localFile.setLastModified(it) }
        }

        private fun pullFile(
            remote: RemoteEntry,
            localFile: File,
        ) {
            val localIsBlank = isBlankFile(localFile)
            if (localFile.exists() && remote.lastModified <= localFile.lastModified() && !localIsBlank) return

            val data = contentResolver.openInputStream(remote.uri)?.use { it.readBytes() } ?: return
            // Never replace anything with a blank save, while a blank local save gives way to any real one.
            if (isBlank(data)) return
            localFile.parentFile?.mkdirs()
            localFile.writeBytesAtomic(data)

            if (remote.lastModified > 0) {
                localFile.setLastModified(remote.lastModified)
            }
        }

        private fun createDocument(
            parent: Uri,
            mimeType: String,
            name: String,
        ): Uri? {
            childrenCache.remove(parent)
            return DocumentsContract.createDocument(contentResolver, parent, mimeType, name)
        }

        private fun queryLastModified(uri: Uri): Long? {
            return contentResolver.query(uri, arrayOf(Document.COLUMN_LAST_MODIFIED), null, null, null)
                ?.use { if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else null }
                ?.takeIf { it > 0 }
        }
    }

    private fun isBlank(data: ByteArray): Boolean {
        return data.isEmpty() || data.all { it == data[0] }
    }

    /** Only save RAM sized files are inspected. States are compressed and never uniform. */
    private fun isBlankFile(file: File): Boolean {
        return file.isFile && file.length() <= BLANK_CHECK_MAX_BYTES && isBlank(file.readBytes())
    }

    private fun isSafeFileName(name: String): Boolean {
        return name.isNotEmpty() && name != "." && name != ".." && !name.contains('/')
    }

    /**
     * Skips AtomicFile leftovers, hidden files (e.g. the metadata folders of sync applications) and
     * Syncthing conflict copies.
     */
    private fun isIgnoredFile(name: String): Boolean {
        return name.endsWith(".new") ||
            name.endsWith(".bak") ||
            name.startsWith(".") ||
            name.contains(".sync-conflict-")
    }

    companion object {
        private val SYNC_LOCK = Object()

        private const val SAVES_DIRECTORY = "saves"
        private const val STATES_DIRECTORY = "states"
        private const val STATE_PREVIEWS_DIRECTORY = "state-previews"
        private const val MIME_TYPE_BINARY = "application/octet-stream"

        private const val BLANK_CHECK_MAX_BYTES = 2L * 1024 * 1024

        private const val SYNCED_FILES_NAME = "external-directory-synced-files.properties"
        private const val TRASH_DIRECTORY = "save-sync-trash"

        // A larger batch is more likely a broken directory than files deleted on purpose.
        private const val MAX_DELETIONS_PER_SYNC = 10

        fun directoryPreferenceKey(context: Context): String {
            return context.getString(com.swordfish.lemuroid.lib.R.string.pref_key_save_sync_external_directory)
        }
    }
}
