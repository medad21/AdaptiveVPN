package com.adaptivevpn.core

enum class SecurityPosture { UNREVIEWED, APPROVED, FAILED }

data class TestSummary(val testId: String, val engineVersion: String, val passed: Boolean, val atMs: Long)

data class RegistryEntry(
    val id: String,
    val name: String,
    val version: String,
    val officialSource: String,
    val license: String,
    val capabilities: Set<EngineCapability> = emptySet(),
    val knownLimitations: List<String> = emptyList(),
    val supportedPlatforms: Set<String> = emptySet(),
    val status: RegistryStatus = RegistryStatus.DISCOVERED,
    val statusReason: String? = null,
    val lastReviewedAtMs: Long? = null,
    val testResults: List<TestSummary> = emptyList(),
    val security: SecurityPosture = SecurityPosture.UNREVIEWED
) {
    init {
        require(id.isNotBlank()) { "id must not be blank" }
        require(name.isNotBlank()) { "name must not be blank" }
        require(version.isNotBlank()) { "version must not be blank" }
    }
}

data class RegistryAuditEvent(val atMs: Long, val entryId: String, val action: String, val detail: String)

data class RegistryResult(val ok: Boolean, val message: String) {
    companion object {
        fun ok(message: String = "ok") = RegistryResult(true, message)
        fun rejected(message: String) = RegistryResult(false, message)
    }
}

/**
 * In-memory registry. Only APPROVED entries are operational. Moving to APPROVED requires a reviewed license,
 * an approved security posture and a passing test for the entry's current version. Persistence is a separate adapter.
 */
class ProtocolRegistry(private val clock: () -> Long) {
    private val entries = LinkedHashMap<String, RegistryEntry>()
    private val audit = ArrayList<RegistryAuditEvent>()

    fun get(id: String): RegistryEntry? = entries[id]
    fun all(): List<RegistryEntry> = entries.values.toList()
    fun auditLog(): List<RegistryAuditEvent> = audit.toList()

    /** Entries the connection manager may use. */
    fun operational(): List<RegistryEntry> = entries.values.filter { it.status == RegistryStatus.APPROVED }
    fun isOperational(id: String): Boolean = entries[id]?.status == RegistryStatus.APPROVED
    fun statusOf(id: String): RegistryStatus? = entries[id]?.status

    fun register(entry: RegistryEntry): RegistryResult {
        if (entries.containsKey(entry.id)) return RegistryResult.rejected("An entry with this id already exists.")
        if (entry.status != RegistryStatus.DISCOVERED) return RegistryResult.rejected("New entries must start as DISCOVERED.")
        if (!entry.officialSource.startsWith("https://")) return RegistryResult.rejected("The official source must be an https URL.")
        entries[entry.id] = entry
        log(entry.id, "register", "version=${entry.version}")
        return RegistryResult.ok()
    }

    fun transition(id: String, to: RegistryStatus, reason: String? = null): RegistryResult {
        val e = entries[id] ?: return RegistryResult.rejected("Unknown entry.")
        if (to !in allowedFrom(e.status)) return RegistryResult.rejected("Transition ${e.status} -> $to is not allowed.")
        if (to in REASON_REQUIRED && reason.isNullOrBlank()) return RegistryResult.rejected("A reason is required for $to.")
        if (to == RegistryStatus.APPROVED) {
            gateFailure(e)?.let { return RegistryResult.rejected(it) }
        }
        entries[id] = e.copy(status = to, statusReason = reason, lastReviewedAtMs = clock())
        log(id, "transition", "${e.status} -> $to${reason?.let { ": $it" } ?: ""}")
        return RegistryResult.ok()
    }

