import com.adaptivevpn.core.*

fun main() {
    val manager = AdaptiveConnectionManager(maxCandidatesPerRecovery = 2)
    val routes = listOf(
        RouteCandidate("approved-fast", "engine", RegistryStatus.APPROVED, true, true, .95, 80, 400),
        RouteCandidate("approved-slow", "engine", RegistryStatus.APPROVED, true, true, .70, 700, 1200),
        RouteCandidate("unsafe", "engine", RegistryStatus.APPROVED, false, true, 1.0, 5, 100),
        RouteCandidate("unreviewed", "engine", RegistryStatus.UNDER_REVIEW, true, true, 1.0, 1, 100),
        RouteCandidate("incompatible", "engine", RegistryStatus.APPROVED, true, false, 1.0, 1, 100),
        RouteCandidate("invalid-metrics", "engine", RegistryStatus.APPROVED, true, true, 2.0, 1, 100)
    )

    val chosen = manager.selectCandidates(routes)
    check(chosen.map { it.routeId } == listOf("approved-fast", "approved-slow"))
    check(manager.selectCandidates(routes, limit = 1).size == 1)
    check(!manager.classifyFailure(null, true).confirmed)
    check(manager.classifyFailure(FailureKind.NETWORK_UNAVAILABLE, false).confirmed.not())
    check(manager.recoveryPlan(routes, "approved-fast").none { it.routeId == "approved-fast" })
    check(manager.recoveryPlan(routes, "approved-fast").size <= 2)

    check(RouteCoveragePolicy.evaluate(listOf(IpRoutePrefix("0.0.0.0", 0)), IpFamily.IPV4).coversEntireFamily)
    check(RouteCoveragePolicy.evaluate(listOf(IpRoutePrefix("::", 0)), IpFamily.IPV6).coversEntireFamily)
    check(!RouteCoveragePolicy.evaluate(listOf(IpRoutePrefix("10.0.0.0", 8)), IpFamily.IPV4).coversEntireFamily)
    check(runCatching { RouteCoveragePolicy.evaluate(listOf(IpRoutePrefix("example.com", 0)), IpFamily.IPV4) }.isFailure)

    println("PASS: approved-only routing, metric validation, bounded selection, unknown-failure handling, recovery exclusion, and fail-closed IP route coverage")
}
