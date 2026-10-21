package cc.noharry.bothy.runtime

import cc.noharry.bothy.kernel.core.ProtocolRequestTemplate

internal data class ConfirmationTemplate(
    val expiresInMillis: Long,
)

internal const val DEFAULT_CONFIRMATION_TIMEOUT_MILLIS = 60_000L
