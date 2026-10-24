package com.adaptivevpn.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AdaptiveConnectionManagerTest {
    private val manager = AdaptiveConnectionManager()
    private fun route(id: String, status: RegistryStatus = RegistryStatus.APPROVED, secure: Boolean = true, compatible: Boolean = true, rate: Double = .9, latency: Long = 100) =
        RouteCandidate(id, "test-engine", status, secure, compatible, rate, latency, 500)

    @Test fun rejectsUnapprovedOrUnsafeRoutes() {
        val selected = manager.selectCandidates(listOf(route("ok"), route("unsafe", secure = false), route("review", status = RegistryStatus.UNDER_REVIEW)))
        assertEquals(listOf("ok"), selected.map { it.routeId })
    }

    @Test fun capsCandidateCount() {
        val selected = manager.selectCandidates((1..5).map { route("r$it") })
        assertEquals(3, selected.size)
    }

    @Test fun excludesActiveRouteDuringRecovery() {
        val selected = manager.recoveryPlan(listOf(route("active"), route("backup")), "active")
        assertEquals(listOf("backup"), selected.map { it.routeId })
    }

    @Test fun unknownFailureIsNeverMarkedConfirmed() {
        val failure = manager.classifyFailure(null, true)
        assertEquals(FailureKind.UNKNOWN_FAILURE, failure.kind)
        assertFalse(failure.confirmed)
    }

    @Test fun validRoutesReceiveFiniteScores() {
        assertTrue(manager.score(route("a")).isFinite())
    }
}
