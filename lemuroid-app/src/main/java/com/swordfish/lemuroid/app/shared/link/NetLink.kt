package com.swordfish.lemuroid.app.shared.link

import android.os.ParcelFileDescriptor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import timber.log.Timber
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import javax.net.ssl.SSLSocket
import kotlin.concurrent.thread
import kotlin.math.ceil

/**
 * The network half of a link cable between two devices. The player who accepted the invite hosts and is
 * player 1. The two devices connect (directly, or through the relay for friends elsewhere), give each
 * other their game and save, agree on the input delay, and then LibretroDroid keeps both copies of the
 * linked Game Boys in lockstep over the same connection.
 */
class NetLink(private val connection: GameLink.Connection) : Closeable {
    /** The other player's game, needed to run both Game Boys on this device too. */
    class Partner(val rom: ByteArray, val saveRAM: ByteArray?, val title: String, val inputDelay: Int)

    val isHost: Boolean =
        when (connection) {
            is GameLink.Connection.Host -> true
            is GameLink.Connection.Online -> connection.isHost
            else -> false
        }
    val localPlayer: Int = if (isHost) 0 else 1
    val peerName: String = connection.peer?.displayName ?: ""

    private val sessionToken = (connection as? GameLink.Connection.Online)?.session ?: ""
    private val closeables = mutableListOf<Closeable>()

    suspend fun connect(): Socket =
        withContext(Dispatchers.IO) {
            val socket =
                when (connection) {
                    is GameLink.Connection.Host -> accept(CONNECT_TIMEOUT_MS)
                    is GameLink.Connection.Guest -> dial(listOf(connection.address), CONNECT_TIMEOUT_MS.toLong())
                    is GameLink.Connection.Online -> connectOnline(connection)
                    GameLink.Connection.Unplugged -> throw IOException("Not linked")
                }
            socket.apply {
                tcpNoDelay = true
                soTimeout = EXCHANGE_TIMEOUT_MS
            }
        }

    private fun accept(timeoutMs: Int): Socket {
        val server = ServerSocket()
        synchronized(closeables) { closeables += server }
        return server.use {
            server.reuseAddress = true
            server.bind(InetSocketAddress(PORT))
            server.soTimeout = timeoutMs
            server.accept()
        }
    }

