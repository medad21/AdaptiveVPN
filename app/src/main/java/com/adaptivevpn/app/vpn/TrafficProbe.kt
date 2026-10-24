package com.adaptivevpn.app.vpn

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Proxy
import java.net.URI
import java.net.URLConnection
import java.net.HttpURLConnection
import javax.net.ssl.HttpsURLConnection

/**
 * Small post-start HTTPS probe. It intentionally does not claim to prove absence of traffic leaks;
 * it only verifies that this app can reach two independent HTTPS endpoints while the VPN is active.
 * The response body and any public IP returned by the endpoint are never persisted or logged.
 */
internal object TrafficProbe {
    internal data class Result(val passed: Boolean, val summary: String)
    private data class Endpoint(val id: String, val url: String)

    private val endpoints = listOf(
        Endpoint("google", "https://connectivitycheck.gstatic.com/generate_204"),
        Endpoint("cloudflare", "https://www.cloudflare.com/cdn-cgi/trace")
    )

    fun run(connectTimeoutMs: Int = 5_000, readTimeoutMs: Int = 5_000): Result {
        require(connectTimeoutMs in 1..15_000 && readTimeoutMs in 1..15_000) {
            "Timeout must be between 1 and 15000 ms"
        }
        val outcomes = endpoints.map { endpoint ->
            val connection = try {
                URI(endpoint.url).toURL().openConnection(Proxy.NO_PROXY).also {
                    it.connectTimeout = connectTimeoutMs
                    it.readTimeout = readTimeoutMs
                    it.useCaches = false
                }
            } catch (_: Exception) {
                return@map endpoint.id to "connection setup failed"
            }
            try {
                val https = connection as? HttpsURLConnection
                    ?: return@map endpoint.id to "HTTPS required"
                https.instanceFollowRedirects = false
                https.requestMethod = "GET"
                https.setRequestProperty("User-Agent", "AdaptiveVPN-ConnectivityProbe/0.15")
                https.setRequestProperty("Connection", "close")
                val status = https.responseCode
                val body = if (status in 200..299 && endpoint.id == "cloudflare") {
                    https.inputStream.use { stream ->
                        BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { reader ->
                            val chars = CharArray(4096)
                            val count = reader.read(chars, 0, chars.size).coerceAtLeast(0)
                            String(chars, 0, count)
                        }
                    }
                } else ""
                if (isExpectedResponse(endpoint.id, status, body)) endpoint.id to "ok"
                else endpoint.id to "unexpected HTTP response"
            } catch (_: java.net.SocketTimeoutException) {
                endpoint.id to "timeout"
            } catch (_: javax.net.ssl.SSLException) {
                endpoint.id to "TLS validation failed"
            } catch (_: java.net.UnknownHostException) {
                endpoint.id to "DNS lookup failed"
            } catch (_: Exception) {
                endpoint.id to "network request failed"
            } finally {
                (connection as? HttpURLConnection)?.disconnect()
            }
        }
        val passed = outcomes.all { it.second == "ok" }
        val summary = outcomes.joinToString(separator = "; ") { (name, result) -> "$name: $result" }
        return Result(passed, summary)
    }

    internal fun isExpectedResponse(endpointId: String, statusCode: Int, body: String): Boolean = when (endpointId) {
        "google" -> statusCode == 204
        "cloudflare" -> statusCode == 200 && body.lineSequence().any { it.startsWith("ip=") }
        else -> false
    }
}
