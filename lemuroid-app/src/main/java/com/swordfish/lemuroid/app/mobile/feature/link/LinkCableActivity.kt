package com.swordfish.lemuroid.app.mobile.feature.link

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import com.swordfish.lemuroid.R
import com.swordfish.lemuroid.app.mobile.feature.gamemenu.link.GameMenuLinkScreen
import com.swordfish.lemuroid.app.mobile.feature.gamemenu.link.GameMenuLinkViewModel
import com.swordfish.lemuroid.app.mobile.shared.compose.ui.AppTheme
import com.swordfish.lemuroid.app.shared.link.GameLink
import com.swordfish.lemuroid.common.kotlin.serializable

/**
 * lemuroid-sync: finds a player on another device to link a game with. Both players open it, one invites
 * the other, and the activity returns the connection once the invite is accepted.
 */
class LinkCableActivity : ComponentActivity() {
    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val gameTitle = intent.getStringExtra(EXTRA_GAME_TITLE) ?: ""

        setContent {
            AppTheme {
                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = { Text(stringResource(R.string.game_context_menu_link)) },
                            navigationIcon = {
                                IconButton(onClick = { finish() }) {
                                    Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
                                }
                            },
                        )
                    },
                ) { padding ->
                    Column(modifier = Modifier.fillMaxSize().padding(padding)) {
                        GameMenuLinkScreen(
                            viewModel(
                                factory =
                                    GameMenuLinkViewModel.Factory(
                                        application,
                                        gameTitle,
                                        GameLink.Connection.Unplugged,
                                    ),
                            ),
                            onLinkChanged = { connection ->
                                if (connection !is GameLink.Connection.Unplugged) {
                                    setResult(Activity.RESULT_OK, Intent().putExtra(RESULT_CONNECTION, connection))
                                    finish()
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    /** Opens the screen for a game title and returns the accepted link, or null. */
    class Contract : ActivityResultContract<String, GameLink.Connection?>() {
        override fun createIntent(
            context: Context,
            input: String,
        ): Intent {
            return Intent(context, LinkCableActivity::class.java).putExtra(EXTRA_GAME_TITLE, input)
        }

        override fun parseResult(
            resultCode: Int,
            intent: Intent?,
        ): GameLink.Connection? {
            if (resultCode != Activity.RESULT_OK) return null
            return intent?.serializable<GameLink.Connection>(RESULT_CONNECTION)
        }
    }

    companion object {
        private const val EXTRA_GAME_TITLE = "EXTRA_GAME_TITLE"
        private const val RESULT_CONNECTION = "RESULT_CONNECTION"
    }
}
