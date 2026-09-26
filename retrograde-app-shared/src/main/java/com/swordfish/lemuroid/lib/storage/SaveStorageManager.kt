package com.swordfish.lemuroid.lib.storage

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import com.swordfish.lemuroid.common.kotlin.writeBytesAtomic
import com.swordfish.lemuroid.lib.R
import com.swordfish.lemuroid.lib.preferences.SharedPreferencesHelper
import dagger.Lazy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File

/**
 * Mirrors saves and states to a user selected directory (Storage Access Framework), so that they can be
 * picked up by external backup/sync solutions. It is opt-in and does nothing unless enabled in settings.
 * Local files remain the source of truth for the emulator; files are copied in either direction only when
 * the other side is newer.
 */
class SaveStorageManager(
    private val appContext: Context,
    private val directoriesManager: DirectoriesManager,
    private val sharedPreferences: Lazy<SharedPreferences>,
) {
    private val mutex = Mutex()

    /** SharedPreferences only keeps weak references to listeners, so we hold them here. */
    private val preferenceListeners = mutableSetOf<SharedPreferences.OnSharedPreferenceChangeListener>()

    private val enabledPreferenceKey = appContext.getString(R.string.pref_key_save_storage_enabled)

    private val preferenceKey = appContext.getString(R.string.pref_key_save_storage_folder)

    fun isEnabled(): Boolean {
        return sharedPreferences.get().getBoolean(enabledPreferenceKey, false)
    }

    fun getCustomDirectoryUri(): Uri? {
        return sharedPreferences.get().getString(preferenceKey, null)
            ?.takeIf { it.isNotBlank() }
            ?.let { Uri.parse(it) }
    }

    fun observeEnabled(): Flow<Boolean> = observePreference(enabledPreferenceKey) { isEnabled() }

    fun observeCustomDirectoryUri(): Flow<Uri?> = observePreference(preferenceKey) { getCustomDirectoryUri() }

    private fun <T> observePreference(
        key: String,
        read: () -> T,
    ): Flow<T> =
        callbackFlow {
            val listener =
                SharedPreferences.OnSharedPreferenceChangeListener { _, changedKey ->
                    if (changedKey == key) trySend(read())
                }
            synchronized(preferenceListeners) { preferenceListeners.add(listener) }
            sharedPreferences.get().registerOnSharedPreferenceChangeListener(listener)
            send(read())
            awaitClose {
                sharedPreferences.get().unregisterOnSharedPreferenceChangeListener(listener)
                synchronized(preferenceListeners) { preferenceListeners.remove(listener) }
            }
        }.distinctUntilChanged()

    suspend fun getCustomDirectoryName(): String? =
        withContext(Dispatchers.IO) {
            val treeUri = getCustomDirectoryUri() ?: return@withContext null
            runCatching {
                val rootUri = rootDocumentUri(treeUri)
                appContext.contentResolver.query(rootUri, arrayOf(Document.COLUMN_DISPLAY_NAME), null, null, null)
                    ?.use { if (it.moveToFirst()) it.getString(0) else null }
            }.getOrNull()
        }

    /** Stores the tree [uri] returned by the SAF picker, persisting its permission and releasing the old one. */
    fun setCustomDirectory(uri: Uri) {
        val contentResolver = appContext.contentResolver
        val readWriteFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION

        contentResolver.takePersistableUriPermission(uri, readWriteFlags)

        val previous = getCustomDirectoryUri()
        if (previous != null && previous != uri) {
            // The roms folder might be the same tree, in which case it still needs the read permission.
            val sharedWithRoms = previous == getRomsDirectoryUri()
            val flags = if (sharedWithRoms) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else readWriteFlags
            runCatching { contentResolver.releasePersistableUriPermission(previous, flags) }
        }

        sharedPreferences.get().edit().putString(preferenceKey, uri.toString()).commit()
    }

    private fun getRomsDirectoryUri(): Uri? {
        return SharedPreferencesHelper.getLegacySharedPreferences(appContext)
            .getString(appContext.getString(R.string.pref_key_extenral_folder), null)
            ?.let { Uri.parse(it) }
    }

    /** Full two-way sync of the saves and states directories. */
    suspend fun syncAll() =
        withCustomDirectory { tree ->
            localRoots().forEach { (name, localDir) ->
                val remoteDir = tree.resolveDirectory(tree.rootUri, listOf(name), create = true) ?: return@forEach
                tree.pullDirectory(remoteDir, localDir)
                tree.pushDirectory(localDir, remoteDir)
            }
        }

    /** Copies each of [localFiles] to the custom directory if the local copy is newer. */
    suspend fun push(vararg localFiles: File) =
        withCustomDirectory { tree ->
            localFiles.forEach { localFile ->
                val segments = remoteSegments(localFile) ?: return@forEach
                val remoteDir = tree.resolveDirectory(tree.rootUri, segments, create = true) ?: return@forEach
                val existing = tree.listChildren(remoteDir).firstOrNull { it.name == localFile.name }
                tree.pushFile(localFile, remoteDir, existing)
            }
        }

    /** Replaces each of [localFiles] with the one in the custom directory if the remote copy is newer. */
    suspend fun pull(vararg localFiles: File) =
        withCustomDirectory { tree ->
            localFiles.forEach { localFile ->
                val segments = remoteSegments(localFile) ?: return@forEach
                val remoteDir = tree.resolveDirectory(tree.rootUri, segments, create = false) ?: return@forEach
                val remote = tree.listChildren(remoteDir).firstOrNull { it.name == localFile.name && !it.isDirectory }
                if (remote != null) {
                    tree.pullFile(remote, localFile)
                }
            }
        }

    private suspend fun withCustomDirectory(block: suspend (RemoteTree) -> Unit) {
        if (!isEnabled()) return
        val treeUri = getCustomDirectoryUri() ?: return
        withContext(Dispatchers.IO) {
            mutex.withLock {
                try {
                    block(RemoteTree(treeUri))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.e(e, "Failed to sync saves with custom directory: %s", treeUri)
                }
            }
        }
    }

    private fun localRoots(): List<Pair<String, File>> =
        listOf(
            SAVES_DIRECTORY to directoriesManager.getSavesDirectory(),
            STATES_DIRECTORY to directoriesManager.getStatesDirectory(),
        )

    /** Returns the remote directory path for [localFile], e.g. ["states", "snes9x"]. */
    private fun remoteSegments(localFile: File): List<String>? {
        return localRoots().firstNotNullOfOrNull { (name, localDir) ->
            val relativeParent = localFile.parentFile?.relativeToOrNull(localDir) ?: return@firstNotNullOfOrNull null
            if (relativeParent.path.startsWith("..")) return@firstNotNullOfOrNull null
            listOf(name) + relativeParent.path.split(File.separatorChar).filter { it.isNotEmpty() }
        }
    }

    private fun rootDocumentUri(treeUri: Uri): Uri =
        DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))

    private data class RemoteEntry(
        val uri: Uri,
        val name: String,
        val isDirectory: Boolean,
        val lastModified: Long,
    )

    /** Talks to DocumentsContract directly: DocumentFile issues a separate query for every attribute. */
    private inner class RemoteTree(private val treeUri: Uri) {
        private val contentResolver = appContext.contentResolver

        val rootUri: Uri = rootDocumentUri(treeUri)

        fun listChildren(parent: Uri): List<RemoteEntry> {
            val childrenUri =
                DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, DocumentsContract.getDocumentId(parent))
            val projection =
                arrayOf(
                    Document.COLUMN_DOCUMENT_ID,
                    Document.COLUMN_DISPLAY_NAME,
                    Document.COLUMN_MIME_TYPE,
                    Document.COLUMN_LAST_MODIFIED,
                )
            return contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        val name = cursor.getString(1) ?: continue
                        val entry =
                            RemoteEntry(
                                uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, cursor.getString(0)),
                                name = name,
                                isDirectory = cursor.getString(2) == Document.MIME_TYPE_DIR,
                                lastModified = if (cursor.isNull(3)) 0L else cursor.getLong(3),
                            )
                        add(entry)
                    }
                }
            } ?: emptyList()
        }

        fun resolveDirectory(
            from: Uri,
            segments: List<String>,
            create: Boolean,
        ): Uri? {
            return segments.fold(from as Uri?) { current, segment ->
                current ?: return null
                listChildren(current).firstOrNull { it.name == segment && it.isDirectory }?.uri
                    ?: if (create) createDocument(current, Document.MIME_TYPE_DIR, segment) else null
            }
        }

        fun pushDirectory(
            localDir: File,
            remoteDir: Uri,
        ) {
            val remoteChildren = listChildren(remoteDir).associateBy { it.name }
            localDir.listFiles()?.forEach { localChild ->
                if (isTemporaryFile(localChild.name)) return@forEach
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

        fun pullDirectory(
            remoteDir: Uri,
            localDir: File,
        ) {
            listChildren(remoteDir).forEach { remoteChild ->
                if (!isSafeFileName(remoteChild.name) || isTemporaryFile(remoteChild.name)) return@forEach
                val localChild = File(localDir, remoteChild.name)
                if (remoteChild.isDirectory) {
                    localChild.mkdirs()
                    pullDirectory(remoteChild.uri, localChild)
                } else {
                    pullFile(remoteChild, localChild)
                }
            }
        }

        fun pushFile(
            localFile: File,
            remoteDir: Uri,
            existing: RemoteEntry?,
        ) {
            if (!localFile.isFile) return
            if (existing != null && existing.lastModified >= localFile.lastModified()) return

            val target = existing?.uri ?: createDocument(remoteDir, MIME_TYPE_BINARY, localFile.name) ?: return

            // "wt" is required: plain "w" does not truncate on some providers, leaving stale trailing bytes.
            contentResolver.openOutputStream(target, "wt")?.use { outputStream ->
                localFile.inputStream().use { it.copyTo(outputStream) }
            }

            // Align timestamps so that the next sync does not copy the file back.
            queryLastModified(target)?.let { localFile.setLastModified(it) }
        }

        fun pullFile(
            remote: RemoteEntry,
            localFile: File,
        ) {
            if (localFile.exists() && remote.lastModified <= localFile.lastModified()) return

            val data = contentResolver.openInputStream(remote.uri)?.use { it.readBytes() } ?: return
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
            return DocumentsContract.createDocument(contentResolver, parent, mimeType, name)
        }

        private fun queryLastModified(uri: Uri): Long? {
            return contentResolver.query(uri, arrayOf(Document.COLUMN_LAST_MODIFIED), null, null, null)
                ?.use { if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else null }
                ?.takeIf { it > 0 }
        }
    }

    private fun isSafeFileName(name: String): Boolean {
        return name.isNotEmpty() && name != "." && name != ".." && !name.contains('/')
    }

    /** Leftovers of AtomicFile writes, which should never be mirrored. */
    private fun isTemporaryFile(name: String): Boolean {
        return name.endsWith(".new") || name.endsWith(".bak")
    }

    companion object {
        private const val SAVES_DIRECTORY = "saves"
        private const val STATES_DIRECTORY = "states"
        private const val MIME_TYPE_BINARY = "application/octet-stream"
    }
}
