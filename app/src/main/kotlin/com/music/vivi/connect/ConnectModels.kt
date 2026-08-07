/**
 * vivimusic Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.music.vivi.connect

import kotlinx.serialization.Serializable
import java.security.MessageDigest

/**
 * Vivi Connect — Spotify-Connect-style control between full Android devices
 * (phone, tablet, another phone) on the same network.
 *
 * This is a *second* transport, not a replacement for the Wear Data Layer. The
 * Data Layer only ever connects a handheld to a paired Wear OS watch; it will
 * never see a tablet. Both transports carry the same [com.music.vivi.wearsync]
 * payloads, which is why that module holds no transport types.
 *
 * Unlike Spotify's, this is peer-to-peer over the LAN with no server, so it
 * works only while devices share a network. That is the trade for having no
 * backend and no playback data leaving the house.
 */
object ConnectProtocol {
    /** NSD service type. The `_tcp` suffix is required by the API. */
    const val SERVICE_TYPE = "_vivimusic._tcp"

    /** NSD TXT record keys. */
    const val ATTR_DEVICE_ID = "did"
    const val ATTR_DEVICE_NAME = "dn"
    const val ATTR_FINGERPRINT = "fp"

    /** Sent by a connecting peer before anything else. */
    const val PATH_HELLO = "/vivi/connect/hello"

    /** Accepted-handshake acknowledgement. */
    const val PATH_WELCOME = "/vivi/connect/welcome"

    /** Peer asks this device to hand playback over to it. */
    const val PATH_TRANSFER_TO_ME = "/vivi/connect/transfer"

    /** Keeps NAT/idle timeouts from silently dropping a socket. */
    const val PATH_PING = "/vivi/connect/ping"

    /**
     * Derives the shared secret that gates a connection.
     *
     * Anything on the Wi-Fi can reach an open socket, so a bare LAN listener
     * would let a stranger on the same café network drive your playback. Devices
     * signed into the same Google account derive the same fingerprint from the
     * account identity and accept each other; everything else is refused.
     *
     * The consequence, which is deliberate: Connect is unavailable when signed
     * out, because there would be no shared value to authenticate with and
     * "accept every device on the LAN" is not an acceptable default.
     *
     * Takes *every* identifier a device knows rather than the first one it can
     * find. Which account fields are populated differs from device to device —
     * one phone may have an email cached while a tablet only has a channel
     * handle — so a single "best" identifier makes two devices on the same
     * account hash different strings and refuse each other. Advertising the
     * whole set and matching on any overlap removes that whole class of
     * mismatch.
     */
    fun fingerprints(identities: List<String?>): Set<String> =
        identities.asSequence()
            .mapNotNull { it?.trim()?.takeIf(String::isNotBlank) }
            // Case and surrounding whitespace vary between how these fields get
            // stored; normalise so the same account always hashes the same.
            .map { fingerprintOf(it.lowercase()) }
            .toSet()

    private fun fingerprintOf(identity: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("vivi-connect-v1:$identity".toByteArray(Charsets.UTF_8))
        // 64 bits is ample to group devices by account and keeps the TXT record
        // well inside DNS-SD's 255-byte per-attribute ceiling once several
        // fingerprints are joined.
        return digest.take(8).joinToString("") { "%02x".format(it) }
    }

    fun encodeFingerprints(fingerprints: Set<String>): String =
        fingerprints.sorted().joinToString(",")

    fun decodeFingerprints(raw: String?): Set<String> =
        raw?.split(',')?.mapNotNull { it.takeIf(String::isNotBlank) }?.toSet().orEmpty()
}

/** What discovery is actually doing, surfaced so failures are diagnosable. */
data class ConnectStatus(
    val advertising: Boolean = false,
    val discovering: Boolean = false,
    /** Vivi services seen on the network, including ones later filtered out. */
    val servicesSeen: Int = 0,
    val rejectedDifferentAccount: Int = 0,
    val resolveFailures: Int = 0,
    val lastError: String? = null,
)

/**
 * One frame on the wire. Newline-delimited JSON, because the payloads are
 * already JSON and a length-prefixed binary format would buy nothing at these
 * sizes while being harder to debug with a socket dump.
 */
@Serializable
data class ConnectFrame(
    val path: String,
    /** Base64 of the `:wearsync` payload for [path]; empty for bare signals. */
    val data: String = "",
)

@Serializable
data class ConnectHello(
    val deviceId: String,
    val deviceName: String,
    val fingerprint: String,
)

/** A peer we can see, whether or not we currently hold a socket to it. */
data class ConnectDevice(
    val id: String,
    val name: String,
    val host: String,
    val port: Int,
    /** True when this entry describes the device the code is running on. */
    val isSelf: Boolean = false,
    /** True when this device currently holds the audio. */
    val isPlaying: Boolean = false,
    val connected: Boolean = false,
    /**
     * Whether the advertised TXT record matched our account. Only a hint — the
     * handshake is authoritative, because the TXT record is the least reliable
     * link in the chain.
     */
    val sameAccountHint: Boolean = true,
)
