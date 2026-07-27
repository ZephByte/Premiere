package dev.zephbyte.premiere.util

import java.net.InetAddress
import java.net.URI

/**
 * URL validation is a security boundary on both sides: the server opens the URL
 * to decode the audio track, and every video client opens it to decode video.
 * Only plain public http(s) is acceptable; file://, internal hosts, and private
 * ranges are rejected before anything connects.
 */
object MediaUrls {

    /** Cheap lexical check. Returns a human-readable error, or null if OK. */
    fun validate(url: String): String? {
        val uri = try {
            URI(url)
        } catch (e: Exception) {
            return "Not a valid URL"
        }
        val scheme = uri.scheme?.lowercase() ?: return "URL has no scheme; use http:// or https://"
        if (scheme != "http" && scheme != "https") return "Only http and https URLs are allowed"
        val host = uri.host ?: return "URL has no host"
        if (host.equals("localhost", ignoreCase = true) || host.endsWith(".localhost")) {
            return "Local addresses are not allowed"
        }
        return null
    }

    /**
     * Full check including DNS resolution; blocks, so call off-thread.
     * Returns a human-readable error, or null if the URL resolves to public
     * addresses only.
     */
    fun validateResolved(url: String): String? {
        validate(url)?.let { return it }
        val host = URI(url).host
        val addresses = try {
            InetAddress.getAllByName(host)
        } catch (e: Exception) {
            return "Could not resolve host '$host'"
        }
        for (address in addresses) {
            if (isPrivate(address)) return "URL resolves to a private or local address; only public hosts are allowed"
        }
        return null
    }

    private fun isPrivate(address: InetAddress): Boolean {
        if (address.isLoopbackAddress || address.isSiteLocalAddress || address.isLinkLocalAddress ||
            address.isAnyLocalAddress || address.isMulticastAddress
        ) return true
        val bytes = address.address
        if (bytes.size == 4) {
            val b0 = bytes[0].toInt() and 0xFF
            val b1 = bytes[1].toInt() and 0xFF
            // 100.64.0.0/10 (CGNAT) and 192.0.0.0/24 aren't covered by isSiteLocalAddress
            if (b0 == 100 && b1 in 64..127) return true
            if (b0 == 192 && b1 == 0 && (bytes[2].toInt() and 0xFF) == 0) return true
        } else if (bytes.size == 16) {
            // fc00::/7 unique-local
            if ((bytes[0].toInt() and 0xFE) == 0xFC) return true
        }
        return false
    }
}
