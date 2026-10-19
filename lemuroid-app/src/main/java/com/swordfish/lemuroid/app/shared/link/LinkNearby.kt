package com.swordfish.lemuroid.app.shared.link

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import com.swordfish.lemuroid.app.shared.link.LinkFriendsStore.Companion.toStrings
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.BufferedReader
import java.io.Closeable
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.coroutines.resume

/** Another player on the same network who has the Link Cable screen open. */
data class LinkPeer(
    val serviceName: String,
    val deviceId: String,
    val identity: LinkIdentity,
    val gameTitle: String,
    val address: String,
    val port: Int,
    /** All of the device's addresses, e.g. its Tailscale one, to reach it again when not nearby. */
    val addresses: List<String> = listOf(address),
)

/** An invite from another player, waiting for this player to answer. */
class IncomingInvite internal constructor(
    val from: LinkIdentity,
    val gameTitle: String,
    internal val answer: CompletableDeferred<Boolean>,
    val fromDeviceId: String = "",
    val fromAddresses: List<String> = emptyList(),
    /** Both players invited each other at once, and this one was picked to accept: no need to ask. */
    val autoAccepted: Boolean = false,
) {
    /** Becomes true when the other player gives up before this player answers. */
    val cancelled = MutableStateFlow(false)

    fun respond(accept: Boolean) {
        answer.complete(accept)
    }
}

sealed interface InviteResult {
    data class Accepted(val connection: GameLink.Connection.Guest) : InviteResult

    data object Declined : InviteResult

    data object Busy : InviteResult

    data object Failed : InviteResult
}

/**
 * Finds other players on the local network with NSD (mDNS) and exchanges link invites with them over a
 * small TCP control socket that carries one line of JSON each way. The game itself is linked by the core
 * afterwards; this class only decides who hosts and where the guest connects.
 */
