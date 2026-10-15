package com.swordfish.lemuroid.app.mobile.feature.gamemenu.link

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.swordfish.lemuroid.app.shared.link.GameLink
import com.swordfish.lemuroid.app.shared.link.IncomingInvite
import com.swordfish.lemuroid.app.shared.link.InviteResult
import com.swordfish.lemuroid.app.shared.link.LinkIdentity
import com.swordfish.lemuroid.app.shared.link.LinkIdentityStore
import com.swordfish.lemuroid.app.shared.link.LinkNearby
import com.swordfish.lemuroid.app.shared.link.LinkPeer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber

class GameMenuLinkViewModel(
    context: Context,
    gameTitle: String,
    val connection: GameLink.Connection,
) : ViewModel() {
    class Factory(
        private val context: Context,
        private val gameTitle: String,
        private val connection: GameLink.Connection,
    ) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return GameMenuLinkViewModel(context.applicationContext, gameTitle, connection) as T
        }
    }

    sealed interface Outgoing {
        val peer: LinkPeer

        data class Waiting(override val peer: LinkPeer) : Outgoing

        data class Declined(override val peer: LinkPeer) : Outgoing

        data class Busy(override val peer: LinkPeer) : Outgoing

        data class Failed(override val peer: LinkPeer) : Outgoing
    }

    data class State(
        val identity: LinkIdentity,
        val peers: List<LinkPeer> = emptyList(),
        val outgoing: Outgoing? = null,
        val incoming: IncomingInvite? = null,
    )

    private val identityStore = LinkIdentityStore(context)
    private val nearby = LinkNearby(context, identityStore.getDeviceId(), gameTitle)

    private val uiState = MutableStateFlow(State(identityStore.getIdentity()))
    val state: StateFlow<State> = uiState

    private val resultsChannel = Channel<GameLink.Connection>(Channel.CONFLATED)

    /** Emits once when this screen has picked a link and the menu should close. */
    val results: Flow<GameLink.Connection> = resultsChannel.receiveAsFlow()

    private var inviteJob: Job? = null

    init {
        // Nothing to discover while a cable is already plugged in.
        if (connection is GameLink.Connection.Unplugged) {
            startNearby()
        }
    }

    private fun startNearby() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { nearby.start(uiState.value.identity) }
                .onFailure { Timber.e(it, "Unable to start Link Cable discovery") }
        }
        viewModelScope.launch {
            nearby.peers.collect { peers -> uiState.update { it.copy(peers = peers) } }
        }
        viewModelScope.launch {
            nearby.invites.collect { invite ->
                uiState.update { it.copy(incoming = invite) }
                launch {
                    invite.cancelled.collect { cancelled ->
                        if (cancelled) uiState.update { if (it.incoming === invite) it.copy(incoming = null) else it }
                    }
                }
            }
        }
    }

    fun shuffleIdentity() {
        setIdentity(LinkIdentity.random())
    }

    fun setIdentity(identity: LinkIdentity) {
        identityStore.setIdentity(identity)
        uiState.update { it.copy(identity = identity) }
        if (connection is GameLink.Connection.Unplugged) {
            nearby.updateIdentity(identity)
        }
    }

    fun invite(peer: LinkPeer) {
        if (uiState.value.outgoing is Outgoing.Waiting) return
        uiState.update { it.copy(outgoing = Outgoing.Waiting(peer)) }
        inviteJob =
            viewModelScope.launch {
                when (val result = nearby.invite(peer)) {
                    is InviteResult.Accepted -> resultsChannel.send(result.connection)
                    InviteResult.Declined -> uiState.update { it.copy(outgoing = Outgoing.Declined(peer)) }
                    InviteResult.Busy -> uiState.update { it.copy(outgoing = Outgoing.Busy(peer)) }
                    InviteResult.Failed -> uiState.update { it.copy(outgoing = Outgoing.Failed(peer)) }
                }
            }
    }

    fun answerInvite(accept: Boolean) {
        val invite = uiState.value.incoming ?: return
        uiState.update { it.copy(incoming = null) }
        if (invite.cancelled.value) return
        invite.respond(accept)
        if (accept) {
            resultsChannel.trySend(GameLink.Connection.Host(invite.from))
        }
    }

    fun unplug() {
        resultsChannel.trySend(GameLink.Connection.Unplugged)
    }

    override fun onCleared() {
        inviteJob?.cancel()
        uiState.value.incoming?.respond(false)
        nearby.close()
        super.onCleared()
    }
}
