package com.adaptivevpn.core

import java.net.InetAddress

/** Literal route prefix returned by libbox; this class never performs hostname lookups. */
data class IpRoutePrefix(val address: String, val prefixLength: Int)

enum class IpFamily(val maxPrefix: Int) { IPV4(32), IPV6(128) }

/**
 * Fail-closed policy for the current full-tunnel Android adapter.
 * It validates route literals and detects only an actual default route or the two canonical /1s.
 */
object RouteCoveragePolicy {
    data class Result(val coversEntireFamily: Boolean, val validatedCount: Int)

    fun evaluate(routes: List<IpRoutePrefix>, family: IpFamily): Result {
        val normalized = routes.map { route ->
            require(route.prefixLength in 0..family.maxPrefix) { "Route prefix is outside ${family.name} range" }
            val bytes = parseLiteral(route.address, family)
            val network = mask(bytes, route.prefixLength)
            require(bytes.contentEquals(network)) { "Route address is not a canonical network address" }
            route.copy(address = format(bytes, family))
        }
        val fullRoute = normalized.any { it.prefixLength == 0 && isUnspecified(it.address, family) }
        if (fullRoute) return Result(true, normalized.size)

        val halfRoutes = normalized.filter { it.prefixLength == 1 }.map { parseLiteral(it.address, family).toList() }.toSet()
        val lower = parseLiteral(if (family == IpFamily.IPV4) "0.0.0.0" else "::", family).toList()
        val upper = parseLiteral(if (family == IpFamily.IPV4) "128.0.0.0" else "8000::", family).toList()
        return Result(halfRoutes.contains(lower) && halfRoutes.contains(upper), normalized.size)
    }

    private fun parseLiteral(value: String, family: IpFamily): ByteArray {
        require(value.isNotBlank() && !value.contains('%')) { "Invalid IP literal" }
        // IPv4 must be dotted decimal, IPv6 must contain a colon. This prevents DNS lookup of hostnames.
        if (family == IpFamily.IPV4) {
            val parts = value.split('.')
            require(parts.size == 4 && parts.all { it.isNotEmpty() && it.all(Char::isDigit) }) { "Invalid IPv4 literal" }
            require(parts.all { it.toIntOrNull() in 0..255 }) { "Invalid IPv4 octet" }
        } else {
            require(value.contains(':')) { "Invalid IPv6 literal" }
        }
        val bytes = InetAddress.getByName(value).address
        require(bytes.size == if (family == IpFamily.IPV4) 4 else 16) { "Wrong IP address family" }
        return bytes
    }

    private fun mask(bytes: ByteArray, prefix: Int): ByteArray {
        val result = bytes.copyOf()
        for (index in result.indices) {
            val bitsRemaining = prefix - index * 8
            val mask = when {
                bitsRemaining >= 8 -> 0xff
                bitsRemaining <= 0 -> 0
                else -> (0xff shl (8 - bitsRemaining)) and 0xff
            }
            result[index] = (result[index].toInt() and mask).toByte()
        }
        return result
    }

    private fun isUnspecified(address: String, family: IpFamily): Boolean =
        parseLiteral(address, family).all { it.toInt() == 0 }

    private fun format(bytes: ByteArray, family: IpFamily): String {
        val address = InetAddress.getByAddress(bytes).hostAddress ?: error("Cannot format IP address")
        return if (family == IpFamily.IPV4) address else address.substringBefore('%').lowercase()
    }
}
