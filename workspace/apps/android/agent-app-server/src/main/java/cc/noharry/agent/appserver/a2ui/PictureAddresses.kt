package cc.noharry.agent.appserver.a2ui

import java.net.Inet6Address
import java.net.InetAddress
import java.net.UnknownHostException
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

// What a card's picture may name when the server fetches it (a page's pictures, a photo's size). The app keeps the same
// rules for the pictures it loads (image/Pictures.kt): the two sides check alike, each with its own client.

/**
 * A picture URL a card may name: https, no credentials, at most [MAX_PICTURE_URL_CHARS], and not a local name or a
 * non-public address. A host name is checked again when it resolves ([PublicOnlyDns]).
 */
internal fun remotePictureUrl(reference: String): HttpUrl? {
    if (reference.length > MAX_PICTURE_URL_CHARS) return null
    val url = reference.toHttpUrlOrNull() ?: return null
    if (!url.isHttps || url.username.isNotEmpty() || url.password.isNotEmpty()) return null
    val host = url.host.lowercase()
    if (host == "localhost" || LOCAL_SUFFIXES.any { host.endsWith(it) }) return null
    if (ADDRESS_LITERAL.matches(host) && !InetAddress.getByName(host).isPublic()) return null
    return url
}

/** Resolves only to public addresses: a name that points at the device, the LAN or a carrier NAT is refused. */
internal object PublicOnlyDns : Dns {
    override fun lookup(hostname: String): List<InetAddress> =
        Dns.SYSTEM.lookup(hostname).filter { it.isPublic() }.ifEmpty { throw UnknownHostException("$hostname has no public address") }
}

internal fun InetAddress.isPublic(): Boolean {
    if (isAnyLocalAddress || isLoopbackAddress || isLinkLocalAddress || isSiteLocalAddress || isMulticastAddress) return false
    val bytes = address
    // Carrier-grade NAT 100.64.0.0/10 and IPv6 unique local fc00::/7 are private too.
    if (bytes.size == 4 && bytes[0].toInt() == 100 && (bytes[1].toInt() and 0xC0) == 64) return false
    if (this is Inet6Address && (bytes[0].toInt() and 0xFE) == 0xFC) return false
    return true
}

private val ADDRESS_LITERAL = Regex("""[0-9.]+|.*:.*""")
private val LOCAL_SUFFIXES = listOf(".local", ".localhost", ".lan", ".internal", ".home.arpa")
private const val MAX_PICTURE_URL_CHARS = 2048
