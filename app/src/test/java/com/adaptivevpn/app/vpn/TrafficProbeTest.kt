package com.adaptivevpn.app.vpn

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrafficProbeTest {
    @Test fun acceptsGoogle204Only() {
        assertTrue(TrafficProbe.isExpectedResponse("google", 204, ""))
        assertFalse(TrafficProbe.isExpectedResponse("google", 200, "ok"))
        assertFalse(TrafficProbe.isExpectedResponse("google", 302, ""))
    }

    @Test fun acceptsCloudflareTraceOnlyWhenTraceBodyIsPresent() {
        assertTrue(TrafficProbe.isExpectedResponse("cloudflare", 200, "fl=abc\nip=203.0.113.4\n"))
        assertFalse(TrafficProbe.isExpectedResponse("cloudflare", 200, "captive portal"))
        assertFalse(TrafficProbe.isExpectedResponse("cloudflare", 403, "ip=203.0.113.4"))
    }

    @Test fun rejectsUnknownEndpoint() {
        assertFalse(TrafficProbe.isExpectedResponse("unknown", 200, "ip=203.0.113.4"))
    }
}
