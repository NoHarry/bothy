package cc.noharry.bothy.server.evidence

import android.content.Context
import cc.noharry.agent.appserver.model.ProviderConfig
import cc.noharry.agent.appserver.model.ProviderProfile
import cc.noharry.agent.appserver.protocol.Empty
import cc.noharry.agent.appserver.protocol.Finding
import cc.noharry.agent.appserver.protocol.ProviderFetchParams
import cc.noharry.agent.appserver.protocol.ServiceRequests
import cc.noharry.agent.appserver.protocol.ServiceView
import cc.noharry.agent.appserver.protocol.SettingsRequests
import cc.noharry.agent.appserver.protocol.finding
import cc.noharry.agent.appserver.protocol.listing
import cc.noharry.agent.appserver.protocol.parts
import cc.noharry.agent.appserver.protocol.text
import cc.noharry.agent.spi.model.ModelClientException
import cc.noharry.agent.spi.model.ModelMessage
import cc.noharry.agent.spi.model.ModelRequest
import cc.noharry.agent.spi.reliability.RuntimeErrorCode
import cc.noharry.bothy.protocol.BothyFindingCode
import cc.noharry.bothy.server.server.ServerLink
import kotlinx.coroutines.CancellationException

/**
 * What the chat check asks of the server, as a client asks it: the services it knows and a profile's model list as
 * 设置 → 模型 → 获取模型 fetches it (the service's own list by the service's rules, or the service's data where it has
 * no list).
 */
internal interface ModelListServer {
    /** The services the server knows (`services/list`). */
    suspend fun services(): List<ServiceView>

    /** [profile]'s models (`provider/fetchModels`), asked with the key saved for it. */
    suspend fun models(profile: ProviderProfile): List<String>
}

/** The running server, through [ServerLink]. */
private object RunningServer : ModelListServer {
    override suspend fun services(): List<ServiceView> = ServerLink.request(ServiceRequests.LIST, Empty).services

    override suspend fun models(profile: ProviderProfile): List<String> = ServerLink.request(
        SettingsRequests.PROVIDER_FETCH_MODELS,
        ProviderFetchParams(profile.id, profile.provider, profile.endpoint, apiKey = "", profile.model, profile.baseUrl),
    ).models
}

/** The selected model's check (设置 → 检查, and 保存并检查 in the first launch's setup), against the running server. */
internal suspend fun checkChatModel(context: Context): CheckResult = checkSelectedModel(ProviderConfig.load(context), RunningServer)

/**
 * [config]'s selected model, checked as the server lists models (owner 2026-11-11: a good Gemini, Zhipu or Model Studio
 * key was reported as failed when the check asked `<base URL>/models` itself): the model list as [server] fetches it,
 * which reads a service's prefixed ids and its own list address. A service with no model list (Zhipu, Model Studio) gets
 * its data's list without being asked, which any key would pass, so there the model is asked for one token, as a run
 * asks it: a key it refuses is a wrong key, a model it does not take is one the service does not offer.
 */
internal suspend fun checkSelectedModel(config: ProviderConfig, server: ModelListServer): CheckResult {
    val profile = config.activeProfile
    val name = parts(text(profile.displayLabel), text(profile.model))
    if (!config.isReady(profile)) return fail(parts(name, config.missingFinding(profile)), CheckFix.MODELS)
    val serviceId = config.editState(profile).service
    val service = serviceId.takeIf { it.isNotBlank() }?.let { id -> server.services().firstOrNull { it.id == id } }
    if (service != null && !service.listsModels) return askForOneToken(config, name, service.models)
    return try {
        chatModelListed(name, profile.model, server.models(profile))
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failed: Exception) {
        fail(parts(name, requestProblem(failed)), CheckFix.MODELS)
    }
}

/**
 * The selected model on a service with no model list, asked for one token with its key, on its wire, endpoint and
 * streaming as a run asks it. [models] are those the service's data names.
 */
private suspend fun askForOneToken(config: ProviderConfig, name: Finding, models: List<String>): CheckResult {
    val profile = config.activeProfile
    try {
        config.client(profile, stream = config.streamEnabled, outputTokens = 1L).complete(ONE_TOKEN).collect { }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failed: Exception) {
        return when {
            failed.refusedKey() -> fail(parts(name, finding(BothyFindingCode.CHECKS_HTTP_KEY, failed.httpStatus() ?: HTTP_UNAUTHORIZED)), CheckFix.MODELS)
            profile.model !in models -> chatModelListed(name, profile.model, models)
            else -> fail(parts(name, requestProblem(failed)), CheckFix.MODELS)
        }
    }
    return CheckResult(CheckState.OK, Finding(BothyFindingCode.CHECKS_SERVICE_AVAILABLE, more = listOf(name)))
}

/** A key the service refused, as the server read the failure (the service's own rules, else 401 and 403). */
private fun Exception.refusedKey(): Boolean = (this as? ModelClientException)?.code == RuntimeErrorCode.PROVIDER_AUTH_FAILED

/**
 * The selected model against the service's list. Not listed reads as the selection being stale, with what is listed
 * (owner 2026-10-19 took 「列出的模型里没有 deepseek-v4-flash」 for the check having that model built in).
 */
internal fun chatModelListed(name: Finding, model: String, ids: List<String>): CheckResult {
    if (ids.isEmpty() || model in ids) return CheckResult(CheckState.OK, Finding(BothyFindingCode.CHECKS_SERVICE_AVAILABLE, more = listOf(name)))
    val shown = listing(ids.take(LISTED_MODELS).map(::text))
    val listed = if (ids.size > LISTED_MODELS) Finding(BothyFindingCode.CHECKS_LISTED_MORE, more = listOf(shown, text(ids.size)))
    else shown
    return CheckResult(CheckState.WARN, Finding(BothyFindingCode.CHECKS_MODEL_NOT_LISTED, more = listOf(name, text(model), listed)), CheckFix.MODELS)
}

private fun fail(finding: Finding, fix: CheckFix) = CheckResult(CheckState.FAIL, finding, fix)

private val ONE_TOKEN = ModelRequest(messages = listOf(ModelMessage(role = "user", content = "hi")))
private const val LISTED_MODELS = 5
private const val HTTP_UNAUTHORIZED = 401
