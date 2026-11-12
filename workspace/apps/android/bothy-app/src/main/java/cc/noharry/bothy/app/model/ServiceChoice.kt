package cc.noharry.bothy.app.model

import cc.noharry.agent.appserver.protocol.ProviderEndpoint
import cc.noharry.agent.appserver.protocol.ServiceEndpointView
import cc.noharry.agent.appserver.protocol.ServiceView

/*
 * Adding a model service starts with choosing who provides it (owner 2026-11-11: the form only let you pick a wire
 * protocol and type everything): one of the services the server knows (`services/list`), which fills the form in, or
 * Custom, the form as typed. A service with hosts in more than one region (China, international) asks which. Without
 * any service (the models plugin switched off) Custom is all there is. The settings (tablet and phone) and the first
 * launch's setup offer the same choice.
 */

/** The regions [this] service has hosts in, in its endpoints' order; one blank region for a service with one host. */
internal fun ServiceView.regions(): List<String> = endpoints.map { it.region }.distinct()

/** Its endpoint in [region] of [kind], else its first in [region]; null when it has none there. */
internal fun ServiceView.endpointIn(region: String, kind: ProviderEndpoint? = null): ServiceEndpointView? {
    val there = endpoints.filter { it.region == region }
    return there.firstOrNull { it.endpoint == kind } ?: there.firstOrNull()
}

/** The region of its endpoint at [baseUrl], as the server lists it; null when none of them is at that address. */
internal fun ServiceView.regionAt(baseUrl: String): String? = endpoints.firstOrNull { it.baseUrl.sameAddress(baseUrl) }?.region

/** Whether [baseUrl] is one of its endpoints' addresses as listed, not typed over. */
internal fun ServiceView.lists(baseUrl: String): Boolean = endpoints.any { it.baseUrl.sameAddress(baseUrl) }

/** The service [id] names among these; null for Custom (blank), for none chosen yet, and for one the list lacks. */
internal fun List<ServiceView>.named(id: String?): ServiceView? = id?.takeIf { it.isNotBlank() }?.let { wanted -> firstOrNull { it.id == wanted } }

private fun String.sameAddress(other: String): Boolean = trim().trimEnd('/') == other.trim().trimEnd('/')
