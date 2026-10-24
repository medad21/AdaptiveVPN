package com.adaptivevpn.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProtocolRegistryTest {
    private var now = 5_000L
    private val registry = ProtocolRegistry { now }

    private fun entry(id: String = "sing-box", version: String = "1.14.0", license: String = "GPL-3.0-or-later", source: String = "https://github.com/SagerNet/sing-box") =
        RegistryEntry(id, "sing-box", version, source, license, setOf(EngineCapability.IPV4, EngineCapability.IPV6))

    private fun walkToTesting(id: String = "sing-box") {
        assertTrue(registry.transition(id, RegistryStatus.UNDER_REVIEW).ok)
        assertTrue(registry.transition(id, RegistryStatus.VERIFIED).ok)
        assertTrue(registry.transition(id, RegistryStatus.TESTING).ok)
    }

    private fun approvable(id: String = "sing-box", version: String = "1.14.0") {
        registry.register(entry(id, version))
        walkToTesting(id)
        registry.recordSecurity(id, SecurityPosture.APPROVED, "reviewed")
        registry.recordTest(id, TestSummary("t1", version, true, now))
    }

    @Test fun registrationRequiresHttpsSourceAndUniqueId() {
        assertFalse(registry.register(entry(source = "http://example.com")).ok)
        assertTrue(registry.register(entry()).ok)
        assertFalse(registry.register(entry()).ok)
        assertFalse(registry.register(entry(id = "x").copy(status = RegistryStatus.APPROVED)).ok)
    }

    @Test fun newEntryIsDiscoveredAndNotOperational() {
        registry.register(entry())
        assertEquals(RegistryStatus.DISCOVERED, registry.statusOf("sing-box"))
        assertTrue(registry.operational().isEmpty())
    }

    @Test fun cannotJumpDirectlyToApproved() {
        registry.register(entry())
        assertFalse(registry.transition("sing-box", RegistryStatus.APPROVED).ok)
        assertEquals(RegistryStatus.DISCOVERED, registry.statusOf("sing-box"))
    }

    @Test fun fullLifecycleReachesApprovedOnlyWithAllGates() {
        approvable()
        assertTrue(registry.transition("sing-box", RegistryStatus.APPROVED).ok)
        assertTrue(registry.isOperational("sing-box"))
        assertEquals(listOf("sing-box"), registry.operational().map { it.id })
    }

    @Test fun approvalIsBlockedWithoutSecurityApproval() {
        registry.register(entry()); walkToTesting()
        registry.recordTest("sing-box", TestSummary("t", "1.14.0", true, now))
        val r = registry.transition("sing-box", RegistryStatus.APPROVED)
        assertFalse(r.ok); assertTrue(r.message.contains("Security"))
    }

    @Test fun approvalIsBlockedWithoutReviewedLicense() {
        registry.register(entry(license = "UNKNOWN")); walkToTesting()
        registry.recordSecurity("sing-box", SecurityPosture.APPROVED, "ok")
        registry.recordTest("sing-box", TestSummary("t", "1.14.0", true, now))
        val r = registry.transition("sing-box", RegistryStatus.APPROVED)
        assertFalse(r.ok); assertTrue(r.message.contains("License"))
    }

    @Test fun approvalIsBlockedWithoutOrWithFailingTestForCurrentVersion() {
        registry.register(entry()); walkToTesting()
        registry.recordSecurity("sing-box", SecurityPosture.APPROVED, "ok")
        assertFalse(registry.transition("sing-box", RegistryStatus.APPROVED).ok)
        registry.recordTest("sing-box", TestSummary("t", "1.13.0", true, now))
        assertFalse(registry.transition("sing-box", RegistryStatus.APPROVED).ok)
        registry.recordTest("sing-box", TestSummary("t", "1.14.0", false, now))
        assertFalse(registry.transition("sing-box", RegistryStatus.APPROVED).ok)
        registry.recordTest("sing-box", TestSummary("t2", "1.14.0", true, now))
        assertTrue(registry.transition("sing-box", RegistryStatus.APPROVED).ok)
    }

    @Test fun disablingDeprecatingAndRejectingRequireAReason() {
        approvable(); registry.transition("sing-box", RegistryStatus.APPROVED)
        assertFalse(registry.transition("sing-box", RegistryStatus.DISABLED).ok)
        assertFalse(registry.transition("sing-box", RegistryStatus.DISABLED, "  ").ok)
        assertTrue(registry.transition("sing-box", RegistryStatus.DISABLED, "regression found").ok)
        assertEquals("regression found", registry.get("sing-box")?.statusReason)
        assertTrue(registry.operational().isEmpty())
    }

    @Test fun rejectedAndDeprecatedAreTerminal() {
        registry.register(entry("a")); registry.transition("a", RegistryStatus.REJECTED, "bad license")
        assertFalse(registry.transition("a", RegistryStatus.UNDER_REVIEW).ok)
    }

    @Test fun newVersionRemovesEntryFromOperationalUseUntilReReviewed() {
        approvable(); registry.transition("sing-box", RegistryStatus.APPROVED)
        assertTrue(registry.updateVersion("sing-box", "1.15.0").ok)
        val e = assertNotNull(registry.get("sing-box"))
        assertEquals(RegistryStatus.UNDER_REVIEW, e.status)
        assertEquals(SecurityPosture.UNREVIEWED, e.security)
        assertFalse(registry.isOperational("sing-box"))
        // The old version's passing test must not carry over.
        registry.transition("sing-box", RegistryStatus.VERIFIED); registry.transition("sing-box", RegistryStatus.TESTING)
        registry.recordSecurity("sing-box", SecurityPosture.APPROVED, "re-reviewed")
        assertFalse(registry.transition("sing-box", RegistryStatus.APPROVED).ok)
        registry.recordTest("sing-box", TestSummary("t", "1.15.0", true, now))
        assertTrue(registry.transition("sing-box", RegistryStatus.APPROVED).ok)
    }

    @Test fun securityFailureImmediatelyDisablesAnApprovedEntry() {
        approvable(); registry.transition("sing-box", RegistryStatus.APPROVED)
        registry.recordSecurity("sing-box", SecurityPosture.FAILED, "CVE found")
        assertEquals(RegistryStatus.DISABLED, registry.statusOf("sing-box"))
        assertTrue(registry.operational().isEmpty())
    }

    @Test fun unknownEntriesAreRejectedGracefully() {
        assertFalse(registry.transition("nope", RegistryStatus.UNDER_REVIEW).ok)
        assertFalse(registry.recordTest("nope", TestSummary("t", "1", true, now)).ok)
        assertNull(registry.get("nope"))
        assertFalse(registry.isOperational("nope"))
    }

    @Test fun everyChangeIsAudited() {
        registry.register(entry()); now = 6_000
        registry.transition("sing-box", RegistryStatus.UNDER_REVIEW)
        val log = registry.auditLog()
        assertEquals(listOf("register", "transition"), log.map { it.action })
        assertEquals(6_000L, log.last().atMs)
    }
}
