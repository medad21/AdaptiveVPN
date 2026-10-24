package com.adaptivevpn.core

import kotlin.math.exp
import kotlin.math.ln

/** Results are kept separate per network class; a good Wi-Fi result says little about cellular. */
enum class NetworkClass { WIFI, CELLULAR, ETHERNET, OTHER }

data class PathKey(val routeId: String, val network: NetworkClass)

data class PathMetrics(
    val successRate: Double,
    val connectTimeMs: Long,
    val medianLatencyMs: Long,
    val unexpectedDropsPerHour: Double,
    /** Null means recovery time has never been measured. */
    val recoveryTimeMs: Long?,
    /** 0..1 quality of observed data transfer. */
    val transferQuality: Double,
    /** 0..1, higher means more battery use. */
    val batteryCost: Double,
    /** Extra data overhead as a ratio, 0 = none. */
    val extraDataRatio: Double,
    val sampleCount: Int,
    val measuredAtMs: Long
) {
    init {
        require(successRate in 0.0..1.0) { "successRate out of range" }
        require(connectTimeMs >= 0 && medianLatencyMs >= 0) { "times must be non-negative" }
        require(unexpectedDropsPerHour.isFinite() && unexpectedDropsPerHour >= 0.0) { "drops must be finite and non-negative" }
        require(recoveryTimeMs == null || recoveryTimeMs >= 0) { "recoveryTimeMs must be non-negative" }
        require(transferQuality in 0.0..1.0) { "transferQuality out of range" }
        require(batteryCost in 0.0..1.0) { "batteryCost out of range" }
        require(extraDataRatio.isFinite() && extraDataRatio >= 0.0) { "extraDataRatio must be finite and non-negative" }
        require(sampleCount >= 0) { "sampleCount must be non-negative" }
    }
}

data class ScoreWeights(
    val successRate: Double = 30.0,
    val connectTime: Double = 10.0,
    val latency: Double = 15.0,
    val drops: Double = 15.0,
    val recovery: Double = 10.0,
    val transfer: Double = 10.0,
    val battery: Double = 5.0,
    val extraData: Double = 5.0
) {
    init {
        val all = listOf(successRate, connectTime, latency, drops, recovery, transfer, battery, extraData)
        require(all.all { it.isFinite() && it >= 0.0 }) { "weights must be finite and non-negative" }
        require(all.sum() > 0.0) { "at least one weight must be positive" }
    }

    internal fun total(): Double = successRate + connectTime + latency + drops + recovery + transfer + battery + extraData
}

data class ScoringConfig(
    val weights: ScoreWeights = ScoreWeights(),
    /** Score assumed when there is no trustworthy data. */
    val priorScore: Double = 50.0,
    /** Number of samples at which confidence reaches 50%. */
    val halfConfidenceSamples: Int = 10,
    /** Age at which a result's weight halves. */
    val freshnessHalfLifeMs: Long = 3L * 24 * 60 * 60 * 1000
) {
    init {
        require(priorScore in 0.0..100.0)
        require(halfConfidenceSamples >= 1)
        require(freshnessHalfLifeMs > 0)
    }
}

data class QualityScore(
    /** Score from the metrics alone, 0..100. */
    val raw: Double,
    /** 0..1: confidence (sample count) times freshness. */
    val trust: Double,
    /** Raw score pulled toward the prior when trust is low. 0..100. */
    val score: Double
)

class PathScorer(private val config: ScoringConfig = ScoringConfig()) {

    fun quality(m: PathMetrics, nowMs: Long): QualityScore {
        val w = config.weights
        val parts = listOf(
            w.successRate * m.successRate,
            w.connectTime * (1.0 / (1.0 + m.connectTimeMs / 2000.0)),
            w.latency * (1.0 / (1.0 + m.medianLatencyMs / 200.0)),
            w.drops * (1.0 / (1.0 + m.unexpectedDropsPerHour)),
            w.recovery * (m.recoveryTimeMs?.let { 1.0 / (1.0 + it / 10_000.0) } ?: 0.5),
            w.transfer * m.transferQuality,
            w.battery * (1.0 - m.batteryCost),
            w.extraData * (1.0 / (1.0 + m.extraDataRatio))
        )
        val raw = 100.0 * parts.sum() / w.total()

        val confidence = m.sampleCount.toDouble() / (m.sampleCount + config.halfConfidenceSamples)
        val ageMs = (nowMs - m.measuredAtMs).coerceAtLeast(0L)
        val freshness = exp(-ln(2.0) * ageMs / config.freshnessHalfLifeMs)
        val trust = (confidence * freshness).coerceIn(0.0, 1.0)

        val score = config.priorScore + (raw - config.priorScore) * trust
        return QualityScore(raw, trust, score.coerceIn(0.0, 100.0))
    }

    /** Security is a gate, not a score component: a route that is not approved gets no operational score. */
    fun operationalScore(quality: QualityScore, security: SecurityPosture): Double =
        if (security == SecurityPosture.APPROVED) quality.score else 0.0
}

data class RankedPath(val routeId: String, val quality: QualityScore, val security: SecurityPosture, val operationalScore: Double)

/** In-memory results keyed by route and network class. Persistence is a separate adapter. */
class PathScoreBoard(private val scorer: PathScorer = PathScorer()) {
    private val results = HashMap<PathKey, PathMetrics>()

    fun record(key: PathKey, metrics: PathMetrics) { results[key] = metrics }
    fun forget(key: PathKey) { results.remove(key) }

    /** Ranks only results measured on [network]. Routes without a result are not invented. */
    fun ranking(network: NetworkClass, securityOf: (String) -> SecurityPosture, nowMs: Long): List<RankedPath> =
        results.asSequence()
            .filter { it.key.network == network }
            .map { (key, metrics) ->
                val q = scorer.quality(metrics, nowMs)
                val sec = securityOf(key.routeId)
                RankedPath(key.routeId, q, sec, scorer.operationalScore(q, sec))
            }
            .sortedWith(compareByDescending<RankedPath> { it.operationalScore }.thenBy { it.routeId })
            .toList()
}
