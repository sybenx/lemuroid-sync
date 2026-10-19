package com.swordfish.lemuroid.app.mobile.feature.gamemenu.link

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.swordfish.lemuroid.app.shared.link.GameLink
import com.swordfish.lemuroid.app.shared.link.IncomingInvite
import com.swordfish.lemuroid.app.shared.link.InviteResult
import com.swordfish.lemuroid.app.shared.link.LinkFriend
import com.swordfish.lemuroid.app.shared.link.LinkFriendsStore
import com.swordfish.lemuroid.app.shared.link.LinkIdentity
import com.swordfish.lemuroid.app.shared.link.LinkIdentityStore
import com.swordfish.lemuroid.app.shared.link.LinkNearby
import com.swordfish.lemuroid.app.shared.link.LinkPeer
import com.swordfish.lemuroid.app.shared.link.LinkRelay
import com.swordfish.lemuroid.app.shared.link.RelayPeer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
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
        val deviceId: String

        data class Waiting(override val deviceId: String) : Outgoing

        data class Declined(override val deviceId: String) : Outgoing

        data class Busy(override val deviceId: String) : Outgoing

        data class Failed(override val deviceId: String) : Outgoing
    }

    /** A remembered friend, and whether they can be invited right now. */
    data class Friend(
        val friend: LinkFriend,
        val online: RelayPeer?,
        val isNearby: Boolean,
    )

    sealed interface Code {
        data object Loading : Code

        data class Mine(val code: String) : Code

        data object Invalid : Code
    }

    data class State(
        val identity: LinkIdentity,
        val peers: List<LinkPeer> = emptyList(),
        val friends: List<Friend> = emptyList(),
        val relayConnected: Boolean = false,
        val outgoing: Outgoing? = null,
        val incoming: IncomingInvite? = null,
        val code: Code? = null,
    )

    private val identityStore = LinkIdentityStore(context)
    private val friendsStore = LinkFriendsStore(context)
    private val deviceId = identityStore.getDeviceId()
    private val nearby = LinkNearby(context, deviceId, gameTitle)
    private val relay = LinkRelay(deviceId, gameTitle)

    private val uiState = MutableStateFlow(State(identityStore.getIdentity()))
    val state: StateFlow<State> = uiState

    private val storedFriends = MutableStateFlow(friendsStore.getFriends())

    private val resultsChannel = Channel<GameLink.Connection>(Channel.CONFLATED)

    /** Emits once when this screen has picked a link and the menu should close. */
    val results: Flow<GameLink.Connection> = resultsChannel.receiveAsFlow()

    private var inviteJob: Job? = null

    /** Relay invites waiting for an answer here, by the inviter's device id. */
    private val relayInvites = mutableMapOf<String, IncomingInvite>()

    /** Relay sessions this device is waiting for, after inviting or accepting. */
    private val pendingSessions = mutableMapOf<String, CompletableDeferred<GameLink.Connection.Online>>()

    init {
        // Nothing to discover while a cable is already plugged in.
        if (connection is GameLink.Connection.Unplugged) {
            startNearby()
            startRelay()
        }
    }

    private fun startNearby() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { nearby.start(uiState.value.identity) }
                .onFailure { Timber.e(it, "Unable to start Link Cable discovery") }
        }
        viewModelScope.launch {
            nearby.invites.collect { invite -> showIncoming(invite) }
        }
    }

    private fun startRelay() {
        relay.start(uiState.value.identity, storedFriends.value)
        viewModelScope.launch {
            combine(nearby.peers, relay.online, storedFriends, relay.connected) { peers, online, friends, connected ->
                val nearbyIds = peers.map { it.deviceId }.toSet()
                val rows =
                    friends
                        .map { friend -> Friend(friend, online.firstOrNull { it.deviceId == friend.deviceId }, friend.deviceId in nearbyIds) }
                        .sortedWith(compareBy<Friend> { it.online == null && !it.isNearby }.thenBy { it.friend.identity.displayName })
                Triple(peers, rows, connected)
            }.collect { (peers, rows, connected) ->
                uiState.update { it.copy(peers = peers, friends = rows, relayConnected = connected) }
            }
        }
        viewModelScope.launch {
            relay.events.collect { handleRelayEvent(it) }
        }
    }

    private fun handleRelayEvent(event: LinkRelay.Event) {
        when (event) {
            is LinkRelay.Event.Invite -> {
                if (uiState.value.incoming != null || uiState.value.outgoing is Outgoing.Waiting) {
                    relay.answer(event.from.deviceId, false)
                    return
                }
                val invite =
                    IncomingInvite(
                        event.from.identity,
                        event.from.gameTitle,
                        CompletableDeferred(),
                        event.from.deviceId,
                        event.from.addresses,
                    )
                relayInvites[event.from.deviceId] = invite
                showIncoming(invite)
            }
            is LinkRelay.Event.InviteCancelled -> {
                relayInvites.remove(event.fromId)?.let {
                    it.cancelled.value = true
                    uiState.update { state -> if (state.incoming === it) state.copy(incoming = null) else state }
                }
            }
            is LinkRelay.Event.Declined -> finishOutgoing(event.fromId) { Outgoing.Declined(it) }
            is LinkRelay.Event.Unreachable -> finishOutgoing(event.toId) { Outgoing.Failed(it) }
            is LinkRelay.Event.Session -> {
                val online =
                    GameLink.Connection.Online(
                        peer = event.with.identity,
                        session = event.session,
                        isHost = event.isHost,
                        peerAddresses = event.with.addresses,
                    )
                remember(event.with.deviceId, event.with.identity, event.with.addresses)
                pendingSessions.remove(event.with.deviceId)?.complete(online)
            }
            is LinkRelay.Event.Code -> uiState.update { it.copy(code = Code.Mine(event.code)) }
            LinkRelay.Event.CodeInvalid -> uiState.update { it.copy(code = Code.Invalid) }
            is LinkRelay.Event.Friend -> {
                remember(event.friend.deviceId, event.friend.identity, event.friend.addresses)
                uiState.update { it.copy(code = null) }
            }
        }
    }

    private fun showIncoming(invite: IncomingInvite) {
        uiState.update { it.copy(incoming = invite) }
        viewModelScope.launch {
            invite.cancelled.collect { cancelled ->
                if (cancelled) uiState.update { if (it.incoming === invite) it.copy(incoming = null) else it }
            }
        }
    }

    private fun finishOutgoing(
        deviceId: String,
        result: (String) -> Outgoing,
    ) {
        if (uiState.value.outgoing?.deviceId != deviceId) return
        inviteJob?.cancel()
        pendingSessions.remove(deviceId)
        uiState.update { it.copy(outgoing = result(deviceId)) }
    }

    private fun remember(
        friendId: String,
        identity: LinkIdentity,
        addresses: List<String>,
    ) {
        if (friendId.isBlank() || friendId == deviceId) return
        val known = storedFriends.value.firstOrNull { it.deviceId == friendId }
        val merged = (addresses + (known?.addresses ?: emptyList())).distinct().take(MAX_FRIEND_ADDRESSES)
        friendsStore.remember(LinkFriend(friendId, identity, merged))
        storedFriends.value = friendsStore.getFriends()
        relay.updateHello(uiState.value.identity, storedFriends.value)
    }

    fun shuffleIdentity() {
        setIdentity(LinkIdentity.random())
    }

    fun setIdentity(identity: LinkIdentity) {
        identityStore.setIdentity(identity)
        uiState.update { it.copy(identity = identity) }
        if (connection is GameLink.Connection.Unplugged) {
            nearby.updateIdentity(identity)
            relay.updateHello(identity, storedFriends.value)
        }
    }

    /** Invites a player on the same Wi-Fi. */
    fun invite(peer: LinkPeer) {
        if (uiState.value.outgoing is Outgoing.Waiting) return
        uiState.update { it.copy(outgoing = Outgoing.Waiting(peer.deviceId)) }
        inviteJob =
            viewModelScope.launch {
                when (val result = nearby.invite(peer)) {
                    is InviteResult.Accepted -> {
                        remember(peer.deviceId, peer.identity, peer.addresses)
                        resultsChannel.send(result.connection)
                    }
                    InviteResult.Declined -> uiState.update { it.copy(outgoing = Outgoing.Declined(peer.deviceId)) }
                    InviteResult.Busy -> uiState.update { it.copy(outgoing = Outgoing.Busy(peer.deviceId)) }
                    InviteResult.Failed -> uiState.update { it.copy(outgoing = Outgoing.Failed(peer.deviceId)) }
                }
            }
    }

    /** Invites a friend: on the same Wi-Fi if they are nearby, otherwise through the relay. */
    fun invite(friend: Friend) {
        uiState.value.peers.firstOrNull { it.deviceId == friend.friend.deviceId }?.let {
            invite(it)
            return
        }
        if (friend.online == null || uiState.value.outgoing is Outgoing.Waiting) return

        val friendId = friend.friend.deviceId
        uiState.update { it.copy(outgoing = Outgoing.Waiting(friendId)) }
        val session = CompletableDeferred<GameLink.Connection.Online>()
        pendingSessions[friendId] = session
        relay.invite(friendId)
        inviteJob =
            viewModelScope.launch {
                val connection = kotlinx.coroutines.withTimeoutOrNull(INVITE_TIMEOUT_MS) { session.await() }
                if (connection != null) {
                    resultsChannel.send(connection)
                } else {
                    relay.cancelInvite(friendId)
                    finishOutgoing(friendId) { Outgoing.Failed(it) }
                }
            }
    }

    fun answerInvite(accept: Boolean) {
        val invite = uiState.value.incoming ?: return
        uiState.update { it.copy(incoming = null) }
        if (invite.cancelled.value) return

        val relayInvite = relayInvites.entries.firstOrNull { it.value === invite }?.key
        if (relayInvite != null) {
            relayInvites.remove(relayInvite)
            if (accept) {
                val session = CompletableDeferred<GameLink.Connection.Online>()
                pendingSessions[relayInvite] = session
                viewModelScope.launch {
                    kotlinx.coroutines.withTimeoutOrNull(INVITE_TIMEOUT_MS) { session.await() }
                        ?.let { resultsChannel.send(it) }
                }
            }
            relay.answer(relayInvite, accept)
            return
        }

        invite.respond(accept)
        if (accept) {
            remember(invite.fromDeviceId, invite.from, invite.fromAddresses)
            resultsChannel.trySend(GameLink.Connection.Host(invite.from))
        }
    }

    fun showMyCode() {
        uiState.update { it.copy(code = Code.Loading) }
        relay.requestCode()
    }

    fun enterCode(code: String) {
        uiState.update { it.copy(code = Code.Loading) }
        relay.redeemCode(code)
    }

    fun dismissCode() {
        uiState.update { it.copy(code = null) }
    }

    fun forget(friend: Friend) {
        friendsStore.forget(friend.friend.deviceId)
        storedFriends.value = friendsStore.getFriends()
        relay.updateHello(uiState.value.identity, storedFriends.value)
    }

    fun unplug() {
        resultsChannel.trySend(GameLink.Connection.Unplugged)
    }

    override fun onCleared() {
        inviteJob?.cancel()
        uiState.value.incoming?.let { invite ->
            relayInvites.entries.firstOrNull { it.value === invite }?.let { relay.answer(it.key, false) } ?: invite.respond(false)
        }
        nearby.close()
        relay.close()
        super.onCleared()
    }

    companion object {
        private const val INVITE_TIMEOUT_MS = 35_000L
        private const val MAX_FRIEND_ADDRESSES = 6
    }
}
