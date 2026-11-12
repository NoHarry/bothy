@file:OptIn(cc.noharry.agent.a2a.ExperimentalA2aClientApi::class)

package cc.noharry.agent.appserver.a2a

import android.content.Context
import cc.noharry.agent.appserver.protocol.A2aModuleState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The person's A2A profiles while the plugin runs ([A2aPlugin]): their settings, a client for each enabled one (made
 * again when a profile changes, closed when it goes), and what the settings show ([state], the `a2a` section). The
 * settings are kept whether or not it runs (连接应用 saves its agents either way); clients are made only while it runs.
 * Any thread.
 */
object A2aProfiles {
    private var config: A2aModuleConfig? = null
    private val pool = A2aClientPool()
    private val mutable = MutableStateFlow(A2aModuleState())

    /** The profiles and their clients as the settings show them; empty while the plugin is off. */
    val state: StateFlow<A2aModuleState> = mutable

    /** The plugin started: the profiles are read and their clients made. */
    internal fun start(context: Context) = synchronized(this) { apply(A2aModuleConfig.load(context.applicationContext)) }

    /** The plugin stopped: every client is closed and nothing is shown. */
    internal fun close() = synchronized(this) {
        pool.closeAll()
        config = null
        mutable.value = A2aModuleState()
    }

    fun setEnabled(context: Context, profileId: String, enabled: Boolean) {
        loaded(A2aModuleConfig.setProfileEnabled(context.applicationContext, profileId, enabled))
    }

    /**
     * Keeps a user-defined profile (saved disabled; enabling stays explicit) and makes its client. Returns why it was
     * refused, or null once saved. With [replaceSameLabel] a profile of the same label is updated in place.
     */
    fun save(
        context: Context,
        label: String,
        moduleId: String,
        endpoint: String,
        allowLoopbackHttpForDebug: Boolean,
        description: String = "",
        token: String = "",
        replaceSameLabel: Boolean = false,
    ): String? {
        val result = A2aModuleConfig.saveUserProfile(
            context.applicationContext, label, moduleId, endpoint, allowLoopbackHttpForDebug,
            description = description, token = token, replaceSameLabel = replaceSameLabel,
        )
        return when (result) {
            is A2aSaveResult.Rejected -> result.reason
            is A2aSaveResult.Saved -> null.also { loaded(result.config) }
        }
    }

    /** Deletes a user-defined profile and closes its client; a packaged profile is refused. */
    fun delete(context: Context, profileId: String) {
        A2aModuleConfig.deleteUserProfile(context.applicationContext, profileId)?.let(::loaded)
    }

    /** One bridge provider per enabled profile, each reaching its client from the pool when it is called; none while off. */
    internal fun bridgeProviders(context: Context): List<A2aBridgeProvider> = synchronized(this) {
        if (!A2aRuntime.running) return emptyList()
        val current = config ?: A2aModuleConfig.load(context.applicationContext).also(::apply)
        appA2aBridgeProviders(current) { profileId -> synchronized(this) { pool.client(profileId) } }
    }

    /** Settings just saved: taken up while the plugin runs, else only kept. */
    private fun loaded(next: A2aModuleConfig) = synchronized(this) { if (A2aRuntime.running) apply(next) }

    private fun apply(loaded: A2aModuleConfig) {
        config = loaded
        val clients = pool.reconcile(loaded)
        val profilesById = loaded.state.profiles.associateBy { it.id }
        val loadedModuleIds = clients.keys.associateWith { profileId -> requireNotNull(profilesById[profileId]).moduleId }
        val errors = loaded.state.errorsByProfileId + pool.errorsByProfileId
        val activeId = loadedModuleIds.keys.firstOrNull() ?: loaded.state.enabledProfiles.firstOrNull()?.id
        mutable.value = loaded.state.copy(
            activeProfileId = activeId,
            loadedModuleId = activeId?.let(loadedModuleIds::get),
            error = loaded.state.error ?: errors.values.firstOrNull(),
            loadedModuleIds = loadedModuleIds,
            errorsByProfileId = errors,
        )
    }
}
