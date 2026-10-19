package com.swordfish.lemuroid.app.shared.link

import android.os.ParcelFileDescriptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * The network half of a link cable between two devices. The player who accepted the invite hosts and is
 * player 1. The two devices connect, give each other their game and save, and then LibretroDroid keeps
 * both copies of the linked Game Boys in lockstep over the same socket.
 */
class NetLink(private val connection: GameLink.Connection) : Closeable {
    /** The other player's game, needed to run both Game Boys on this device too. */
    class Partner(val rom: ByteArray, val saveRAM: ByteArray?, val title: String)

    val isHost: Boolean = connection is GameLink.Connection.Host
    val localPlayer: Int = if (isHost) 0 else 1
    val peerName: String = connection.peer?.displayName ?: ""
    private var heldSocket: Socket? = null

    suspend fun connect(): Socket =
        withContext(Dispatchers.IO) {
            val socket =
                when (connection) {
                    is GameLink.Connection.Host -> accept()
                    is GameLink.Connection.Guest -> dial(connection.address)
                    GameLink.Connection.Unplugged -> throw IOException("Not linked")
                }
            socket.apply {
                tcpNoDelay = true
                soTimeout = EXCHANGE_TIMEOUT_MS
            }
        }

    private fun accept(): Socket {
        return ServerSocket().use { server ->
            server.reuseAddress = true
            server.bind(InetSocketAddress(PORT))
            server.soTimeout = CONNECT_TIMEOUT_MS
            server.accept()
        }
    }

    /** Both games start at about the same time, so the guest keeps trying until the host listens. */
    private suspend fun dial(address: String): Socket {
        val deadline = System.currentTimeMillis() + CONNECT_TIMEOUT_MS
        while (true) {
            try {
                return Socket().apply { connect(InetSocketAddress(address, PORT), DIAL_TIMEOUT_MS) }
            } catch (e: IOException) {
                if (System.currentTimeMillis() > deadline) throw e
                Timber.i("Link host not ready yet, retrying")
                delay(DIAL_RETRY_MS)
            }
        }
    }

    /** Sends this player's game and save while receiving the other player's. */
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
                    output.writeUTF(title.take(MAX_TITLE_LENGTH))
                    output.writeInt(rom.size)
                    output.write(rom)
                    output.writeInt(saveRAM?.size ?: -1)
                    saveRAM?.let { output.write(it) }
                    output.flush()
                }

            val partner =
                withContext(Dispatchers.IO) {
                    val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
                    if (input.readInt() != MAGIC) throw IOException("Not a link cable")
                    if (input.readInt() != PROTOCOL_VERSION) throw IOException("Different link cable version")
                    val partnerTitle = input.readUTF()
                    val partnerRom = ByteArray(readSize(input, MAX_ROM_SIZE)).also { input.readFully(it) }
                    val saveSize = input.readInt()
                    val partnerSave =
                        if (saveSize >= 0) {
                            ByteArray(checkSize(saveSize, MAX_SAVE_SIZE)).also { input.readFully(it) }
                        } else {
                            null
                        }
                    Partner(partnerRom, partnerSave, partnerTitle)
                }

            sending.await()
            partner
        }

    private fun readSize(
        input: DataInputStream,
        max: Int,
    ): Int = checkSize(input.readInt(), max)

    private fun checkSize(
        size: Int,
        max: Int,
    ): Int {
        if (size !in 0..max) throw IOException("Unexpected size $size")
        return size
    }

    /**
     * Hands the connection over as a duplicated descriptor that belongs to the caller. The socket stays
     * open until [close], so that closing it can never cut the connection while the core still uses it.
     */
    fun detach(socket: Socket): Int {
        socket.soTimeout = 0
        heldSocket = socket
        return ParcelFileDescriptor.fromSocket(socket).detachFd()
    }

    override fun close() {
        runCatching { heldSocket?.close() }
        heldSocket = null
    }

    companion object {
        /** Next to the port the Gambatte link used. */
        private const val PORT = GameLink.GAME_PORT + 1
        private const val MAGIC = 0x4C4B4342 // "LKCB"
        private const val PROTOCOL_VERSION = 1
        private const val MAX_TITLE_LENGTH = 100
        private const val MAX_ROM_SIZE = 8 * 1024 * 1024
        private const val MAX_SAVE_SIZE = 1024 * 1024
        private const val CONNECT_TIMEOUT_MS = 30_000
        private const val DIAL_TIMEOUT_MS = 2_000
        private const val DIAL_RETRY_MS = 500L
        private const val EXCHANGE_TIMEOUT_MS = 15_000

        /** Frames between pressing a button and the game seeing it, which hides the Wi-Fi latency. */
        const val INPUT_DELAY_FRAMES = 3
    }
}
