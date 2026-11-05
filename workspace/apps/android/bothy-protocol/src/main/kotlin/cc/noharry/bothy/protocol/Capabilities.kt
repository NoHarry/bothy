package cc.noharry.bothy.protocol

import kotlinx.serialization.Serializable

@Serializable
enum class JevSearchMode { FLAT, TWO_STAGE, TWO_STAGE_SUBDOMAINS }

/** Jev picking the relevant tools before the first model call; the connection itself is set with voice. */
@Serializable
data class JevSettings(
    val enabled: Boolean = false,
    val endpoint: String = "",
    val model: String = "",
    val timeoutMillis: Long = 0,
    val minScore: Double = 0.0,
    val maxTools: Int = 0,
    val hasApiKey: Boolean = false,
    val mode: JevSearchMode = JevSearchMode.FLAT,
    val domainMinScore: Double = 0.0,
)
