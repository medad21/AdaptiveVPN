package com.adaptivevpn.core

import kotlin.math.min
import kotlin.math.pow

/** How a failure kind should influence recovery. */
enum class Retryability {
    /** Switching routes cannot help; wait for the OS to report a network change. */
    WAIT_FOR_NETWORK_CHANGE,
    /** Whole-internet problem that the OS cannot signal; poll with bounded backoff, do not blame routes. */
    WAIT_WITH_BACKOFF,
    /** Retrying or rotating may help. */
    TRANSIENT,
    /** Retrying the same route will not help (bad credentials, bad config, unsupported transport). */
    ROUTE_PERMANENT
}

fun FailureKind.retryability(): Retryability = when (this) {
    FailureKind.NETWORK_UNAVAILABLE -> Retryability.WAIT_FOR_NETWORK_CHANGE
    FailureKind.INTERNET_ACCESS_RESTRICTED -> Retryability.WAIT_WITH_BACKOFF
    FailureKind.AUTHENTICATION_FAILURE,
    FailureKind.CONFIGURATION_INVALID,
    FailureKind.UNSUPPORTED_TRANSPORT -> Retryability.ROUTE_PERMANENT
    FailureKind.DNS_RESOLUTION_FAILURE,
    FailureKind.SERVER_UNREACHABLE,
    FailureKind.HANDSHAKE_FAILURE,
    FailureKind.TUNNEL_ESTABLISHED_BUT_NO_TRAFFIC,
    FailureKind.HIGH_PACKET_LOSS,
    FailureKind.HIGH_LATENCY,
    FailureKind.ENGINE_FAILURE,
    FailureKind.UNKNOWN_FAILURE -> Retryability.TRANSIENT
}

/** Exponential backoff with symmetric jitter. Always bounded by [maxMs]. */
data class BackoffPolicy(
    val baseMs: Long = 2_000,
    val factor: Double = 2.0,
    val maxMs: Long = 300_000,
    val jitterRatio: Double = 0.2
) {
    init {
        require(baseMs > 0) { "baseMs must be positive" }
        require(factor >= 1.0) { "factor must be >= 1" }
        require(maxMs >= baseMs) { "maxMs must be >= baseMs" }
        require(jitterRatio in 0.0..1.0) { "jitterRatio must be in 0..1" }
    }

    /** [attempt] starts at 1. [random01] must be in [0, 1). */
    fun delayMs(attempt: Int, random01: Double = 0.5): Long {
        require(attempt >= 1) { "attempt starts at 1" }
        require(random01 >= 0.0 && random01 < 1.0) { "random01 must be in [0,1)" }
        val raw = min(maxMs.toDouble(), baseMs * factor.pow(attempt - 1))
        val jitter = 1.0 + jitterRatio * (2.0 * random01 - 1.0)
        return (raw * jitter).toLong().coerceIn(1L, maxMs)
    }
}

/**
 * Per-route circuit breaker. A route that keeps failing is skipped until its open period elapses;
 * then exactly one trial attempt is allowed (half-open). Not thread-safe: use from one coroutine/thread.
 */
class CircuitBreaker(private val failureThreshold: Int = 3, private val defaultOpenMs: Long = 120_000) {
    enum class State { CLOSED, OPEN, HALF_OPEN }

    init {
        require(failureThreshold >= 1)
        require(defaultOpenMs > 0)
    }

    private class Entry(var failures: Int = 0, var openedAt: Long = NOT_OPEN, var openMs: Long = 0, var trialInFlight: Boolean = false)

    private val entries = HashMap<String, Entry>()

    fun state(routeId: String, now: Long): State {
        val e = entries[routeId] ?: return State.CLOSED
        if (e.openedAt == NOT_OPEN) return State.CLOSED
        return if (now - e.openedAt < e.openMs) State.OPEN else State.HALF_OPEN
    }

    /** Non-mutating check, safe to use for filtering candidates. */
    fun canAttempt(routeId: String, now: Long): Boolean = when (state(routeId, now)) {
        State.CLOSED -> true
        State.OPEN -> false
        State.HALF_OPEN -> entries.getValue(routeId).trialInFlight.not()
    }

    /** Call for the route that is actually about to be tried. */
    fun beginAttempt(routeId: String, now: Long) {
        if (state(routeId, now) == State.HALF_OPEN) entries.getValue(routeId).trialInFlight = true
    }

    fun recordSuccess(routeId: String) {
        entries.remove(routeId)
    }

