package cc.noharry.bothy.app.a2a
import cc.noharry.agent.a2a.A2aClient
import cc.noharry.agent.a2a.ExperimentalA2aClientApi

/** Owns independently configured Sample A2A clients keyed by packaged profile id. */
@OptIn(ExperimentalA2aClientApi::class)
internal class A2aClientPool {
    private data class Entry(
        val profile: A2aProfile,
        val client: A2aClient,
    )

    private val entries = linkedMapOf<String, Entry>()
    private val mutableErrors = linkedMapOf<String, String>()

    val clients: Map<String, A2aClient>
        get() = entries.mapValues { it.value.client }

    val errorsByProfileId: Map<String, String>
        get() = mutableErrors.toMap()

    fun client(profileId: String): A2aClient? = entries[profileId]?.client

    fun reconcile(config: A2aModuleConfig): Map<String, A2aClient> {
        val desired = config.state.loadedProfiles.associateBy { it.id }
        val staleIds = entries.filter { (id, entry) -> desired[id] != entry.profile }.keys.toList()
        staleIds.forEach(::closeEntry)
        mutableErrors.keys.retainAll(desired.keys)
        desired.forEach { (profileId, profile) ->
            if (profileId !in entries) createEntry(config, profileId, profile)
        }
        return clients
    }

    fun closeAll() {
        entries.keys.toList().forEach(::closeEntry)
        mutableErrors.clear()
    }

    private fun createEntry(config: A2aModuleConfig, profileId: String, profile: A2aProfile) {
        runCatching { requireNotNull(config.createClient(profileId)) { "A2A client is not loadable" } }
            .onSuccess { client ->
                entries[profileId] = Entry(profile, client)
                mutableErrors.remove(profileId)
            }
            .onFailure { failure ->
                mutableErrors[profileId] = failure.message ?: "Unable to create A2A client"
            }
    }

    private fun closeEntry(profileId: String) {
        entries.remove(profileId)?.client?.let { client -> runCatching { client.close() } }
        mutableErrors.remove(profileId)
    }
}
