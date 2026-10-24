package com.adaptivevpn.app.profile

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SingBoxProfileValidatorTest {
    private val validTun = """
        {
          "inbounds": [{
            "type": "tun",
            "tag": "tun-in",
            "address": ["172.19.0.1/30", "fdfe:dcba:9876::1/126"],
            "auto_route": true
          }],
          "outbounds": [{"type": "direct", "tag": "direct"}]
        }
    """.trimIndent()

    @Test fun acceptsBasicAndroidTunProfile() {
        assertTrue(SingBoxProfileValidator.validate(validTun).valid)
    }

    @Test fun rejectsNonTunInbound() {
        val input = validTun.replace("\"type\": \"tun\"", "\"type\": \"socks\"")
        assertFalse(SingBoxProfileValidator.validate(input).valid)
    }

    @Test fun rejectsTunWithoutAutoRoute() {
        val input = validTun.replace("\"auto_route\": true", "\"auto_route\": false")
        assertFalse(SingBoxProfileValidator.validate(input).valid)
    }

    @Test fun rejectsProcessNameRoutingUntilProcessResolverExists() {
        val input = validTun.replace(
            "\"outbounds\":",
            "\"route\": {\"rules\": [{\"process_name\": [\"browser\"]}]}, \"outbounds\":"
        )
        assertFalse(SingBoxProfileValidator.validate(input).valid)
    }

    @Test fun rejectsUnsupportedPackageRoutingOnTun() {
        val input = validTun.replace(
            "\"auto_route\": true",
            "\"auto_route\": true, \"include_package\": [\"com.example.browser\"]"
        )
        assertFalse(SingBoxProfileValidator.validate(input).valid)
    }

    @Test fun rejectsStrictRouteUntilImplemented() {
        val input = validTun.replace(
            "\"auto_route\": true",
            "\"auto_route\": true, \"strict_route\": true"
        )
        assertFalse(SingBoxProfileValidator.validate(input).valid)
    }

    @Test fun rejectsExcludeRoutesThatAndroidBuilderWouldOtherwiseIgnore() {
        val input = validTun.replace(
            "\"auto_route\": true",
            "\"auto_route\": true, \"inet4_route_exclude_address\": [\"192.168.0.0/16\"]"
        )
        assertFalse(SingBoxProfileValidator.validate(input).valid)
    }

    @Test fun rejectsModernRouteExcludeOptionsUntilImplemented() {
        val input = validTun.replace(
            "\"auto_route\": true",
            "\"auto_route\": true, \"route_exclude_address\": [\"192.168.0.0/16\"]"
        )
        assertFalse(SingBoxProfileValidator.validate(input).valid)
    }

    @Test fun rejectsEnabledTunHttpProxyUntilAndroidProxyIsWired() {
        val input = validTun.replace(
            "\"auto_route\": true",
            "\"auto_route\": true, \"platform\": {\"http_proxy\": {\"enabled\": true, \"server\": \"127.0.0.1\", \"server_port\": 8080}}"
        )
        assertFalse(SingBoxProfileValidator.validate(input).valid)
    }

    @Test fun rejectsMalformedJson() {
        assertFalse(SingBoxProfileValidator.validate("{not-json").valid)
    }

    @Test fun rejectsMultipleTunInbounds() {
        val input = validTun.replace(
            "  }],\n  \"outbounds\":",
            "  }, {\n    \"type\": \"tun\",\n    \"address\": [\"172.19.0.2/30\", \"fdfe:dcba:9876::2/126\"],\n    \"auto_route\": true\n  }],\n  \"outbounds\":"
        )
        assertTrue(input.contains("172.19.0.2/30"))
        assertFalse(SingBoxProfileValidator.validate(input).valid)
    }

    @Test fun rejectsUidBasedTunOptions() {
        val input = validTun.replace(
            "\"auto_route\": true",
            "\"auto_route\": true, \"include_uid\": [1000]"
        )
        assertFalse(SingBoxProfileValidator.validate(input).valid)
    }

    @Test fun rejectsOversizedProfile() {
        val input = validTun + " ".repeat(SingBoxProfileValidator.MAX_PROFILE_BYTES)
        assertFalse(SingBoxProfileValidator.validate(input).valid)
    }

    @Test fun rejectsEmptyTunAddressConfiguration() {
        val input = validTun.replace(
            "\"address\": [\"172.19.0.1/30\", \"fdfe:dcba:9876::1/126\"]",
            "\"address\": []"
        )
        assertFalse(SingBoxProfileValidator.validate(input).valid)
    }

    @Test fun rejectsProfileWithoutIpv6AddressToPreventFamilyBypass() {
        val input = validTun.replace(", \"fdfe:dcba:9876::1/126\"", "")
        assertFalse(SingBoxProfileValidator.validate(input).valid)
    }

    @Test fun rejectsDisabledDnsModeForInitialVpnBuild() {
        val input = validTun.replace(
            "\"auto_route\": true",
            "\"auto_route\": true, \"dns_mode\": \"disabled\""
        )
        assertFalse(SingBoxProfileValidator.validate(input).valid)
    }
}
