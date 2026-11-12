package cc.noharry.agent.appserver.plans

import android.content.Context
import cc.noharry.agent.appserver.protocol.Empty
import cc.noharry.agent.appserver.protocol.FeatureRequests
import cc.noharry.agent.appserver.protocol.Finding
import cc.noharry.agent.appserver.protocol.FindingCode
import cc.noharry.agent.appserver.protocol.SaveResult
import cc.noharry.agent.appserver.server.RouteModule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The plans page's requests ([PlansPlugin]): a plan's switch and deleting one answer at once and change the book off the
 * main thread (it waits on the disk); a plan made on the page answers once kept, or with why not.
 */
fun planRoutes(context: Context): RouteModule {
    val app = context.applicationContext
    return RouteModule {
        on(FeatureRequests.PLAN_SET_ENABLED) { params -> Empty.also { background.launch { PlanRunner.setEnabled(app, params.id, params.enabled) } } }
        on(FeatureRequests.PLAN_DELETE) { params -> Empty.also { background.launch { PlanRunner.delete(app, params.id) } } }
        on(FeatureRequests.PLAN_CREATE) { draft ->
            val refused = withContext(Dispatchers.IO) { PlanRunner.create(app, draft).exceptionOrNull() }
            SaveResult(refused?.let { Finding(FindingCode.PLANS_PROBLEM_NOT_SAVED.name) })
        }
    }
}

private val background = CoroutineScope(SupervisorJob() + Dispatchers.IO)
