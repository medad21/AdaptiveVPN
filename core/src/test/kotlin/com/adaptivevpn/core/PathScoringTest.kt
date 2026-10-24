package com.adaptivevpn.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PathScoringTest {
    private val now = 10_000_000_000L
    private fun metrics(
        success: Double = .95, connect: Long = 800, latency: Long = 80, drops: Double = 0.2,
        recovery: Long? = 3000, transfer: Double = .9, battery: Double = .2, extra: Double = .05,
        samples: Int = 100, measuredAt: Long = now
    ) = PathMetrics(success, connect, latency, drops, recovery, transfer, battery, extra, samples, measuredAt)

    private val scorer = PathScorer()

    @Test fun betterMetricsScoreHigher() {
        val good = scorer.quality(metrics(), now).score
        val bad = scorer.quality(metrics(success = .4, latency = 900, drops = 6.0, transfer = .3), now).score
        assertTrue(good > bad + 20, "good=$good bad=$bad")
    }

    @Test fun scoresStayWithinZeroToHundred() {
        val best = scorer.quality(metrics(success = 1.0, connect = 0, latency = 0, drops = 0.0, recovery = 0, transfer = 1.0, battery = 0.0, extra = 0.0), now)
        val worst = scorer.quality(metrics(success = 0.0, connect = 600_000, latency = 600_000, drops = 1e6, recovery = 600_000, transfer = 0.0, battery = 1.0, extra = 100.0), now)
        assertTrue(best.score in 0.0..100.0 && worst.score in 0.0..100.0)
        assertTrue(best.raw > 95.0 && worst.raw < 5.0)
    }

    @Test fun oneLuckySampleCannotBeatAWellTestedRoute() {
        val lucky = scorer.quality(metrics(success = 1.0, latency = 5, samples = 1), now).score
        val proven = scorer.quality(metrics(success = .93, latency = 90, samples = 200), now).score
        assertTrue(proven > lucky, "proven=$proven lucky=$lucky")
    }

    @Test fun staleResultsDecayTowardThePrior() {
        val fresh = scorer.quality(metrics(), now)
        val old = scorer.quality(metrics(measuredAt = now - 60L * 24 * 60 * 60 * 1000), now)
        assertTrue(old.trust < fresh.trust)
        assertTrue(kotlin.math.abs(old.score - 50.0) < kotlin.math.abs(fresh.score - 50.0))
    }

    @Test fun unmeasuredRecoveryIsNeutralNotPerfect() {
        val measuredFast = scorer.quality(metrics(recovery = 100), now).raw
        val unknown = scorer.quality(metrics(recovery = null), now).raw
        val measuredSlow = scorer.quality(metrics(recovery = 120_000), now).raw
        assertTrue(measuredFast > unknown && unknown > measuredSlow)
    }

    @Test fun insecureRouteGetsNoOperationalScoreRegardlessOfSpeed() {
        val q = scorer.quality(metrics(latency = 1, connect = 1), now)
        assertEquals(0.0, scorer.operationalScore(q, SecurityPosture.UNREVIEWED))
        assertEquals(0.0, scorer.operationalScore(q, SecurityPosture.FAILED))
        assertEquals(q.score, scorer.operationalScore(q, SecurityPosture.APPROVED))
    }

    @Test fun rankingPlacesApprovedSlowRouteAboveInsecureFastRoute() {
        val board = PathScoreBoard(scorer)
        board.record(PathKey("fast-unsafe", NetworkClass.WIFI), metrics(latency = 5, connect = 50))
        board.record(PathKey("slow-safe", NetworkClass.WIFI), metrics(latency = 400, connect = 3000, success = .8))
        val ranking = board.ranking(NetworkClass.WIFI, { if (it == "slow-safe") SecurityPosture.APPROVED else SecurityPosture.UNREVIEWED }, now)
        assertEquals(listOf("slow-safe", "fast-unsafe"), ranking.map { it.routeId })
        assertEquals(0.0, ranking.last().operationalScore)
    }

    @Test fun resultsAreSeparatedPerNetworkClass() {
        val board = PathScoreBoard(scorer)
        board.record(PathKey("r", NetworkClass.WIFI), metrics())
        assertTrue(board.ranking(NetworkClass.CELLULAR, { SecurityPosture.APPROVED }, now).isEmpty())
        assertEquals(1, board.ranking(NetworkClass.WIFI, { SecurityPosture.APPROVED }, now).size)
    }

    @Test fun customWeightsChangeTheOutcome() {
        val lowLatencyFlakyRoute = metrics(latency = 10, success = .5)
        val slowReliableRoute = metrics(latency = 600, success = .99)
        val reliabilityFirst = PathScorer(ScoringConfig(weights = ScoreWeights(successRate = 90.0, latency = 1.0)))
        val latencyFirst = PathScorer(ScoringConfig(weights = ScoreWeights(successRate = 1.0, latency = 90.0)))
        assertTrue(reliabilityFirst.quality(slowReliableRoute, now).score > reliabilityFirst.quality(lowLatencyFlakyRoute, now).score)
        assertTrue(latencyFirst.quality(lowLatencyFlakyRoute, now).score > latencyFirst.quality(slowReliableRoute, now).score)
    }

    @Test fun invalidInputsAreRejected() {
        assertTrue(runCatching { metrics(success = 1.5) }.isFailure)
        assertTrue(runCatching { metrics(drops = Double.NaN) }.isFailure)
        assertTrue(runCatching { metrics(latency = -1) }.isFailure)
        assertTrue(runCatching { ScoreWeights(successRate = -1.0) }.isFailure)
        assertTrue(runCatching { ScoreWeights(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0) }.isFailure)
    }
}