    /** [forceOpenMs] opens the breaker immediately (used for confirmed, non-retryable failures). */
    fun recordFailure(routeId: String, now: Long, forceOpenMs: Long? = null) {
        val e = entries.getOrPut(routeId) { Entry() }
        val wasHalfOpen = state(routeId, now) == State.HALF_OPEN
        e.trialInFlight = false
        e.failures++
        when {
            forceOpenMs != null -> open(e, now, forceOpenMs)
            wasHalfOpen || e.failures >= failureThreshold -> open(e, now, defaultOpenMs)
        }
    }

    private fun open(e: Entry, now: Long, ms: Long) {
        require(ms > 0)
        e.openedAt = now
        e.openMs = ms
        e.failures = 0
    }

    private companion object { const val NOT_OPEN = Long.MIN_VALUE }
}

data class RecoveryConfig(
    val maxAttemptsPerEpisode: Int = 6,
    val maxEpisodeDurationMs: Long = 10 * 60_000,
    val attemptTimeoutMs: Long = 20_000,
    val backoff: BackoffPolicy = BackoffPolicy(),
    val breakerFailureThreshold: Int = 3,
    val breakerOpenMs: Long = 120_000,
    /** How long a confirmed non-retryable failure keeps that route blocked. */
    val permanentFailureOpenMs: Long = 30 * 60_000,
    val minDwellMs: Long = 60_000,
    val minScoreImprovement: Double = 10.0,
    val maxSwitchesPerWindow: Int = 3,
    val switchWindowMs: Long = 10 * 60_000
) {
    init {
        require(maxAttemptsPerEpisode in 1..20) { "attempts must be bounded (1..20)" }
        require(maxEpisodeDurationMs > 0 && attemptTimeoutMs > 0)
        require(breakerFailureThreshold >= 1 && breakerOpenMs > 0 && permanentFailureOpenMs > 0)
        require(minDwellMs >= 0 && minScoreImprovement >= 0.0)
        require(maxSwitchesPerWindow >= 1 && switchWindowMs > 0)
    }
}

sealed interface RecoveryDecision {
    /** Try [route] after waiting [delayBeforeMs], giving the attempt at most [timeoutMs]. */
    data class TryRoute(val route: RouteCandidate, val delayBeforeMs: Long, val timeoutMs: Long) : RecoveryDecision
    /** Do nothing until the OS reports a network change. No polling, no battery cost. */
    data object WaitForNetworkChange : RecoveryDecision
    /** Re-evaluate after [delayMs]. */
    data class WaitAndRetry(val delayMs: Long) : RecoveryDecision
    /** Stop autonomous recovery. A new episode requires an external trigger (network change, user action). */
    data class GiveUp(val reason: String) : RecoveryDecision
}

data class SwitchVerdict(val allowed: Boolean, val reason: String)

/**
 * Decides what to do after a failure, within hard bounds. Pure logic: the caller performs the actual
 * connection attempts, verifies real traffic, and reports results. Not thread-safe.
 */
