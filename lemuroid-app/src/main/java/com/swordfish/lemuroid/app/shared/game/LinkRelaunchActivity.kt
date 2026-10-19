package com.swordfish.lemuroid.app.shared.game

import android.content.Context
import android.content.Intent
import android.os.Bundle
import com.swordfish.lemuroid.R
import com.swordfish.lemuroid.app.mobile.feature.link.LinkCableActivity
import com.swordfish.lemuroid.app.shared.ImmersiveActivity
import com.swordfish.lemuroid.app.shared.main.GameLaunchTaskHandler
import com.swordfish.lemuroid.common.coroutines.safeLaunch
import com.swordfish.lemuroid.common.kotlin.serializable
import com.swordfish.lemuroid.lib.library.db.entity.Game
import kotlinx.coroutines.GlobalScope
import javax.inject.Inject

/**
 * lemuroid-sync: starts a link picked in a game's pause menu, once that game has saved and closed. The game
 * opens this screen itself on its way out, so the link starts however the game was opened (library, home
 * screen shortcut). It runs in the main process, shows the usual loading screen, and closes after the
 * linked session.
 */
class LinkRelaunchActivity : ImmersiveActivity() {
    @Inject
    lateinit var gameLauncher: GameLauncher

    @Inject
    lateinit var gameLaunchTaskHandler: GameLaunchTaskHandler

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_loading)
        if (savedInstanceState != null) return

        val game = intent.serializable<Game>(EXTRA_GAME)
        val link = intent.serializable<LinkCableActivity.Result>(EXTRA_LINK)
        if (game == null || link == null) {
            finish()
            return
        }

        val started =
            when (link) {
                is LinkCableActivity.Result.WithGame ->
                    gameLauncher.launchLinkedGamesAsync(this, game, link.game, false) { finish() }
                is LinkCableActivity.Result.WithFriend ->
                    gameLauncher.launchNetLinkAsync(this, game, link.connection, false) { finish() }
            }
        if (!started) finish()
    }

    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?,
    ) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != BaseGameActivity.REQUEST_PLAY_GAME) return
        GlobalScope.safeLaunch {
            gameLaunchTaskHandler.handleGameFinish(false, this@LinkRelaunchActivity, resultCode, data)
            finish()
        }
    }

    companion object {
        private const val EXTRA_GAME = "EXTRA_GAME"
        private const val EXTRA_LINK = "EXTRA_LINK"

        fun intent(
            context: Context,
            game: Game,
            link: LinkCableActivity.Result,
        ): Intent {
            return Intent(context, LinkRelaunchActivity::class.java)
                .putExtra(EXTRA_GAME, game)
                .putExtra(EXTRA_LINK, link)
        }
    }
}
