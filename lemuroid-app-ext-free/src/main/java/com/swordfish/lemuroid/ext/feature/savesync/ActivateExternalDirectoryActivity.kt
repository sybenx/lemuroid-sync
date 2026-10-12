package com.swordfish.lemuroid.ext.feature.savesync

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import com.swordfish.lemuroid.ext.R
import com.swordfish.lemuroid.lib.preferences.SharedPreferencesHelper

/**
 * Lets the user pick the directory used by [SaveSyncManagerImpl]. This lives in the extension module because
 * getSettingsActivity() has to return a class from here, so it cannot reuse StorageFrameworkPickerLauncher.
 */
class ActivateExternalDirectoryActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (savedInstanceState == null) {
            val intent =
                Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                    this.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    this.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                    this.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                    this.addFlags(Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
                }
            try {
                startActivityForResult(intent, REQUEST_CODE_PICK_FOLDER)
            } catch (e: Exception) {
                Toast.makeText(this, R.string.external_directory_picker_not_found, Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        resultData: Intent?,
    ) {
        super.onActivityResult(requestCode, resultCode, resultData)

        val newValue = resultData?.data
        if (requestCode == REQUEST_CODE_PICK_FOLDER && resultCode == Activity.RESULT_OK && newValue != null) {
            updateDirectory(newValue)
        }
        finish()
    }

    private fun updateDirectory(uri: Uri) {
        val readWriteFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        contentResolver.takePersistableUriPermission(uri, readWriteFlags)

        val sharedPreferences = SharedPreferencesHelper.getSharedPreferences(this)
        val preferenceKey = SaveSyncManagerImpl.directoryPreferenceKey(this)
        val previous = sharedPreferences.getString(preferenceKey, null)?.let { Uri.parse(it) }

        if (previous != null && previous != uri) {
            // The roms directory might be the same tree, in which case it still needs read access.
            val sharedWithRoms = previous == getRomsDirectoryUri()
            val flags = if (sharedWithRoms) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else readWriteFlags
            runCatching { contentResolver.releasePersistableUriPermission(previous, flags) }
        }

        sharedPreferences.edit().putString(preferenceKey, uri.toString()).commit()
    }

    private fun getRomsDirectoryUri(): Uri? {
        return SharedPreferencesHelper.getLegacySharedPreferences(this)
            .getString(getString(com.swordfish.lemuroid.lib.R.string.pref_key_extenral_folder), null)
            ?.let { Uri.parse(it) }
    }

    companion object {
        private const val REQUEST_CODE_PICK_FOLDER = 1
    }
}
