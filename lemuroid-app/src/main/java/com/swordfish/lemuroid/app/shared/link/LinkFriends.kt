package com.swordfish.lemuroid.app.shared.link

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.Inet4Address
import java.net.NetworkInterface

/** Someone this player linked with before, who can be invited again from anywhere. */
data class LinkFriend(
    val deviceId: String,
    val identity: LinkIdentity,
    /** Where the device was last seen, e.g. its Wi-Fi and Tailscale addresses. */
    val addresses: List<String>,
)

/** Remembers friends on this device. Only the main process uses it. */
class LinkFriendsStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun getFriends(): List<LinkFriend> {
        val stored = preferences.getString(KEY_FRIENDS, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(stored)
            (0 until array.length()).mapNotNull { index ->
                val item = array.getJSONObject(index)
                val identity = LinkIdentity.fromIds(item.optString("color"), item.optString("creature"))
                identity?.let { LinkFriend(item.getString("id"), it, item.optJSONArray("addrs").toStrings()) }
            }
        }.getOrDefault(emptyList())
    }

    /** Adds [friend], or updates its name and addresses. */
    fun remember(friend: LinkFriend) {
        val friends = getFriends().filterNot { it.deviceId == friend.deviceId } + friend
        val array = JSONArray()
        friends.forEach {
            array.put(
                JSONObject()
                    .put("id", it.deviceId)
                    .put("color", it.identity.color.name)
                    .put("creature", it.identity.creature.name)
                    .put("addrs", JSONArray(it.addresses)),
            )
        }
        preferences.edit().putString(KEY_FRIENDS, array.toString()).apply()
    }

    fun forget(deviceId: String) {
        val remaining = getFriends().filterNot { it.deviceId == deviceId }
        preferences.edit().remove(KEY_FRIENDS).apply()
        remaining.forEach { remember(it) }
    }

    companion object {
        private const val PREFERENCES_NAME = "game_link_friends"
        private const val KEY_FRIENDS = "friends"

        /** This device's IPv4 addresses, e.g. on Wi-Fi and on Tailscale. */
        fun localAddresses(): List<String> {
            return runCatching {
                NetworkInterface.getNetworkInterfaces().toList()
                    .filter { it.isUp && !it.isLoopback }
                    .flatMap { it.inetAddresses.toList() }
                    .filterIsInstance<Inet4Address>()
                    .mapNotNull { it.hostAddress }
                    .distinct()
                    .take(MAX_ADDRESSES)
            }.getOrDefault(emptyList())
        }

        fun JSONArray?.toStrings(): List<String> {
            if (this == null) return emptyList()
            return (0 until length()).map { getString(it) }
        }

        private const val MAX_ADDRESSES = 6
    }
}
