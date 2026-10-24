package com.adaptivevpn.core

/** Adapter boundary. Implementations must map real engine behavior; no fake success values. */
interface ProtocolEngine {
    val engineId: String
    val version: String
    val capabilities: Set<EngineCapability>

    suspend fun validateConfiguration(configuration: EngineConfiguration): ValidationResult
    suspend fun checkPrerequisites(): PrerequisiteResult
    suspend fun connect(configuration: EngineConfiguration): EngineResult
    suspend fun healthCheck(): HealthReport
    fun getConnectionState(): ConnectionState
    suspend fun getDiagnostics(): EngineDiagnostics
    suspend fun disconnect(): EngineResult
}