    fun recordSecurity(id: String, posture: SecurityPosture, reason: String): RegistryResult {
        val e = entries[id] ?: return RegistryResult.rejected("Unknown entry.")
        var updated = e.copy(security = posture, lastReviewedAtMs = clock())
        // A security failure immediately removes the entry from operational use.
        if (posture == SecurityPosture.FAILED && e.status == RegistryStatus.APPROVED) {
            updated = updated.copy(status = RegistryStatus.DISABLED, statusReason = "Security review failed: $reason")
        }
        entries[id] = updated
        log(id, "security", "$posture: $reason")
        return RegistryResult.ok()
    }

    fun recordTest(id: String, result: TestSummary): RegistryResult {
        val e = entries[id] ?: return RegistryResult.rejected("Unknown entry.")
        entries[id] = e.copy(testResults = e.testResults + result)
        log(id, "test", "${result.testId} v${result.engineVersion} passed=${result.passed}")
        return RegistryResult.ok()
    }

    /**
     * A new engine version must pass review again. Security approval is reset and the entry leaves
     * operational use until it is re-approved; results from older versions do not count.
     */
    fun updateVersion(id: String, newVersion: String): RegistryResult {
        val e = entries[id] ?: return RegistryResult.rejected("Unknown entry.")
        if (newVersion.isBlank()) return RegistryResult.rejected("Version must not be blank.")
        if (newVersion == e.version) return RegistryResult.ok("Version unchanged.")
        val leavesOperation = e.status in setOf(RegistryStatus.APPROVED, RegistryStatus.TESTING, RegistryStatus.VERIFIED)
        entries[id] = e.copy(
            version = newVersion,
            security = SecurityPosture.UNREVIEWED,
            status = if (leavesOperation) RegistryStatus.UNDER_REVIEW else e.status,
            statusReason = if (leavesOperation) "New version $newVersion requires re-review." else e.statusReason,
            lastReviewedAtMs = clock()
        )
        log(id, "version", "${e.version} -> $newVersion")
        return RegistryResult.ok()
    }

    private fun gateFailure(e: RegistryEntry): String? {
        if (e.license.isBlank() || e.license.equals("UNKNOWN", ignoreCase = true)) return "License has not been reviewed."
        if (e.security != SecurityPosture.APPROVED) return "Security posture is not approved."
        val current = e.testResults.filter { it.engineVersion == e.version }
        if (current.isEmpty()) return "No test result exists for version ${e.version}."
        if (current.last().passed.not()) return "The latest test for version ${e.version} failed."
        return null
    }

    private fun log(id: String, action: String, detail: String) {
        audit += RegistryAuditEvent(clock(), id, action, detail)
    }

    private fun allowedFrom(s: RegistryStatus): Set<RegistryStatus> = when (s) {
        RegistryStatus.DISCOVERED -> setOf(RegistryStatus.UNDER_REVIEW, RegistryStatus.REJECTED)
        RegistryStatus.UNDER_REVIEW -> setOf(RegistryStatus.VERIFIED, RegistryStatus.REJECTED, RegistryStatus.DISABLED)
        RegistryStatus.VERIFIED -> setOf(RegistryStatus.TESTING, RegistryStatus.UNDER_REVIEW, RegistryStatus.REJECTED, RegistryStatus.DISABLED)
        RegistryStatus.TESTING -> setOf(RegistryStatus.APPROVED, RegistryStatus.VERIFIED, RegistryStatus.REJECTED, RegistryStatus.DISABLED)
        RegistryStatus.APPROVED -> setOf(RegistryStatus.DISABLED, RegistryStatus.DEPRECATED, RegistryStatus.UNDER_REVIEW)
        RegistryStatus.DISABLED -> setOf(RegistryStatus.UNDER_REVIEW, RegistryStatus.DEPRECATED)
        RegistryStatus.DEPRECATED, RegistryStatus.REJECTED -> emptySet()
    }

    private companion object {
        val REASON_REQUIRED = setOf(RegistryStatus.DISABLED, RegistryStatus.DEPRECATED, RegistryStatus.REJECTED)
    }
}
