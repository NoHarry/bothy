package cc.noharry.bothy.server.evidence

import android.content.Context
import cc.noharry.bothy.protocol.CheckFixKind
import cc.noharry.bothy.protocol.CheckId
import cc.noharry.bothy.protocol.CheckStatus
import cc.noharry.bothy.protocol.CheckView
import cc.noharry.bothy.protocol.ChecksView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Settings → 检查 as the server holds it: the latest result of each check, how many rounds are going on, and when the
 * last one ended. Rounds may overlap (a permission granted runs its checks again while others still run).
 */
internal object HealthCheckRuns {
    data class Runs(val results: Map<CheckId, CheckResult> = emptyMap(), val active: Int = 0, val checkedAt: Long? = null)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val runs = MutableStateFlow(Runs())
    val state: StateFlow<Runs> = runs.asStateFlow()

    /** Runs the checks named in [ids] (every check when empty); each shows as running until its result comes. */
    fun run(context: Context, ids: List<String>) {
        val checks = DEBUG_CHECKS.filter { ids.isEmpty() || it.id.name in ids }
        if (checks.isEmpty()) return
        val checking = CheckResult(CheckState.RUNNING, null)
        runs.update { it.copy(results = it.results + checks.associate { check -> check.id to checking }, active = it.active + 1) }
        scope.launch {
            try {
                runHealthChecks(context, checks) { id, result -> runs.update { it.copy(results = it.results + (id to result)) } }
            } finally {
                runs.update { it.copy(active = it.active - 1, checkedAt = System.currentTimeMillis()) }
            }
        }
    }
}

/** Every check in order with its latest result. */
internal fun HealthCheckRuns.Runs.view(): ChecksView = ChecksView(
    checks = DEBUG_CHECKS.map { check ->
        val result = results[check.id]
        CheckView(
            id = check.id,
            group = check.group,
            status = result?.state?.let { CheckStatus.valueOf(it.name) },
            finding = result?.finding,
            fix = result?.fix?.let { CheckFixKind.valueOf(it.name) },
        )
    },
    running = active > 0,
    checkedAtEpochMillis = checkedAt,
)