class RecoveryCoordinator(
    private val manager: AdaptiveConnectionManager = AdaptiveConnectionManager(),
    private val config: RecoveryConfig = RecoveryConfig(),
    private val clock: () -> Long,
    private val random: () -> Double = { Math.random() }
) {
    private class Episode(
        val startedAt: Long,
        val originRouteId: String?,
        var lastFailure: FailureKind,
        var attempts: Int = 0,
        val tried: MutableSet<String> = linkedSetOf()
    )

    private val breaker = CircuitBreaker(config.breakerFailureThreshold, config.breakerOpenMs)
    private var episode: Episode? = null
    private val switchTimes = ArrayDeque<Long>()
    private var lastSwitchAt: Long? = null

    val recovering: Boolean get() = episode != null

    /** Starts (or continues) a recovery episode. A continuing episode keeps its attempt counter. */
    fun beginRecovery(failure: ConnectionFailure, activeRouteId: String?) {
        val now = clock()
        val ep = episode ?: Episode(now, activeRouteId, failure.kind).also { episode = it }
        ep.lastFailure = failure.kind
        if (activeRouteId != null) {
            // The route that just failed counts as already tried, so alternatives are preferred and
            // a retry of the same route is delayed by backoff.
            ep.tried += activeRouteId
            penalize(activeRouteId, failure, now)
        }
    }

    fun nextStep(routes: List<RouteCandidate>): RecoveryDecision {
        val ep = episode ?: return RecoveryDecision.GiveUp("No recovery episode is active.")
        val now = clock()
        if (ep.attempts >= config.maxAttemptsPerEpisode) return finish("Attempt limit reached (${config.maxAttemptsPerEpisode}).")
        if (now - ep.startedAt >= config.maxEpisodeDurationMs) return finish("Recovery time limit reached.")

        when (ep.lastFailure.retryability()) {
            Retryability.WAIT_FOR_NETWORK_CHANGE -> return RecoveryDecision.WaitForNetworkChange
            Retryability.WAIT_WITH_BACKOFF -> {
                ep.attempts++
                return RecoveryDecision.WaitAndRetry(config.backoff.delayMs(ep.attempts, random()))
            }
            Retryability.TRANSIENT, Retryability.ROUTE_PERMANENT -> Unit
        }

        val usable = manager.selectCandidates(routes, limit = 5).filter { breaker.canAttempt(it.routeId, now) }
        if (usable.isEmpty()) return finish("No approved route is currently available.")

        // Untried alternatives first (keeping score order), then routes already tried in this episode.
        val chosen = usable.firstOrNull { it.routeId !in ep.tried } ?: usable.first()
        ep.attempts++
        val retryOfTried = chosen.routeId in ep.tried
        ep.tried += chosen.routeId
        breaker.beginAttempt(chosen.routeId, now)
        val delay = if (retryOfTried) config.backoff.delayMs(ep.attempts, random()) else 0L
        return RecoveryDecision.TryRoute(chosen, delay, config.attemptTimeoutMs)
    }

    /** Report the outcome of an attempt returned by [nextStep]. Success must mean verified traffic, not just "started". */
    fun reportAttempt(routeId: String, success: Boolean, failure: ConnectionFailure? = null) {
        val now = clock()
        val ep = episode
        if (success) {
            breaker.recordSuccess(routeId)
            if (ep != null && routeId != ep.originRouteId) recordSwitch(now)
            episode = null
            return
        }
        val f = failure ?: ConnectionFailure(FailureKind.UNKNOWN_FAILURE, false, "Attempt failed without a classified cause.")
        ep?.lastFailure = f.kind
        penalize(routeId, f, now)
    }

    /** Quality-based (non-failure) switching, guarded against flapping. */
    fun evaluateQualitySwitch(currentScore: Double, candidateRouteId: String, candidateScore: Double): SwitchVerdict {
        val now = clock()
        if (!breaker.canAttempt(candidateRouteId, now)) return SwitchVerdict(false, "Candidate route is blocked by its circuit breaker.")
        lastSwitchAt?.let {
            if (now - it < config.minDwellMs) return SwitchVerdict(false, "Minimum time on the current route has not elapsed.")
        }
        if (candidateScore - currentScore < config.minScoreImprovement) {
            return SwitchVerdict(false, "Candidate is not better by the required margin.")
        }
        pruneSwitches(now)
        if (switchTimes.size >= config.maxSwitchesPerWindow) return SwitchVerdict(false, "Too many recent route switches.")
        return SwitchVerdict(true, "Candidate is meaningfully better and switching limits allow it.")
    }

    /** Call after a quality-based switch has been completed and verified. */
    fun recordQualitySwitchCompleted() = recordSwitch(clock())

    fun circuitState(routeId: String): CircuitBreaker.State = breaker.state(routeId, clock())

    private fun penalize(routeId: String, failure: ConnectionFailure, now: Long) {
        when (failure.kind.retryability()) {
            // Not the route's fault; do not count against it.
            Retryability.WAIT_FOR_NETWORK_CHANGE, Retryability.WAIT_WITH_BACKOFF -> Unit
            Retryability.ROUTE_PERMANENT ->
                breaker.recordFailure(routeId, now, if (failure.confirmed) config.permanentFailureOpenMs else null)
            Retryability.TRANSIENT -> breaker.recordFailure(routeId, now)
        }
    }

    private fun finish(reason: String): RecoveryDecision {
        episode = null
        return RecoveryDecision.GiveUp(reason)
    }

    private fun recordSwitch(now: Long) {
        lastSwitchAt = now
        switchTimes.addLast(now)
        pruneSwitches(now)
    }

    private fun pruneSwitches(now: Long) {
        while (switchTimes.isNotEmpty() && now - switchTimes.first() >= config.switchWindowMs) switchTimes.removeFirst()
    }
}
