package cc.noharry.bothy.server.server

import android.content.Context
import cc.noharry.agent.appserver.protocol.Finding
import cc.noharry.agent.appserver.protocol.finding
import cc.noharry.agent.appserver.server.RouteModule
import cc.noharry.agent.appserver.server.RouteTable
import cc.noharry.bothy.protocol.BothyFindingCode
import cc.noharry.bothy.protocol.CompanionDecisionView
import cc.noharry.bothy.protocol.CompanionDecisionsResult
import cc.noharry.bothy.protocol.CompanionRequests
import cc.noharry.bothy.protocol.CompanionSaveResult
import cc.noharry.bothy.protocol.CompanionService
import cc.noharry.bothy.protocol.CompanionTryResult
import cc.noharry.bothy.protocol.CompanionView
import cc.noharry.bothy.server.companion.CompanionKeys
import cc.noharry.bothy.server.companion.CompanionRouteReason
import cc.noharry.bothy.server.companion.SmallTalkCompanion
import cc.noharry.bothy.server.companion.companionFailure
import cc.noharry.bothy.server.companion.loadCompanionConfig
import cc.noharry.bothy.server.companion.readinessError
import cc.noharry.bothy.server.companion.saveCompanionConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Settings → 陪聊: its settings and keys, 试一试 with the saved service, and the latest routing decisions. */
internal class CompanionRoutes(private val app: Context) : RouteModule {
    override fun RouteTable.install() {
        on(CompanionRequests.READ) { withContext(Dispatchers.IO) { view() } }
        on(CompanionRequests.SAVE) { params ->
            withContext(Dispatchers.IO) {
                val error = saveCompanionConfig(app, params.config, params.key)
                CompanionSaveResult(view(), error)
            }
        }
        on(CompanionRequests.TRY) { params ->
            val saved = withContext(Dispatchers.IO) { loadCompanionConfig(app) }
            val started = System.currentTimeMillis()
            runCatching { SmallTalkCompanion.ask(app, saved, params.text) }.fold(
                onSuccess = { CompanionTryResult(reply = it, elapsedMillis = System.currentTimeMillis() - started) },
                onFailure = { CompanionTryResult(failure = companionFailure(it)) },
            )
        }
        on(CompanionRequests.DECISIONS) {
            CompanionDecisionsResult(
                SmallTalkCompanion.recentDecisions().map {
                    CompanionDecisionView(it.atEpochMillis, it.words, it.decision.toCompanion, reason(it.decision.why))
                },
            )
        }
    }

    private fun view(): CompanionView {
        val config = loadCompanionConfig(app)
        return CompanionView(
            config = config,
            ownKeys = CompanionService.entries.filterTo(LinkedHashSet()) { CompanionKeys.hasOwn(app, it) },
            usableKeys = CompanionService.entries.filterTo(LinkedHashSet()) { CompanionKeys.read(app, it).isNotEmpty() },
            notReady = config.readinessError(app),
        )
    }

    private fun reason(why: CompanionRouteReason): Finding = when (why) {
        CompanionRouteReason.Off -> finding(BothyFindingCode.COMPANION_REASON_OFF)
        CompanionRouteReason.NotScored -> finding(BothyFindingCode.COMPANION_REASON_NOT_SCORED)
        is CompanionRouteReason.BelowThreshold -> finding(BothyFindingCode.COMPANION_REASON_BELOW, why.score, why.threshold)
        is CompanionRouteReason.TaskWord -> finding(BothyFindingCode.COMPANION_REASON_TASK_WORD, why.word)
        CompanionRouteReason.AnswersAgent -> finding(BothyFindingCode.COMPANION_REASON_ANSWERS_AGENT)
        is CompanionRouteReason.Observing -> finding(BothyFindingCode.COMPANION_REASON_OBSERVING, why.score)
        is CompanionRouteReason.SmallTalk -> finding(BothyFindingCode.COMPANION_REASON_SMALL_TALK, why.score)
    }
}