    /** Both games start at about the same time, so keep trying until the host listens. */
    private suspend fun dial(
        addresses: List<String>,
        timeoutMs: Long,
    ): Socket {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            for (address in addresses) {
                try {
                    return Socket().apply { connect(InetSocketAddress(address, PORT), DIAL_TIMEOUT_MS) }
                } catch (e: IOException) {
                    if (System.currentTimeMillis() > deadline) throw e
                }
            }
            Timber.i("Link host not ready yet, retrying")
            delay(DIAL_RETRY_MS)
        }
    }

    /**
     * Friends elsewhere may still reach each other directly, on the same Wi-Fi or over Tailscale. The guest
     * tries the host's addresses for a few seconds and falls back to the relay; the host takes whichever
     * arrives first.
     */
    private suspend fun connectOnline(online: GameLink.Connection.Online): Socket =
        coroutineScope {
            if (!online.isHost) {
                val direct = runCatching { dial(online.peerAddresses, DIRECT_TIMEOUT_MS) }.getOrNull()
                return@coroutineScope direct ?: joinRelay(online.session)
            }

            val winner = CompletableDeferred<Socket>()
            val direct = launch(Dispatchers.IO) { runCatching { accept(CONNECT_TIMEOUT_MS) }.onSuccess { winner.complete(it) } }
            val relay = launch(Dispatchers.IO) { runCatching { joinRelay(online.session) }.onSuccess { winner.complete(it) } }
            launch {
                direct.join()
                relay.join()
                winner.completeExceptionally(IOException("Couldn't reach the other player"))
            }
            val socket = winner.await()
            // Unblock the losing attempt.
            synchronized(closeables) { closeables.filter { it !== socket }.forEach { runCatching { it.close() } } }
            direct.cancel()
            relay.cancel()
            socket
        }

    private fun joinRelay(session: String): Socket {
        val socket = LinkRelay.openRelaySocket()
        synchronized(closeables) { closeables += socket }
        socket.soTimeout = CONNECT_TIMEOUT_MS
        val output = socket.getOutputStream()
        val role = if (isHost) "host" else "guest"
        val join = JSONObject().put("op", "join").put("session", session).put("id", role)
        output.write((join.toString() + "\n").toByteArray())
        output.flush()
        val reply = readLine(socket.getInputStream())
        if (JSONObject(reply).optString("op") != "paired") throw IOException("Relay said $reply")
        Timber.i("Linked through the relay")
        return socket
    }

    /** Reads one line without buffering, so nothing after it is taken from the stream. */
    private fun readLine(input: InputStream): String {
        val bytes = java.io.ByteArrayOutputStream()
        while (true) {
            val byte = input.read()
            if (byte < 0) throw IOException("Connection closed")
            if (byte == '\n'.code) return bytes.toString(Charsets.UTF_8.name())
            bytes.write(byte)
            if (bytes.size() > MAX_LINE) throw IOException("Line too long")
        }
    }

    /**
     * Sends this player's game and save while receiving the other player's, then the host measures the
     * connection and picks the input delay for both. Reading is unbuffered: whatever follows belongs to
     * LibretroDroid.
     */
    suspend fun exchange(
        socket: Socket,
        rom: ByteArray,
        saveRAM: ByteArray?,
        title: String,
    ): Partner =
        coroutineScope {
            // Both sides write first, so writing must not wait for reading or a large game would deadlock.
            val sending =
                async(Dispatchers.IO) {
                    val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))
                    output.writeInt(MAGIC)
                    output.writeInt(PROTOCOL_VERSION)
                    output.writeUTF(sessionToken)
                    output.writeUTF(title.take(MAX_TITLE_LENGTH))
                    output.writeInt(rom.size)
                    output.write(rom)
                    output.writeInt(saveRAM?.size ?: -1)
                    saveRAM?.let { output.write(it) }
                    output.flush()
                }

            val received =
                withContext(Dispatchers.IO) {
                    val input = DataInputStream(socket.getInputStream())
                    if (input.readInt() != MAGIC) throw IOException("Not a link cable")
                    if (input.readInt() != PROTOCOL_VERSION) throw IOException("Different link cable version")
                    if (input.readUTF() != sessionToken) throw IOException("Someone else's link")
                    val partnerTitle = input.readUTF()
                    val partnerRom = ByteArray(checkSize(input.readInt(), MAX_ROM_SIZE)).also { input.readFully(it) }
                    val saveSize = input.readInt()
                    val partnerSave =
                        if (saveSize >= 0) ByteArray(checkSize(saveSize, MAX_SAVE_SIZE)).also { input.readFully(it) } else null
                    Triple(partnerTitle, partnerRom, partnerSave)
                }
            sending.await()

            val inputDelay = withContext(Dispatchers.IO) { agreeOnInputDelay(socket) }
            Partner(received.second, received.third, received.first, inputDelay)
        }

    /** The input has to reach the other device before its frame runs: one way latency plus some margin. */
    private fun agreeOnInputDelay(socket: Socket): Int {
        val input = socket.getInputStream()
        val output = socket.getOutputStream()
        if (!isHost) {
            repeat(PING_COUNT) {
                output.write(input.read().also { if (it < 0) throw IOException("Connection closed") })
                output.flush()
            }
            return input.read().takeIf { it > 0 } ?: throw IOException("Connection closed")
        }

        val roundTrips =
            (1..PING_COUNT).map {
                val start = System.nanoTime()
                output.write(PING_BYTE)
                output.flush()
                if (input.read() < 0) throw IOException("Connection closed")
                (System.nanoTime() - start) / 1_000_000.0
            }.sorted()
        val roundTripMs = roundTrips[roundTrips.size / 2]
        val delay = (ceil(roundTripMs / 2 / FRAME_MS).toInt() + DELAY_MARGIN_FRAMES).coerceIn(MIN_DELAY, MAX_DELAY)
        Timber.i("Link round trip ${roundTripMs.toInt()} ms, input delay $delay frames")
        output.write(delay)
        output.flush()
        return delay
    }

    private fun checkSize(
        size: Int,
        max: Int,
    ): Int {
        if (size !in 0..max) throw IOException("Unexpected size $size")
        return size
    }

    /**
     * Hands the connection over as a descriptor that belongs to the caller. A direct socket is duplicated
     * and stays open until [close], so closing it can never cut the connection while the core uses it.
     * The relay connection is encrypted, so the core gets one end of a local socket pair and two threads
     * copy between the other end and the relay.
     */
    fun detach(socket: Socket): Int {
        socket.soTimeout = 0
        synchronized(closeables) { closeables += socket }
        if (socket !is SSLSocket) {
            return ParcelFileDescriptor.fromSocket(socket).detachFd()
        }

        val (coreEnd, pumpEnd) = ParcelFileDescriptor.createSocketPair()
        synchronized(closeables) { closeables += pumpEnd }
        pump(FileInputStream(pumpEnd.fileDescriptor), socket.getOutputStream(), socket)
        pump(socket.getInputStream(), FileOutputStream(pumpEnd.fileDescriptor), pumpEnd)
        return coreEnd.detachFd()
    }

    private fun pump(
        from: InputStream,
        to: OutputStream,
        closeWhenDone: Closeable,
    ) {
        thread(name = "link-pump", isDaemon = true) {
            val buffer = ByteArray(PUMP_BUFFER)
            try {
                while (true) {
                    val count = from.read(buffer)
                    if (count < 0) break
                    to.write(buffer, 0, count)
                    to.flush()
                }
            } catch (e: IOException) {
                Timber.i("Link pump stopped: ${e.message}")
            }
            // One side ended: end the other, so LibretroDroid and the relay both notice.
            runCatching { closeWhenDone.close() }
        }
    }

    override fun close() {
        synchronized(closeables) {
            closeables.forEach { runCatching { it.close() } }
            closeables.clear()
        }
    }

    companion object {
        /** Next to the port the Gambatte link used. */
        private const val PORT = GameLink.GAME_PORT + 1
        private const val MAGIC = 0x4C4B4342 // "LKCB"
        private const val PROTOCOL_VERSION = 2
        private const val MAX_TITLE_LENGTH = 100
        private const val MAX_LINE = 4096
        private const val MAX_ROM_SIZE = 8 * 1024 * 1024
        private const val MAX_SAVE_SIZE = 1024 * 1024
        private const val CONNECT_TIMEOUT_MS = 30_000
        private const val DIRECT_TIMEOUT_MS = 4_000L
        private const val DIAL_TIMEOUT_MS = 1_500
        private const val DIAL_RETRY_MS = 500L
        private const val EXCHANGE_TIMEOUT_MS = 30_000
        private const val PUMP_BUFFER = 16 * 1024

        private const val PING_COUNT = 5
        private const val PING_BYTE = 0x50
        private const val FRAME_MS = 1000.0 / 60
        private const val DELAY_MARGIN_FRAMES = 2
        private const val MIN_DELAY = 3
        private const val MAX_DELAY = 10
    }
}
