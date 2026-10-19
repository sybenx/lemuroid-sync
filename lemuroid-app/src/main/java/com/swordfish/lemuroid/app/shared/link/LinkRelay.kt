package com.swordfish.lemuroid.app.shared.link

import android.util.Base64
import com.swordfish.lemuroid.app.shared.link.LinkFriendsStore.Companion.toStrings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.BufferedReader
import java.io.Closeable
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

/** A friend the relay reports online, with what they are playing. */
data class RelayPeer(
    val deviceId: String,
    val identity: LinkIdentity,
    val gameTitle: String,
    val addresses: List<String>,
)

/**
 * Talks to the link relay on racknerd2, which lets friends on different networks see each other online,
 * invite each other and, once an invite is accepted, play through it when they can't reach each other
 * directly. The connection lasts while the Link Cable screen is open.
 */
class LinkRelay(
    private val deviceId: String,
    private val gameTitle: String,
) : Closeable {
    sealed interface Event {
        data class Invite(val from: RelayPeer) : Event

        data class InviteCancelled(val fromId: String) : Event

        data class Declined(val fromId: String) : Event

        data class Unreachable(val toId: String) : Event

        data class Session(val session: String, val with: RelayPeer, val isHost: Boolean) : Event

        data class Code(val code: String) : Event

        data object CodeInvalid : Event

        data class Friend(val friend: RelayPeer) : Event
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // One at a time, so messages go out in the order they were sent.
    private val sendDispatcher = Dispatchers.IO.limitedParallelism(1)

    private val onlineState = MutableStateFlow<List<RelayPeer>>(emptyList())
    val online: StateFlow<List<RelayPeer>> = onlineState

    private val connectedState = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = connectedState

    private val eventsChannel = Channel<Event>(Channel.UNLIMITED)
    val events: Flow<Event> = eventsChannel.receiveAsFlow()

    @Volatile
    private var output: OutputStream? = null

    @Volatile
    private var socket: Socket? = null
    private var loop: Job? = null

    @Volatile
    private var hello: JSONObject? = null

    /** Connects, and reconnects whenever the connection drops, until closed. */
    fun start(
        identity: LinkIdentity,
        friends: List<LinkFriend>,
    ) {
        updateHello(identity, friends)
        loop =
            scope.launch {
                while (isActive) {
                    runCatching { session() }
                        .onFailure { Timber.w(it, "Link relay connection lost") }
                    connectedState.value = false
                    onlineState.value = emptyList()
                    delay(RECONNECT_DELAY_MS)
                }
            }
    }

    /** Sends a new name or friends list. */
    fun updateHello(
        identity: LinkIdentity,
        friends: List<LinkFriend>,
    ) {
        val message =
            JSONObject()
                .put("op", "hello")
                .put("v", PROTOCOL_VERSION)
                .put("id", deviceId)
                .put("name", identity.displayName)
                .put("color", identity.color.name)
                .put("creature", identity.creature.name)
                .put("game", gameTitle)
                .put("addrs", JSONArray(LinkFriendsStore.localAddresses()))
                .put("friends", JSONArray(friends.map { it.deviceId }))
        hello = message
        send(message)
    }

    fun invite(peerId: String) = send(JSONObject().put("op", "invite").put("to", peerId))

    fun cancelInvite(peerId: String) = send(JSONObject().put("op", "cancel").put("to", peerId))

    fun answer(
        peerId: String,
        accept: Boolean,
    ) = send(JSONObject().put("op", "answer").put("to", peerId).put("accept", accept))

    fun requestCode() = send(JSONObject().put("op", "code"))

    fun redeemCode(code: String) = send(JSONObject().put("op", "redeem").put("code", code.trim()))

    private fun send(message: JSONObject) {
        val stream = output ?: return
        scope.launch(sendDispatcher) {
            runCatching {
                stream.write((message.toString() + "\n").toByteArray())
                stream.flush()
            }.onFailure { runCatching { socket?.close() } }
        }
    }

    private suspend fun session() {
        val socket = openRelaySocket()
        this.socket = socket
        socket.use {
            output = socket.getOutputStream()
            hello?.let { send(it) }
            connectedState.value = true

            val pinger =
                scope.launch {
                    while (isActive) {
                        delay(PING_INTERVAL_MS)
                        send(JSONObject().put("op", "ping"))
                    }
                }
            try {
                val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
                while (true) {
                    val line = reader.readLine() ?: break
                    handle(JSONObject(line))
                }
            } finally {
                pinger.cancel()
                output = null
            }
        }
    }

    private fun handle(message: JSONObject) {
        when (message.optString("op")) {
            "presence" -> {
                val friends = message.optJSONArray("friends") ?: JSONArray()
                onlineState.value = (0 until friends.length()).mapNotNull { parsePeer(friends.getJSONObject(it)) }
            }
            "invite" -> parsePeer(message.optJSONObject("from"))?.let { eventsChannel.trySend(Event.Invite(it)) }
            "cancel" -> eventsChannel.trySend(Event.InviteCancelled(message.optString("from")))
            "declined" -> eventsChannel.trySend(Event.Declined(message.optString("from")))
            "invite_failed" -> eventsChannel.trySend(Event.Unreachable(message.optString("to")))
            "session" -> {
                val with = parsePeer(message.optJSONObject("with")) ?: return
                eventsChannel.trySend(Event.Session(message.getString("session"), with, message.optBoolean("host")))
            }
            "code" -> eventsChannel.trySend(Event.Code(message.getString("code")))
            "code_invalid" -> eventsChannel.trySend(Event.CodeInvalid)
            "friend" -> parsePeer(message.optJSONObject("friend"))?.let { eventsChannel.trySend(Event.Friend(it)) }
        }
    }

    private fun parsePeer(json: JSONObject?): RelayPeer? {
        json ?: return null
        val identity = LinkIdentity.fromIds(json.optString("color"), json.optString("creature")) ?: return null
        return RelayPeer(
            deviceId = json.optString("id").takeIf { it.isNotEmpty() } ?: return null,
            identity = identity,
            gameTitle = json.optString("game"),
            addresses = json.optJSONArray("addrs").toStrings(),
        )
    }

    override fun close() {
        loop?.cancel()
        runCatching { socket?.close() }
        scope.cancel()
        eventsChannel.close()
    }

    companion object {
        private const val PROTOCOL_VERSION = 1
        private const val RECONNECT_DELAY_MS = 5_000L
        private const val PING_INTERVAL_MS = 30_000L
        private const val CONNECT_TIMEOUT_MS = 5_000

        /**
         * The relay, by its public name first and then on Tailscale, where it is reachable even while
         * its public port is closed.
         */
        private val RELAY_ADDRESSES = listOf("mail.aaro.cc", "100.86.253.33")
        private const val RELAY_PORT = 56450

        /** SHA-256 of the relay certificate's public key. The relay's certificate is self-signed. */
        private const val RELAY_KEY_PIN = "5mJY1EGZQFZsuqmuWykjh0BFKmul3EHF/LE6UN94I+Y="

        /** A TLS connection to the relay, which only trusts the relay's own key. */
        fun openRelaySocket(): SSLSocket {
            var lastError: Exception? = null
            for (host in RELAY_ADDRESSES) {
                try {
                    val plain = Socket().apply { connect(InetSocketAddress(host, RELAY_PORT), CONNECT_TIMEOUT_MS) }
                    val tls = sslContext.socketFactory.createSocket(plain, host, RELAY_PORT, true) as SSLSocket
                    tls.startHandshake()
                    tls.tcpNoDelay = true
                    return tls
                } catch (e: Exception) {
                    lastError = e
                }
            }
            throw lastError ?: IllegalStateException("No relay address")
        }

        private val sslContext: SSLContext by lazy {
            SSLContext.getInstance("TLS").apply { init(null, arrayOf(PinnedTrustManager), null) }
        }

        private object PinnedTrustManager : X509TrustManager {
            override fun checkClientTrusted(
                chain: Array<out X509Certificate>,
                authType: String,
            ) = throw CertificateException("Not a server")

            override fun checkServerTrusted(
                chain: Array<out X509Certificate>,
                authType: String,
            ) {
                val key = chain.firstOrNull()?.publicKey?.encoded ?: throw CertificateException("No certificate")
                val pin = Base64.encodeToString(MessageDigest.getInstance("SHA-256").digest(key), Base64.NO_WRAP)
                if (pin != RELAY_KEY_PIN) throw CertificateException("Not the link relay")
            }

            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
    }
}
