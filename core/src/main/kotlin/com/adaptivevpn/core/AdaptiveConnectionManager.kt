package com.adaptivevpn.core

/** Pure decision logic; Android lifecycle, persistence and actual engine calls live in adapters. */
class AdaptiveConnectionManager(private val maxCandidatesPerRecovery: Int = 3) {
    init { require(maxCandidatesPerRecovery in 1..5) }

    fun selectCandidates(routes: List<RouteCandidate>, limit: Int = maxCandidatesPerRecovery): List<RouteCandidate> {
        require(limit in 1..5)
        return routes.asSequence()
            .filter { it.status == RegistryStatus.APPROVED && it.securityApproved && it.compatible }
            .filter { it.successRate in 0.0..1.0 && it.latencyMs >= 0 && it.connectTimeMs >= 0 && it.recentFailures >= 0 }
            .sortedByDescending(::score)
            .take(limit)
            .toList()
    }

    /** Score is only used after security and compatibility gates. Higher is better. */
    fun score(route: RouteCandidate): Double {
        val success = route.successRate.coerceIn(0.0, 1.0) * 60.0
        val latency = 20.0 / (1.0 + route.latencyMs / 100.0)
        val connect = 10.0 / (1.0 + route.connectTimeMs / 1000.0)
        val penalty = (route.recentFailures.coerceAtMost(10) * 2.0)
        return success + latency + connect - penalty
    }

    fun classifyFailure(kind: FailureKind?, evidenceConfirmed: Boolean): ConnectionFailure =
        if (kind == null) ConnectionFailure(FailureKind.UNKNOWN_FAILURE, false, "Insufficient evidence")
        else ConnectionFailure(kind, evidenceConfirmed, if (evidenceConfirmed) "Confirmed by observed result" else "Possible cause; not confirmed")

    /** Bounded retry plan. Caller must verify real traffic before marking a route connected. */
    fun recoveryPlan(routes: List<RouteCandidate>, activeRouteId: String?): List<RouteCandidate> =
        selectCandidates(routes.filterNot { it.routeId == activeRouteId })
}
