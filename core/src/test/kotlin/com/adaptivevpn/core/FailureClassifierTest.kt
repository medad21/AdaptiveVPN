package com.adaptivevpn.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FailureClassifierTest {
    private val classifier = FailureClassifier()
    private fun obs(
        reported: Boolean = true,
        network: Signal = Signal.YES,
        dns: Evidence = Evidence.NONE,
        server: Evidence = Evidence.NONE,
        control: Evidence = Evidence.NONE,
        tunnel: Signal = Signal.UNKNOWN,
        traffic: Evidence = Evidence.NONE,
        handshake: Signal = Signal.UNKNOWN,
        auth: Signal = Signal.UNKNOWN,
        config: Signal = Signal.UNKNOWN,
        loss: Double? = null, lossSamples: Int = 0,
        latency: Long? = null, latencySamples: Int = 0
    ) = ConnectionObservation(
        failureReported = reported, configurationValid = config, deviceNetworkAvailable = network,
        authAccepted = auth, handshakeCompleted = handshake, tunnelEstablished = tunnel,
        dnsLookups = dns, serverProbes = server, controlProbes = control, tunnelTrafficProbes = traffic,
        packetLossRatio = loss, lossSamples = lossSamples, latencyMs = latency, latencySamples = latencySamples
    )

    @Test fun noFailureAndNoEvidenceYieldsNull() {
        assertNull(classifier.classify(obs(reported = false)))
    }

    @Test fun reportedFailureWithoutEvidenceIsUnknownAndUnconfirmed() {
        val f = assertNotNull(classifier.classify(obs()))
        assertEquals(FailureKind.UNKNOWN_FAILURE, f.kind)
        assertFalse(f.confirmed)
    }

    @Test fun invalidConfigurationIsConfirmedAndWinsOverNetworkState() {
        val f = assertNotNull(classifier.classify(obs(config = Signal.NO, network = Signal.NO)))
        assertEquals(FailureKind.CONFIGURATION_INVALID, f.kind)
        assertTrue(f.confirmed)
    }

    @Test fun osReportedNoNetworkIsConfirmed() {
        val f = assertNotNull(classifier.classify(obs(network = Signal.NO)))
        assertEquals(FailureKind.NETWORK_UNAVAILABLE, f.kind)
        assertTrue(f.confirmed)
    }

    @Test fun nothingIndependentReachableIsRestrictedButNeverConfirmed() {
        val f = assertNotNull(classifier.classify(obs(control = Evidence(0, 3), server = Evidence(0, 2))))
        assertEquals(FailureKind.INTERNET_ACCESS_RESTRICTED, f.kind)
        assertFalse(f.confirmed)
    }

    @Test fun controlFailureWithUnknownOsStateIsProbableNetworkUnavailable() {
        val f = assertNotNull(classifier.classify(obs(network = Signal.UNKNOWN, control = Evidence(0, 2))))
        assertEquals(FailureKind.NETWORK_UNAVAILABLE, f.kind)
        assertFalse(f.confirmed)
    }

    @Test fun serverDownWhileControlReachableIsConfirmed() {
        val f = assertNotNull(classifier.classify(obs(server = Evidence(0, 3), control = Evidence(2, 3))))
        assertEquals(FailureKind.SERVER_UNREACHABLE, f.kind)
        assertTrue(f.confirmed)
    }

    @Test fun singleServerProbeIsOnlyProbable() {
        val f = assertNotNull(classifier.classify(obs(server = Evidence(0, 1), control = Evidence(3, 3))))
        assertEquals(FailureKind.SERVER_UNREACHABLE, f.kind)
        assertFalse(f.confirmed)
    }

    @Test fun serverDownWithoutControlDataIsOnlyProbable() {
        val f = assertNotNull(classifier.classify(obs(server = Evidence(0, 3))))
        assertEquals(FailureKind.SERVER_UNREACHABLE, f.kind)
        assertFalse(f.confirmed)
    }

    @Test fun allResolversFailingWhileControlWorksIsConfirmedDns() {
        val f = assertNotNull(classifier.classify(obs(dns = Evidence(0, 2), control = Evidence(2, 2))))
        assertEquals(FailureKind.DNS_RESOLUTION_FAILURE, f.kind)
        assertTrue(f.confirmed)
    }

    @Test fun singleFailedResolverIsNotEnoughToBlameDns() {
        val f = assertNotNull(classifier.classify(obs(dns = Evidence(0, 1), control = Evidence(2, 2))))
        assertEquals(FailureKind.UNKNOWN_FAILURE, f.kind)
    }

    @Test fun authRejectionAndHandshakeAreEngineReportedAndConfirmed() {
        val a = assertNotNull(classifier.classify(obs(auth = Signal.NO)))
        assertEquals(FailureKind.AUTHENTICATION_FAILURE, a.kind)
        assertTrue(a.confirmed)
        val h = assertNotNull(classifier.classify(obs(handshake = Signal.NO)))
        assertEquals(FailureKind.HANDSHAKE_FAILURE, h.kind)
        assertTrue(h.confirmed)
    }

    @Test fun tunnelUpButAllProbesFailIsNoTraffic() {
        val confirmed = assertNotNull(classifier.classify(obs(tunnel = Signal.YES, traffic = Evidence(0, 2), control = Evidence(2, 2))))
        assertEquals(FailureKind.TUNNEL_ESTABLISHED_BUT_NO_TRAFFIC, confirmed.kind)
        assertTrue(confirmed.confirmed)
        val probable = assertNotNull(classifier.classify(obs(tunnel = Signal.YES, traffic = Evidence(0, 2))))
        assertEquals(FailureKind.TUNNEL_ESTABLISHED_BUT_NO_TRAFFIC, probable.kind)
        assertFalse(probable.confirmed)
    }

    @Test fun partiallyWorkingTunnelIsNotNoTraffic() {
        val f = assertNotNull(classifier.classify(obs(tunnel = Signal.YES, traffic = Evidence(1, 2), control = Evidence(2, 2))))
        assertEquals(FailureKind.UNKNOWN_FAILURE, f.kind)
    }

    @Test fun qualityDegradationNeedsEnoughSamples() {
        assertEquals(FailureKind.UNKNOWN_FAILURE, assertNotNull(classifier.classify(obs(loss = 0.5, lossSamples = 2))).kind)
        assertEquals(FailureKind.HIGH_PACKET_LOSS, assertNotNull(classifier.classify(obs(loss = 0.5, lossSamples = 10))).kind)
        assertEquals(FailureKind.HIGH_LATENCY, assertNotNull(classifier.classify(obs(latency = 2000, latencySamples = 10))).kind)
        assertNull(classifier.classify(obs(reported = false, loss = 0.01, lossSamples = 10, latency = 100, latencySamples = 10)))
    }

    @Test fun evidenceCountsAreValidated() {
        assertTrue(runCatching { Evidence(3, 2) }.isFailure)
        assertTrue(runCatching { Evidence(-1, 2) }.isFailure)
    }
}
