package com.adaptivevpn.core

/** Operational states. UI must never infer CONNECTED from a successful start request alone. */
enum class ConnectionState {
    DISCONNECTED, CHECKING_NETWORK, VALIDATING_CONFIGURATION, SELECTING_ROUTE,
    CONNECTING, VERIFYING_TRAFFIC, CONNECTED, DEGRADED, RECOVERING, DISCONNECTING, FAILED
}

enum class FailureKind {
    NETWORK_UNAVAILABLE, DNS_RESOLUTION_FAILURE, SERVER_UNREACHABLE, HANDSHAKE_FAILURE,
    AUTHENTICATION_FAILURE, TUNNEL_ESTABLISHED_BUT_NO_TRAFFIC, HIGH_PACKET_LOSS,
    HIGH_LATENCY, CONFIGURATION_INVALID, ENGINE_FAILURE, UNSUPPORTED_TRANSPORT,
    INTERNET_ACCESS_RESTRICTED, UNKNOWN_FAILURE
}

enum class RegistryStatus {
    DISCOVERED, UNDER_REVIEW, VERIFIED, TESTING, APPROVED, DISABLED, DEPRECATED, REJECTED
}

enum class EngineCapability { IPV4, IPV6, CUSTOM_DNS, TRAFFIC_STATS, HEALTH_CHECK, CONFIG_VALIDATION }

data class EngineConfiguration(val profileId: String, val opaqueConfig: String) {
    init { require(profileId.isNotBlank()) { "profileId must not be blank" } }
}

data class ValidationResult(val valid: Boolean, val safeMessage: String? = null)
data class PrerequisiteResult(val ready: Boolean, val safeMessage: String? = null)
data class EngineResult(val success: Boolean, val failure: FailureKind? = null, val safeMessage: String? = null)
data class HealthReport(val healthy: Boolean, val trafficVerified: Boolean, val latencyMs: Long? = null, val failure: FailureKind? = null)
data class EngineDiagnostics(val engineId: String, val version: String, val state: ConnectionState, val details: Map<String, String> = emptyMap())
data class RouteCandidate(
    val routeId: String,
    val engineId: String,
    val status: RegistryStatus,
    val securityApproved: Boolean,
    val compatible: Boolean,
    val successRate: Double,
    val latencyMs: Long,
    val connectTimeMs: Long,
    val recentFailures: Int = 0
)
data class ConnectionFailure(val kind: FailureKind, val confirmed: Boolean, val message: String? = null)
