package com.swordfish.lemuroid.app.shared.settings

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import com.swordfish.lemuroid.R
import com.swordfish.lemuroid.app.utils.android.displayErrorDialog
import com.swordfish.lemuroid.common.coroutines.safeLaunch
import com.swordfish.lemuroid.lib.android.RetrogradeActivity
import com.swordfish.lemuroid.lib.storage.SaveStorageManager
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import javax.inject.Inject

class SaveStorageFolderPickerLauncher : RetrogradeActivity() {
    @Inject
    lateinit var saveStorageManager: SaveStorageManager

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
                showStorageAccessFrameworkNotSupportedDialog()
            }
        }
    }

    private fun showStorageAccessFrameworkNotSupportedDialog() {
        val message = getString(R.string.dialog_saf_save_storage_not_found)
        val actionLabel = getString(R.string.ok)
        displayErrorDialog(message, actionLabel) { finish() }
    }

    @OptIn(DelicateCoroutinesApi::class)
    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        resultData: Intent?,
    ) {
        super.onActivityResult(requestCode, resultCode, resultData)

        val newValue = resultData?.data
        if (requestCode == REQUEST_CODE_PICK_FOLDER && resultCode == Activity.RESULT_OK && newValue != null) {
            // setCustomDirectory() commits synchronously to disk, so keep it off the main thread too.
            GlobalScope.safeLaunch(Dispatchers.IO) {
                saveStorageManager.setCustomDirectory(newValue)
                saveStorageManager.syncAll()
            }
        }
        finish()
    }

    companion object {
        private const val REQUEST_CODE_PICK_FOLDER = 1

        fun pickFolder(context: Context) {
            context.startActivity(Intent(context, SaveStorageFolderPickerLauncher::class.java))
        }
    }
}
