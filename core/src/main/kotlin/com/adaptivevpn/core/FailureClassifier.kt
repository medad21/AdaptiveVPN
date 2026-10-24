package com.adaptivevpn.core

/** Tri-state fact. UNKNOWN means "not measured", never "probably fine". */
enum class Signal { YES, NO, UNKNOWN }

/** Outcome of a group of independent checks (e.g. several resolvers or several endpoints). */
data class Evidence(val passed: Int, val total: Int) {
    init { require(total >= 0 && passed in 0..total) { "invalid evidence counts" } }
    val tested: Boolean get() = total > 0
    val allFailed: Boolean get() = total > 0 && passed == 0
    val anyPassed: Boolean get() = passed > 0

    companion object { val NONE = Evidence(0, 0) }
}

/**
 * Raw facts gathered by the Android/engine adapters. All fields default to "not measured".
 *
 * - [serverProbes]: direct reachability of the VPN server, measured outside the tunnel.
 * - [controlProbes]: independent, unrelated destinations reached outside the tunnel. They separate
 *   "the target is down" from "the whole network is down".
 * - [tunnelTrafficProbes]: probes sent through the established tunnel.
 */
data class ConnectionObservation(
    val failureReported: Boolean,
    val configurationValid: Signal = Signal.UNKNOWN,
    val transportSupported: Signal = Signal.UNKNOWN,
    val engineAlive: Signal = Signal.UNKNOWN,
    val deviceNetworkAvailable: Signal = Signal.UNKNOWN,
    val authAccepted: Signal = Signal.UNKNOWN,
    val handshakeCompleted: Signal = Signal.UNKNOWN,
    val tunnelEstablished: Signal = Signal.UNKNOWN,
    val dnsLookups: Evidence = Evidence.NONE,
    val serverProbes: Evidence = Evidence.NONE,
    val controlProbes: Evidence = Evidence.NONE,
    val tunnelTrafficProbes: Evidence = Evidence.NONE,
    val packetLossRatio: Double? = null,
    val lossSamples: Int = 0,
    val latencyMs: Long? = null,
    val latencySamples: Int = 0
)

data class ClassifierThresholds(
    /** Minimum number of independent checks before a group result is trusted as confirmed. */
    val minIndependentProbes: Int = 2,
    val highLossRatio: Double = 0.15,
    val highLatencyMs: Long = 800,
    val minQualitySamples: Int = 5
) {
    init {
        require(minIndependentProbes >= 1)
        require(highLossRatio in 0.0..1.0)
        require(highLatencyMs > 0)
        require(minQualitySamples >= 1)
    }
}

/**
 * Maps observations to a [FailureKind]. `confirmed = true` only when the evidence directly shows the cause;
 * otherwise the result is a probable cause. The classifier never claims DPI or censorship was detected.
 */
class FailureClassifier(private val thresholds: ClassifierThresholds = ClassifierThresholds()) {

    /** Returns null when nothing indicates a failure and none was reported. */
    fun classify(o: ConnectionObservation): ConnectionFailure? {
        val min = thresholds.minIndependentProbes

        // 1. Deterministic local facts.
        if (o.configurationValid == Signal.NO) return confirmed(FailureKind.CONFIGURATION_INVALID, "Configuration was rejected by the validator.")
        if (o.transportSupported == Signal.NO) return confirmed(FailureKind.UNSUPPORTED_TRANSPORT, "The engine or platform does not support the requested transport.")
        if (o.engineAlive == Signal.NO) return confirmed(FailureKind.ENGINE_FAILURE, "The engine process or service stopped unexpectedly.")
        if (o.deviceNetworkAvailable == Signal.NO) return confirmed(FailureKind.NETWORK_UNAVAILABLE, "The operating system reports no usable network.")

        // 2. The server explicitly rejected our credentials (proves the server was reached).
        if (o.authAccepted == Signal.NO) return confirmed(FailureKind.AUTHENTICATION_FAILURE, "The engine reported that authentication was rejected.")

        // 3. Everything independent is unreachable: this is not a problem with one route.
        if (o.controlProbes.total >= min && o.controlProbes.allFailed) {
            return if (o.deviceNetworkAvailable == Signal.YES) {
                probable(
                    FailureKind.INTERNET_ACCESS_RESTRICTED,
                    "The device reports a network, but no independent destination was reachable. " +
                        "Possible causes include a captive portal, no uplink, or access restrictions; the exact cause is not determined."
                )
            } else {
                probable(FailureKind.NETWORK_UNAVAILABLE, "No independent destination was reachable and the OS network state is unknown.")
            }
        }
        val controlReachable = o.controlProbes.anyPassed

        // 4. Name resolution.
        if (o.dnsLookups.total >= min && o.dnsLookups.allFailed) {
            return result(
                FailureKind.DNS_RESOLUTION_FAILURE, controlReachable,
                if (controlReachable) "All resolvers failed while independent destinations were reachable."
                else "All resolvers failed; general connectivity was not independently confirmed."
            )
        }

        // 5. The server itself.
        if (o.serverProbes.allFailed) {
            val confirmed = o.serverProbes.total >= min && controlReachable
            return result(
                FailureKind.SERVER_UNREACHABLE, confirmed,
                if (confirmed) "The server did not answer repeated probes while independent destinations were reachable."
                else "The server did not answer, but there is not enough independent evidence to rule out a local network problem."
            )
        }

        // 6. Protocol-level failures reported by the engine.
        if (o.handshakeCompleted == Signal.NO) return confirmed(FailureKind.HANDSHAKE_FAILURE, "The engine reported that the handshake did not complete.")

        // 7. Tunnel is up but data does not flow.
        if (o.tunnelEstablished == Signal.YES && o.tunnelTrafficProbes.allFailed) {
            val confirmed = o.tunnelTrafficProbes.total >= min && controlReachable
            return result(
                FailureKind.TUNNEL_ESTABLISHED_BUT_NO_TRAFFIC, confirmed,
                if (confirmed) "The tunnel is established but every probe through it failed while direct connectivity works."
                else "The tunnel is established but probes through it failed; direct connectivity was not confirmed."
            )
        }

        // 8. Measured quality degradation.
        val loss = o.packetLossRatio
        if (loss != null && o.lossSamples >= thresholds.minQualitySamples && loss >= thresholds.highLossRatio) {
            return confirmed(FailureKind.HIGH_PACKET_LOSS, "Measured packet loss is above the configured threshold.")
        }
        val latency = o.latencyMs
        if (latency != null && o.latencySamples >= thresholds.minQualitySamples && latency >= thresholds.highLatencyMs) {
            return confirmed(FailureKind.HIGH_LATENCY, "Measured latency is above the configured threshold.")
        }

        return if (o.failureReported) {
            ConnectionFailure(FailureKind.UNKNOWN_FAILURE, false, "A failure was reported but the evidence does not identify a cause.")
        } else null
    }

    private fun confirmed(kind: FailureKind, message: String) = ConnectionFailure(kind, true, message)
    private fun probable(kind: FailureKind, message: String) = ConnectionFailure(kind, false, message)
    private fun result(kind: FailureKind, confirmed: Boolean, message: String) = ConnectionFailure(kind, confirmed, message)
}
