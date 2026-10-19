package com.swordfish.lemuroid.app.shared.link

import com.swordfish.lemuroid.lib.library.CoreID
import com.swordfish.libretrodroid.Variable
import java.io.Serializable

/**
 * Game Link (link cable) support for cores that can talk to another copy of themselves over TCP.
 * Only Gambatte (Game Boy / Game Boy Color) has this today.
 */
object GameLink {
    /** The port Gambatte listens on. One of the values its "gambatte_gb_link_network_port" option accepts. */
    const val GAME_PORT = 56400

    /**
     * Off for now: Gambatte waits for a network round trip on every byte, which is too slow and freezes on
     * Wi-Fi. Linking two games on one device (LocalLink) replaces it until a lockstep version exists.
     */
    private const val ENABLED = false

    fun isSupported(coreID: CoreID): Boolean = ENABLED && coreID == CoreID.GAMBATTE

    /** Where the cable currently goes. Kept for the lifetime of a game session. */
    sealed interface Connection : Serializable {
        val peer: LinkIdentity?

        /** This device waits for the other one to connect. */
        data class Host(override val peer: LinkIdentity) : Connection

        /** This device connects to the other one at [address]. */
        data class Guest(override val peer: LinkIdentity, val address: String) : Connection

        data object Unplugged : Connection {
            override val peer: LinkIdentity? = null
        }
    }

    fun coreVariables(connection: Connection): Array<Variable> {
        val mode =
            when (connection) {
                is Connection.Host -> "Network Server"
                is Connection.Guest -> "Network Client"
                Connection.Unplugged -> "Not Connected"
            }
        val variables =
            mutableListOf(
                Variable("gambatte_gb_link_mode", mode),
                Variable("gambatte_gb_link_network_port", GAME_PORT.toString()),
            )
        if (connection is Connection.Guest) {
            addressDigits(connection.address).forEachIndexed { index, digit ->
                variables += Variable("gambatte_gb_link_network_server_ip_${index + 1}", digit.toString())
            }
        }
        return variables.toTypedArray()
    }

    /** Gambatte takes the server address as 12 single-digit options: 192.168.1.5 becomes 192 168 001 005. */
    fun addressDigits(address: String): List<Char> {
        val octets = address.split(".").map { it.toInt() }
        require(octets.size == 4 && octets.all { it in 0..255 }) { "Not an IPv4 address: $address" }
        return octets.joinToString("") { it.toString().padStart(3, '0') }.toList()
    }
}
