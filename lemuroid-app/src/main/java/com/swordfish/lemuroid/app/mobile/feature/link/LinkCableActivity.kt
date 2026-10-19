package com.swordfish.lemuroid.app.mobile.feature.link

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.swordfish.lemuroid.R
import com.swordfish.lemuroid.app.mobile.feature.gamemenu.link.GameMenuLinkInvites
import com.swordfish.lemuroid.app.mobile.feature.gamemenu.link.GameMenuLinkScreen
import com.swordfish.lemuroid.app.mobile.feature.gamemenu.link.GameMenuLinkViewModel
import com.swordfish.lemuroid.app.mobile.shared.compose.ui.AppTheme
import com.swordfish.lemuroid.app.mobile.shared.compose.ui.LemuroidGameTexts
import com.swordfish.lemuroid.app.mobile.shared.compose.ui.LemuroidSmallGameImage
import com.swordfish.lemuroid.app.shared.link.GameLink
import com.swordfish.lemuroid.app.shared.link.LinkCompatibility
import com.swordfish.lemuroid.app.shared.link.LocalLink
import com.swordfish.lemuroid.common.kotlin.serializable
import com.swordfish.lemuroid.lib.android.RetrogradeComponentActivity
import com.swordfish.lemuroid.lib.library.db.RetrogradeDatabase
import com.swordfish.lemuroid.lib.library.db.entity.Game
import java.io.Serializable
import javax.inject.Inject

/**
 * lemuroid-sync: the Link Cable page. "My games" links two of this player's games on this device,
 * "Friends" links with a player on another device. Opened from a game's long press menu, or from the
 * pause menu, where linking first closes the running game, so every choice is confirmed.
 */
class LinkCableActivity : RetrogradeComponentActivity() {
    @Inject
    lateinit var retrogradeDb: RetrogradeDatabase

    data class Request(val game: Game, val fromGame: Boolean) : Serializable

    /** What the player picked: one of their games, or a link with another device. */
    sealed interface Result : Serializable {
        data class WithGame(val game: Game) : Result

        data class WithFriend(val connection: GameLink.Connection) : Result
    }

    private enum class Tab { GAMES, FRIENDS }

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val request = intent.serializable<Request>(EXTRA_REQUEST) ?: return finish()
        val game = request.game
        val gameName = game.customName ?: game.title

        setContent {
            AppTheme {
                var tab by rememberSaveable { mutableStateOf(Tab.GAMES) }
                var confirming by rememberSaveable { mutableStateOf<Game?>(null) }
                val restartWarning =
                    if (request.fromGame) stringResource(R.string.game_link_restart_warning, gameName) else null
                val onLinkChanged = { connection: GameLink.Connection ->
                    if (connection !is GameLink.Connection.Unplugged) finishWith(Result.WithFriend(connection))
                }
                // Created with the page, so friends see this player and invites arrive on either tab.
                val friends: GameMenuLinkViewModel =
                    viewModel(
                        factory = GameMenuLinkViewModel.Factory(application, gameName, GameLink.Connection.Unplugged),
                    )

                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = {
                                Column {
                                    Text(stringResource(R.string.game_context_menu_link))
                                    Text(
                                        text = gameName,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            },
                            navigationIcon = {
                                IconButton(onClick = { finish() }) {
                                    Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
                                }
                            },
                        )
                    },
                ) { padding ->
                    Column(modifier = Modifier.fillMaxSize().padding(padding)) {
                        if (request.fromGame) {
                            SaveFirstBanner(gameName)
                        }
                        SingleChoiceSegmentedButtonRow(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        ) {
                            Tab.entries.forEachIndexed { index, entry ->
                                SegmentedButton(
                                    selected = tab == entry,
                                    onClick = { tab = entry },
                                    shape = SegmentedButtonDefaults.itemShape(index, Tab.entries.size),
                                ) {
                                    Text(
                                        stringResource(
                                            if (entry == Tab.GAMES) R.string.game_link_tab_games else R.string.game_link_tab_friends,
                                        ),
                                    )
                                }
                            }
                        }

                        when (tab) {
                            Tab.GAMES -> {
                                GameMenuLinkInvites(friends, onLinkChanged, restartWarning)
                                MyGames(game) { partner ->
                                    if (request.fromGame) confirming = partner else finishWith(Result.WithGame(partner))
                                }
                            }
                            Tab.FRIENDS -> GameMenuLinkScreen(friends, onLinkChanged, restartWarning)
                        }
                    }
                }

                confirming?.let { partner ->
                    AlertDialog(
                        onDismissRequest = { confirming = null },
                        title = {
                            Text(stringResource(R.string.game_link_confirm_title, partner.customName ?: partner.title))
                        },
                        text = { Text(restartWarning ?: "") },
                        confirmButton = {
                            Button(onClick = { finishWith(Result.WithGame(partner)) }) {
                                Text(stringResource(R.string.game_link_confirm))
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { confirming = null }) { Text(stringResource(android.R.string.cancel)) }
                        },
                    )
                }
            }
        }
    }

    @Composable
    private fun SaveFirstBanner(gameName: String) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(12.dp))
                    .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Filled.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Text(
                text = stringResource(R.string.game_link_save_first, gameName),
                modifier = Modifier.padding(start = 12.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }

    /** This player's other Game Boy games, with what each can do linked to [game] where that is certain. */
    @Composable
    private fun MyGames(
        game: Game,
        onPick: (Game) -> Unit,
    ) {
        val partners by produceState<List<Game>?>(null, game) {
            value =
                retrogradeDb.gameDao().asyncSelectBySystems(LocalLink.SYSTEMS).filter { it.id != game.id }
        }

        val loaded = partners ?: return
        if (loaded.isEmpty()) {
            Text(
                text = stringResource(R.string.game_link_local_pick_empty),
                modifier = Modifier.padding(16.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return
        }

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            item {
                Text(
                    text = stringResource(R.string.game_link_games_hint),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            items(loaded, key = { it.id }) { partner ->
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clickable { onPick(partner) }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    LemuroidSmallGameImage(modifier = Modifier.width(48.dp).height(48.dp), game = partner)
                    Column(modifier = Modifier.weight(1f).padding(start = 16.dp)) {
                        LemuroidGameTexts(game = partner)
                        LinkCompatibility.describe(game, partner)?.let { link ->
                            Text(
                                text =
                                    stringResource(
                                        when (link) {
                                            LinkCompatibility.Link.TRADE_AND_BATTLE -> R.string.game_link_hint_trade_battle
                                            LinkCompatibility.Link.TIME_CAPSULE -> R.string.game_link_hint_time_capsule
                                        },
                                    ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }
            item { androidx.compose.foundation.layout.Spacer(modifier = Modifier.size(16.dp)) }
        }
    }

    private fun finishWith(result: Result) {
        setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_RESULT, result))
        finish()
    }

    class Contract : ActivityResultContract<Request, Result?>() {
        override fun createIntent(
            context: Context,
            input: Request,
        ): Intent {
            return Intent(context, LinkCableActivity::class.java).putExtra(EXTRA_REQUEST, input)
        }

        override fun parseResult(
            resultCode: Int,
            intent: Intent?,
        ): Result? {
            if (resultCode != Activity.RESULT_OK) return null
            return intent?.serializable<Result>(EXTRA_RESULT)
        }
    }

    companion object {
        private const val EXTRA_REQUEST = "EXTRA_REQUEST"
        private const val EXTRA_RESULT = "EXTRA_RESULT"
    }
}
