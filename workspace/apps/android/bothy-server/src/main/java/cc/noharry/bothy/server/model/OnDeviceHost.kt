package cc.noharry.bothy.server.model

import android.content.Context
import cc.noharry.agent.appserver.evidence.AppDiagnostics
import cc.noharry.agent.appserver.model.ON_DEVICE_FIRST_EVENT_TIMEOUT_MS
import cc.noharry.agent.appserver.model.ProviderProfile
import cc.noharry.agent.appserver.protocol.Finding
import cc.noharry.agent.appserver.protocol.ProviderEndpoint
import cc.noharry.agent.appserver.protocol.finding
import cc.noharry.agent.appserver.runtime.HostModels
import cc.noharry.agent.kernel.api.model.ModelClient
import cc.noharry.agent.kernel.api.model.ServerSideSearchCapability
import cc.noharry.agent.model.OpenAiModelClient
import cc.noharry.agent.transport.OkHttpHttpTransport
import cc.noharry.bothy.llm.localModel
import cc.noharry.bothy.protocol.BothyFindingCode
import cc.noharry.bothy.protocol.ON_DEVICE_PROFILE_ID
import cc.noharry.bothy.server.R
import cc.noharry.bothy.server.voice.tts.savedTtsValues
import kotlin.math.roundToLong

/**
 * Bothy's own models for the runtime ([HostModels]): the built-in on-device profile, served by [OnDeviceModel]'s
 * llama-server; the demo car's simulated model ([BothyFixtureModel]); and the voice's saved TTS settings, read with the
 * providers'.
 */
internal object BothyModels : HostModels {
    override fun withProfiles(context: Context, profiles: List<ProviderProfile>): List<ProviderProfile> =
        profiles.withOnDeviceProfile(context.getString(R.string.model_on_device))

    override fun owns(profileId: String): Boolean = profileId == ON_DEVICE_PROFILE_ID

    override fun simulated(): ModelClient = BothyFixtureModel()

    override fun isReady(profile: ProviderProfile): Boolean = OnDeviceModel.isDownloaded(OnDeviceModel.spec(profile.model))

    /** OpenAI chat to the on-device server, at the address and key of whichever start of it is running. */
    override fun client(profile: ProviderProfile, stream: Boolean, diagnostics: AppDiagnostics?): ModelClient =
        OnDeviceModelClient(ready = { OnDeviceModel.ensureRunning(OnDeviceModel.spec(profile.model)) }) { server ->
            OpenAiModelClient(
                apiKeyProvider = { server.apiKey },
                model = profile.model,
                baseUrl = server.baseUrl,
                stream = stream,
                endpoint = ProviderEndpoint.CHAT_COMPLETIONS.wireName,
                serverSideSearch = false,
                searchCapability = ServerSideSearchCapability.NONE,
                transport = diagnostics?.transport(profile.provider) ?: OkHttpHttpTransport(),
            )
        }

    override fun missing(profile: ProviderProfile): Finding {
        val spec = OnDeviceModel.spec(profile.model)
        return finding(BothyFindingCode.MODEL_ON_DEVICE_MISSING, spec.label, (spec.sizeBytes / 1e8).roundToLong() / 10.0)
    }

    override fun missingMessage(context: Context, profile: ProviderProfile): String {
        val spec = OnDeviceModel.spec(profile.model)
        return context.getString(R.string.model_on_device_missing, spec.label, "%.1f".format(spec.sizeBytes / 1e9))
    }

    override fun models(profileId: String): List<String> = OnDeviceModel.models.map { it.id }

    // Its server is started with the model's window (llama-server -c).
    override fun contextTokens(model: String): Long? = localModel(model)?.contextTokens?.toLong()

    override fun firstEventTimeoutMs(profile: ProviderProfile): Long = ON_DEVICE_FIRST_EVENT_TIMEOUT_MS

    override fun selected(profile: ProviderProfile) = OnDeviceModel.onProfileSelected(profile)

    override fun savedValues(context: Context): Map<String, String> = savedTtsValues(context)
}
