// SPDX-License-Identifier: GPL-3.0-only
package com.aikeyboard.app.ai.client.locallan.util

import java.net.URI

/**
 * Classifies the host portion of a Local LAN base URL so the edit screen can warn
 * before saving a cleartext URL whose host is publicly routable.
 *
 * Phase 10 takes Option B from §1.5: NSC base-config permits cleartext globally,
 * with this validator + a warn-before-save dialog as the privacy compensator. IPv6
 * support is intentionally narrow — full IPv6 ULA/GUA classification deferred to
 * Phase 12.
 */
object PublicIpValidator {

    enum class Classification {
        LOOPBACK,        // 127.0.0.0/8 or "localhost" or ::1
        PRIVATE,         // RFC1918: 10/8, 172.16/12, 192.168/16
        LINK_LOCAL,      // 169.254/16 (DHCP fallback) or fe80::/10
        HOSTNAME,        // not an IP literal — DNS resolves at runtime
        PUBLIC,          // routable IPv4 outside the private/loopback ranges
        INVALID,         // can't parse the URL or host
    }

    /** True if the classification doesn't require a public-IP cleartext warning. */
    fun isSafeForCleartext(c: Classification): Boolean = when (c) {
        Classification.LOOPBACK,
        Classification.PRIVATE,
        Classification.LINK_LOCAL,
        Classification.HOSTNAME -> true
        Classification.PUBLIC,
        Classification.INVALID -> false
    }

    /** Pure helper: extracts host from URL string and classifies it. */
    fun classifyUrl(url: String): Classification {
        val host = runCatching { URI(url.trim()).host }.getOrNull()
            ?: return Classification.INVALID
        if (host.isEmpty()) return Classification.INVALID
        return classifyHost(host)
    }

    /** Pure helper: classifies a host string (IP literal or hostname). */
    internal fun classifyHost(host: String): Classification {
        // IPv6 loopback and link-local. Phase 10 v1 doesn't fully classify
        // arbitrary IPv6; everything else with a colon is treated as PUBLIC
        // unless it matches loopback/link-local prefixes.
        if (host == "::1" || host == "[::1]") return Classification.LOOPBACK
        if (host.startsWith("fe80:", ignoreCase = true) ||
            host.startsWith("[fe80:", ignoreCase = true)
        ) return Classification.LINK_LOCAL
        if (host.contains(':')) return Classification.PUBLIC // conservative IPv6 fallback
        if (host.equals("localhost", ignoreCase = true)) return Classification.LOOPBACK
        val octets = host.split('.')
        if (octets.size != 4 || !octets.all { it.toIntOrNull()?.let { v -> v in 0..255 } == true }) {
            return Classification.HOSTNAME
        }
        val a = octets[0].toInt()
        val b = octets[1].toInt()
        return when {
            a == 127 -> Classification.LOOPBACK
            a == 10 -> Classification.PRIVATE
            a == 172 && b in 16..31 -> Classification.PRIVATE
            a == 192 && b == 168 -> Classification.PRIVATE
            a == 169 && b == 254 -> Classification.LINK_LOCAL
            a == 0 || a >= 224 -> Classification.INVALID // 0.x or multicast/reserved
            else -> Classification.PUBLIC
        }
    }
}
