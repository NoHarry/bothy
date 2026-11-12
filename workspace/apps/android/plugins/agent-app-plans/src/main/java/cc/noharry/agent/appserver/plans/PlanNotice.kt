package cc.noharry.agent.appserver.plans

import android.os.Handler
import android.os.Looper
import cc.noharry.agent.appserver.protocol.Finding
import cc.noharry.agent.appserver.protocol.Plan
import cc.noharry.agent.appserver.protocol.PlanKind
import cc.noharry.agent.appserver.protocol.PlanNoticeEntry
import cc.noharry.agent.appserver.protocol.PlanRanParams
import cc.noharry.agent.appserver.protocol.PresentationNotifications
import cc.noharry.agent.appserver.runtime.VoicePort
import cc.noharry.agent.appserver.server.Assistant

/**
 * The popup's entry for [plan] coming due, [reply] being what it says out loud: a task as handed to the assistant, a
 * reminder's words, and its controls by name until [PlanNotice.ran] has their outcome; the app words them.
 */
internal fun planNoticeEntry(plan: Plan, reply: String?): PlanNoticeEntry = PlanNoticeEntry(
    planId = plan.id,
    kind = when {
        plan.actions.isNotEmpty() -> PlanKind.AUTO
        plan.instruction != null -> PlanKind.TASK
        else -> PlanKind.REMINDER
    },
    title = plan.title,
    handed = plan.instruction,
    said = reply?.takeIf { plan.instruction == null && it.isNotBlank() },
    actions = plan.actions.map { it.label },
)

/**
 * A plan coming due (owner 2026-10-15): the host voice's chime ([VoicePort.chime]), and the popup the assistant on
 * screen shows ([Assistant], `presentation/planDue`). Any thread.
 */
internal object PlanNotice {
    private val main = Handler(Looper.getMainLooper())

    fun show(entry: PlanNoticeEntry, voice: VoicePort, assistant: Assistant) {
        main.post {
            voice.chime()
            assistant.tell(PresentationNotifications.PLAN_DUE, entry)
        }
    }

    /** [planId]'s controls are done: their [outcome] replaces 「执行：…」 while its entry is still up. */
    fun ran(planId: String, outcome: Finding, assistant: Assistant) {
        main.post { assistant.tell(PresentationNotifications.PLAN_RAN, PlanRanParams(planId, outcome)) }
    }
}
