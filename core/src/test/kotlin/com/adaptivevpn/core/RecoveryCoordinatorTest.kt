package com.adaptivevpn.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class RecoveryCoordinatorTest {
    private var now = 1_000_000L
    private fun coordinator(config: RecoveryConfig = RecoveryConfig()) =
        RecoveryCoordinator(AdaptiveConnectionManager(), config, { now }, { 0.5 })

    private fun route(id: String, rate: Double = .9, status: RegistryStatus = RegistryStatus.APPROVED, secure: Boolean = true) =
        RouteCandidate(id, "engine", status, secure, true, rate, 100, 500)

    private fun failure(kind: FailureKind, confirmed: Boolean = true) = ConnectionFailure(kind, confirmed, "test")

    // ---- Backoff
    @Test fun backoffGrowsExponentiallyAndIsCapped() {
        val b = BackoffPolicy(baseMs = 1000, factor = 2.0, maxMs = 10_000, jitterRatio = 0.0)
        assertEquals(listOf(1000L, 2000L, 4000L, 8000L, 10_000L, 10_000L), (1..6).map { b.delayMs(it) })
        assertEquals(10_000L, b.delayMs(10_000))
    }

    @Test fun backoffJitterStaysWithinBounds() {
        val b = BackoffPolicy(baseMs = 1000, factor = 2.0, maxMs = 10_000, jitterRatio = 0.2)
        assertEquals(800L, b.delayMs(1, 0.0))
        assertTrue(b.delayMs(1, 0.999999999) in 1199L..1200L)
        for (attempt in 1..30) for (r in listOf(0.0, 0.3, 0.99)) assertTrue(b.delayMs(attempt, r) in 1..10_000)
    }

    @Test fun backoffRejectsInvalidInput() {
        assertTrue(runCatching { BackoffPolicy(baseMs = 0) }.isFailure)
        assertTrue(runCatching { BackoffPolicy(baseMs = 10, maxMs = 5) }.isFailure)
        assertTrue(runCatching { BackoffPolicy().delayMs(0) }.isFailure)
        assertTrue(runCatching { BackoffPolicy().delayMs(1, 1.0) }.isFailure)
    }

    // ---- Circuit breaker
    @Test fun breakerOpensAfterThresholdThenAllowsSingleTrial() {
        val cb = CircuitBreaker(failureThreshold = 2, defaultOpenMs = 1000)
        cb.recordFailure("r", 0)
        assertTrue(cb.canAttempt("r", 0))
        cb.recordFailure("r", 0)
        assertEquals(CircuitBreaker.State.OPEN, cb.state("r", 500))
        assertFalse(cb.canAttempt("r", 500))
        assertEquals(CircuitBreaker.State.HALF_OPEN, cb.state("r", 1000))
        assertTrue(cb.canAttempt("r", 1000))
        cb.beginAttempt("r", 1000)
        assertFalse(cb.canAttempt("r", 1001))
    }

    @Test fun breakerReopensOnHalfOpenFailureAndClosesOnSuccess() {
        val cb = CircuitBreaker(failureThreshold = 1, defaultOpenMs = 1000)
        cb.recordFailure("r", 0)
        cb.beginAttempt("r", 1000)
        cb.recordFailure("r", 1000)
        assertEquals(CircuitBreaker.State.OPEN, cb.state("r", 1500))
        cb.beginAttempt("r", 2000)
        cb.recordSuccess("r")
        assertEquals(CircuitBreaker.State.CLOSED, cb.state("r", 2000))
    }

    @Test fun breakerForceOpenBlocksImmediately() {
        val cb = CircuitBreaker(failureThreshold = 5, defaultOpenMs = 1000)
        cb.recordFailure("r", 0, forceOpenMs = 10_000)
        assertFalse(cb.canAttempt("r", 9_999))
        assertTrue(cb.canAttempt("r", 10_000))
    }

    // ---- Coordinator
    @Test fun networkLossWaitsForOsEventAndDoesNotBlameTheRoute() {
        val c = coordinator()
        c.beginRecovery(failure(FailureKind.NETWORK_UNAVAILABLE), "a")
        assertEquals(RecoveryDecision.WaitForNetworkChange, c.nextStep(listOf(route("a"))))
        assertEquals(CircuitBreaker.State.CLOSED, c.circuitState("a"))
    }

    @Test fun transientFailurePrefersUntriedAlternativeWithoutDelay() {
        val c = coordinator()
        c.beginRecovery(failure(FailureKind.HANDSHAKE_FAILURE), "a")
        val d = assertIs<RecoveryDecision.TryRoute>(c.nextStep(listOf(route("a", .99), route("b", .5))))
        // "a" is still allowed (breaker threshold not reached) but "b" is untried, so b goes first.
        assertEquals("b", d.route.routeId)
        assertEquals(0L, d.delayBeforeMs)
    }

    @Test fun retryingAnAlreadyTriedRouteUsesBackoffDelay() {
        val c = coordinator()
        c.beginRecovery(failure(FailureKind.SERVER_UNREACHABLE), null)
        val first = assertIs<RecoveryDecision.TryRoute>(c.nextStep(listOf(route("a"))))
        assertEquals(0L, first.delayBeforeMs)
        c.reportAttempt("a", false, failure(FailureKind.SERVER_UNREACHABLE))
        val second = assertIs<RecoveryDecision.TryRoute>(c.nextStep(listOf(route("a"))))
        assertTrue(second.delayBeforeMs > 0)
        assertEquals(20_000L, second.timeoutMs)
    }

    @Test fun recoveryAlwaysTerminatesWithinAttemptLimit() {
        val config = RecoveryConfig(maxAttemptsPerEpisode = 6, breakerFailureThreshold = 100)
        val c = coordinator(config)
        c.beginRecovery(failure(FailureKind.HANDSHAKE_FAILURE), "a")
        var tries = 0
        var last: RecoveryDecision = RecoveryDecision.WaitAndRetry(0)
        while (tries < 100) {
            last = c.nextStep(listOf(route("a"), route("b")))
            if (last !is RecoveryDecision.TryRoute) break
            tries++
            c.reportAttempt(last.route.routeId, false, failure(FailureKind.HANDSHAKE_FAILURE))
        }
        assertEquals(6, tries)
        assertIs<RecoveryDecision.GiveUp>(last)
        assertFalse(c.recovering)
    }

    @Test fun recoveryAlsoStopsAfterTimeLimit() {
        val c = coordinator(RecoveryConfig(maxEpisodeDurationMs = 60_000))
        c.beginRecovery(failure(FailureKind.HANDSHAKE_FAILURE), null)
        now += 60_000
        assertIs<RecoveryDecision.GiveUp>(c.nextStep(listOf(route("a"))))
    }

    @Test fun confirmedAuthenticationFailureBlocksThatRouteButNotOthers() {
        val c = coordinator()
        c.beginRecovery(failure(FailureKind.AUTHENTICATION_FAILURE), "a")
        assertEquals(CircuitBreaker.State.OPEN, c.circuitState("a"))
        val d = assertIs<RecoveryDecision.TryRoute>(c.nextStep(listOf(route("a", .99), route("b", .4))))
        assertEquals("b", d.route.routeId)
        c.reportAttempt("b", false, failure(FailureKind.AUTHENTICATION_FAILURE))
        assertIs<RecoveryDecision.GiveUp>(c.nextStep(listOf(route("a"), route("b"))))
    }

    @Test fun unconfirmedPermanentKindIsOnlyCountedNotBlocked() {
        val c = coordinator()
        c.beginRecovery(failure(FailureKind.AUTHENTICATION_FAILURE, confirmed = false), "a")
        assertEquals(CircuitBreaker.State.CLOSED, c.circuitState("a"))
    }

    @Test fun unapprovedOrUnsafeRoutesAreNeverTried() {
        val c = coordinator()
        c.beginRecovery(failure(FailureKind.HANDSHAKE_FAILURE), null)
        val d = c.nextStep(listOf(route("x", status = RegistryStatus.UNDER_REVIEW), route("y", secure = false)))
        assertIs<RecoveryDecision.GiveUp>(d)
    }

    @Test fun restrictedInternetPollsWithBoundedBackoffThenGivesUp() {
        val c = coordinator(RecoveryConfig(maxAttemptsPerEpisode = 3))
        c.beginRecovery(failure(FailureKind.INTERNET_ACCESS_RESTRICTED, confirmed = false), "a")
        repeat(3) { assertIs<RecoveryDecision.WaitAndRetry>(c.nextStep(listOf(route("a")))) }
        assertIs<RecoveryDecision.GiveUp>(c.nextStep(listOf(route("a"))))
        assertEquals(CircuitBreaker.State.CLOSED, c.circuitState("a"))
    }

    @Test fun successEndsEpisode() {
        val c = coordinator()
        c.beginRecovery(failure(FailureKind.HANDSHAKE_FAILURE), "a")
        val d = assertIs<RecoveryDecision.TryRoute>(c.nextStep(listOf(route("a"), route("b"))))
        c.reportAttempt(d.route.routeId, true)
        assertFalse(c.recovering)
    }

    // ---- Hysteresis
    @Test fun qualitySwitchRequiresMarginDwellAndRateLimit() {
        val c = coordinator(RecoveryConfig(minDwellMs = 60_000, minScoreImprovement = 10.0, maxSwitchesPerWindow = 2, switchWindowMs = 600_000))
        assertFalse(c.evaluateQualitySwitch(70.0, "b", 75.0).allowed)
        assertTrue(c.evaluateQualitySwitch(70.0, "b", 85.0).allowed)

        c.recordQualitySwitchCompleted()
        now += 10_000
        assertFalse(c.evaluateQualitySwitch(70.0, "b", 95.0).allowed)
        now += 60_000
        assertTrue(c.evaluateQualitySwitch(70.0, "b", 95.0).allowed)

        c.recordQualitySwitchCompleted()
        now += 61_000
        assertFalse(c.evaluateQualitySwitch(70.0, "b", 95.0).allowed)
        now += 600_000
        assertTrue(c.evaluateQualitySwitch(70.0, "b", 95.0).allowed)
    }

    @Test fun qualitySwitchNeverTargetsABlockedRoute() {
        val c = coordinator()
        c.beginRecovery(failure(FailureKind.AUTHENTICATION_FAILURE), "b")
        assertFalse(c.evaluateQualitySwitch(10.0, "b", 99.0).allowed)
    }

    @Test fun configRejectsUnboundedAttempts() {
        assertTrue(runCatching { RecoveryConfig(maxAttemptsPerEpisode = 0) }.isFailure)
        assertTrue(runCatching { RecoveryConfig(maxAttemptsPerEpisode = 1000) }.isFailure)
    }
}
