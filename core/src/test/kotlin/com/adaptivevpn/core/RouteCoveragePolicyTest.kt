package com.adaptivevpn.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class RouteCoveragePolicyTest {
    @Test fun acceptsCanonicalIpv4DefaultRoute() {
        val result = RouteCoveragePolicy.evaluate(listOf(IpRoutePrefix("0.0.0.0", 0)), IpFamily.IPV4)
        assertTrue(result.coversEntireFamily)
        assertEquals(1, result.validatedCount)
    }

    @Test fun acceptsTwoIpv4HalfRoutesAsFullCoverage() {
        val result = RouteCoveragePolicy.evaluate(
            listOf(IpRoutePrefix("0.0.0.0", 1), IpRoutePrefix("128.0.0.0", 1)), IpFamily.IPV4
        )
        assertTrue(result.coversEntireFamily)
    }

    @Test fun acceptsIpv6DefaultRoute() {
        assertTrue(RouteCoveragePolicy.evaluate(listOf(IpRoutePrefix("::", 0)), IpFamily.IPV6).coversEntireFamily)
    }

    @Test fun rejectsHostnameRatherThanResolvingIt() {
        assertFailsWith<IllegalArgumentException> {
            RouteCoveragePolicy.evaluate(listOf(IpRoutePrefix("example.com", 0)), IpFamily.IPV4)
        }
    }

    @Test fun rejectsNonCanonicalNetworkPrefix() {
        assertFailsWith<IllegalArgumentException> {
            RouteCoveragePolicy.evaluate(listOf(IpRoutePrefix("10.0.0.1", 24)), IpFamily.IPV4)
        }
    }

    @Test fun partialRouteDoesNotClaimFullCoverage() {
        val result = RouteCoveragePolicy.evaluate(listOf(IpRoutePrefix("10.0.0.0", 8)), IpFamily.IPV4)
        assertFalse(result.coversEntireFamily)
    }

    @Test fun rejectsWrongFamilyAndInvalidPrefix() {
        assertFailsWith<IllegalArgumentException> {
            RouteCoveragePolicy.evaluate(listOf(IpRoutePrefix("2001:db8::", 32)), IpFamily.IPV4)
        }
        assertFailsWith<IllegalArgumentException> {
            RouteCoveragePolicy.evaluate(listOf(IpRoutePrefix("0.0.0.0", 33)), IpFamily.IPV4)
        }
    }
}