class LinkNearby(
    context: Context,
    private val deviceId: String,
    private val gameTitle: String,
) : Closeable {
    // Not tied to the screen: an answer to an invite must still go out while the menu is closing.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val nsdManager = context.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager

    private val peersState = MutableStateFlow<List<LinkPeer>>(emptyList())
    val peers: StateFlow<List<LinkPeer>> = peersState

    private val invitesChannel = Channel<IncomingInvite>(Channel.UNLIMITED)
    val invites: Flow<IncomingInvite> = invitesChannel.receiveAsFlow()

    private var identity: LinkIdentity? = null
    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private val resolveMutex = Mutex()

    @Volatile
    private var busy = false

    /** The player this device is inviting right now, to spot two invites crossing. */
    @Volatile
    private var invitingDeviceId: String? = null

    @Volatile
    private var closed = false

    fun start(identity: LinkIdentity) {
        this.identity = identity
        val socket = ServerSocket(0)
        serverSocket = socket
        acceptJob = scope.launch(Dispatchers.IO) { acceptInvites(socket) }
        register(identity, socket.localPort)
        discover()
    }

    /** Re-advertises under a new name after the player changes it. */
    fun updateIdentity(identity: LinkIdentity) {
        this.identity = identity
        val port = serverSocket?.localPort ?: return
        unregister()
        register(identity, port)
    }

    suspend fun invite(peer: LinkPeer): InviteResult =
        withContext(Dispatchers.IO) {
            val me = identity ?: return@withContext InviteResult.Failed
            busy = true
            invitingDeviceId = peer.deviceId
            try {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(peer.address, peer.port), CONNECT_TIMEOUT_MS)
                    socket.soTimeout = INVITE_TIMEOUT_MS
                    val writer = PrintWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8), true)
                    val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
                    val message =
                        JSONObject()
                            .put("type", "invite")
                            .put("v", PROTOCOL_VERSION)
                            .put("id", deviceId)
                            .put("color", me.color.name)
                            .put("creature", me.creature.name)
                            .put("game", gameTitle)
                            .put("addrs", JSONArray(LinkFriendsStore.localAddresses()))
                    writer.println(message.toString())
                    val reply = reader.readLine()?.let { JSONObject(it) }
                    when (reply?.optString("type")) {
                        "accept" -> InviteResult.Accepted(GameLink.Connection.Guest(peer.identity, peer.address))
                        "busy" -> InviteResult.Busy
                        "decline" -> InviteResult.Declined
                        else -> InviteResult.Failed
                    }
                }
            } catch (e: Exception) {
                Timber.w(e, "Link invite to ${peer.serviceName} failed")
                InviteResult.Failed
            } finally {
                busy = false
                invitingDeviceId = null
            }
        }

    private fun acceptInvites(socket: ServerSocket) {
        while (!closed) {
            val client =
                try {
                    socket.accept()
                } catch (e: Exception) {
                    if (!closed) Timber.w(e, "Link control socket closed")
                    return
                }
            scope.launch(Dispatchers.IO) { handleInvite(client) }
        }
    }

    private suspend fun handleInvite(client: Socket) {
        client.use { socket ->
            try {
                socket.soTimeout = READ_TIMEOUT_MS
                val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
                val writer = PrintWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8), true)
                val message = reader.readLine()?.let { JSONObject(it) } ?: return
                val from = LinkIdentity.fromIds(message.optString("color"), message.optString("creature"))
                if (message.optString("type") != "invite" || from == null) return

                val fromId = message.optString("id")
                if (busy && fromId.isNotEmpty() && fromId == invitingDeviceId) {
                    // Both invited each other at once. One rule both sides agree on picks who accepts:
                    // the smaller device id accepts the other's invite, the larger declines it and gets
                    // its own invite accepted instead.
                    if (deviceId < fromId) {
                        val invite =
                            IncomingInvite(
                                from,
                                message.optString("game"),
                                CompletableDeferred(true),
                                fromId,
                                message.optJSONArray("addrs").toStrings(),
                                autoAccepted = true,
                            )
                        invitesChannel.send(invite)
                        writer.println(JSONObject().put("type", "accept").toString())
                    } else {
                        writer.println(JSONObject().put("type", "decline").toString())
                    }
                    return
                }
                if (busy) {
                    writer.println(JSONObject().put("type", "busy").toString())
                    return
                }
                busy = true
                try {
                    val invite =
                        IncomingInvite(
                            from,
                            message.optString("game"),
                            CompletableDeferred(),
                            message.optString("id"),
                            message.optJSONArray("addrs").toStrings(),
                        )
                    invitesChannel.send(invite)

                    // Reading again only returns when the inviter hangs up, which means they gave up.
                    val hangUpWatcher =
                        scope.launch(Dispatchers.IO) {
                            val hungUp =
                                runCatching {
                                    socket.soTimeout = 0
                                    reader.read() < 0
                                }.getOrDefault(true)
                            if (hungUp && !invite.answer.isCompleted) {
                                invite.cancelled.value = true
                                invite.answer.complete(false)
                            }
                        }

                    val accepted = withTimeoutOrNull(ANSWER_TIMEOUT_MS) { invite.answer.await() } ?: false
                    hangUpWatcher.cancel()
                    if (!invite.cancelled.value) {
                        val type = if (accepted) "accept" else "decline"
                        writer.println(JSONObject().put("type", type).toString())
                    }
                } finally {
                    busy = false
                }
            } catch (e: Exception) {
                Timber.w(e, "Link invite handling failed")
            }
        }
    }

    private fun register(
        identity: LinkIdentity,
        port: Int,
    ) {
        val info =
            NsdServiceInfo().apply {
                serviceName = identity.displayName
                serviceType = SERVICE_TYPE
                setPort(port)
                setAttribute("v", PROTOCOL_VERSION.toString())
                setAttribute("id", deviceId)
                setAttribute("color", identity.color.name)
                setAttribute("creature", identity.creature.name)
                setAttribute("game", gameTitle.take(MAX_TITLE_LENGTH))
                setAttribute("addrs", LinkFriendsStore.localAddresses().joinToString(","))
            }
        val listener =
            object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                    Timber.i("Link advertised as ${serviceInfo.serviceName}")
                }

                override fun onRegistrationFailed(
                    serviceInfo: NsdServiceInfo,
                    errorCode: Int,
                ) {
                    Timber.w("Link advertising failed: $errorCode")
                }

                override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {}

                override fun onUnregistrationFailed(
                    serviceInfo: NsdServiceInfo,
                    errorCode: Int,
                ) {}
            }
        registrationListener = listener
        runCatching { nsdManager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener) }
            .onFailure { Timber.w(it, "Link advertising failed") }
    }

    private fun unregister() {
        registrationListener?.let { runCatching { nsdManager.unregisterService(it) } }
        registrationListener = null
    }

    private fun discover() {
        val listener =
            object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(serviceType: String) {}

                override fun onDiscoveryStopped(serviceType: String) {}

                override fun onStartDiscoveryFailed(
                    serviceType: String,
                    errorCode: Int,
                ) {
                    Timber.w("Link discovery failed: $errorCode")
                }

                override fun onStopDiscoveryFailed(
                    serviceType: String,
                    errorCode: Int,
                ) {}

                override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                    scope.launch { resolve(serviceInfo)?.let { addPeer(it) } }
                }

                override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                    peersState.update { peers -> peers.filterNot { it.serviceName == serviceInfo.serviceName } }
                }
            }
        discoveryListener = listener
        runCatching { nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener) }
            .onFailure { Timber.w(it, "Link discovery failed") }
    }

    private fun addPeer(peer: LinkPeer) {
        if (peer.deviceId == deviceId || closed) return
        peersState.update { peers ->
            (peers.filterNot { it.serviceName == peer.serviceName || it.deviceId == peer.deviceId } + peer)
                .sortedBy { it.identity.displayName }
        }
    }

    /** Older Android versions resolve one service at a time, so resolves are queued. */
    @Suppress("DEPRECATION")
    private suspend fun resolve(serviceInfo: NsdServiceInfo): LinkPeer? =
        resolveMutex.withLock {
            if (closed) return null
            val resolved =
                withTimeoutOrNull(RESOLVE_TIMEOUT_MS) {
                    suspendCancellableCoroutine<NsdServiceInfo?> { continuation ->
                        val listener =
                            object : NsdManager.ResolveListener {
                                override fun onResolveFailed(
                                    serviceInfo: NsdServiceInfo,
                                    errorCode: Int,
                                ) {
                                    if (continuation.isActive) continuation.resume(null)
                                }

                                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                                    if (continuation.isActive) continuation.resume(serviceInfo)
                                }
                            }
                        runCatching { nsdManager.resolveService(serviceInfo, listener) }
                            .onFailure { if (continuation.isActive) continuation.resume(null) }
                    }
                } ?: return null

            val attributes = resolved.attributes.mapValues { (_, value) -> value?.toString(Charsets.UTF_8) }
            val identity = LinkIdentity.fromIds(attributes["color"], attributes["creature"]) ?: return null
            val hosts =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    resolved.hostAddresses
                } else {
                    listOfNotNull(resolved.host)
                }
            // The core only speaks IPv4.
            val address = hosts.filterIsInstance<Inet4Address>().firstOrNull()?.hostAddress ?: return null
            LinkPeer(
                serviceName = resolved.serviceName,
                deviceId = attributes["id"] ?: return null,
                identity = identity,
                gameTitle = attributes["game"] ?: "",
                address = address,
                port = resolved.port,
                addresses = (listOf(address) + (attributes["addrs"]?.split(",") ?: emptyList())).filter { it.isNotBlank() }.distinct(),
            )
        }

    override fun close() {
        closed = true
        unregister()
        discoveryListener?.let { runCatching { nsdManager.stopServiceDiscovery(it) } }
        discoveryListener = null
        acceptJob?.cancel()
        runCatching { serverSocket?.close() }
        serverSocket = null
        invitesChannel.close()
    }

    companion object {
        private const val SERVICE_TYPE = "_lemuroidlink._tcp"
        private const val PROTOCOL_VERSION = 1
        private const val MAX_TITLE_LENGTH = 60
        private const val CONNECT_TIMEOUT_MS = 3_000
        private const val READ_TIMEOUT_MS = 5_000
        private const val INVITE_TIMEOUT_MS = 35_000
        private const val ANSWER_TIMEOUT_MS = 30_000L
        private const val RESOLVE_TIMEOUT_MS = 5_000L
    }
}
