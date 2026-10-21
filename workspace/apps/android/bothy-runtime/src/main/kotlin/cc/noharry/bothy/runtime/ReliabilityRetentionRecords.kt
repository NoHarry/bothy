package cc.noharry.bothy.runtime

import cc.noharry.bothy.kernel.api.reliability.RuntimeRunStatus
import cc.noharry.bothy.kernel.core.isTerminalRunStatus
import java.io.File

internal data class StoredRunRecord(
    val directory: File,
    val runId: String,
    val status: RuntimeRunStatus?,
    val updatedAtEpochMillis: Long,
) {
    val canDelete: Boolean = status?.isTerminalRunStatus() == true
}
