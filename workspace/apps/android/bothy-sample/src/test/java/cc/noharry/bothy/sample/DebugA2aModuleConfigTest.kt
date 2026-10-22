package cc.noharry.bothy.sample

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import cc.noharry.agent.a2a.A2aClient
import cc.noharry.agent.a2a.A2aClientConfig
import cc.noharry.agent.a2a.A2aClientFactory
import cc.noharry.agent.a2a.A2aClientModule
import cc.noharry.agent.a2a.ExperimentalA2aClientApi
import java.lang.reflect.Proxy
import java.net.URI
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalA2aClientApi::class)
@RunWith(RobolectricTestRunner::class)
class DebugA2aModuleConfigTest {
    private lateinit var context: Context

    @Before
    fun clearPreferences() {
        context = ApplicationProvider.getApplicationContext()
        preferences().edit().clear().commit()
    }

    @Test
    fun loadsTwoEnabledProfilesFromPackagedRegistry() {
        val loaded = DebugA2aModuleConfig.loadText(PROFILES, setOf("direct", "official"), recordingRegistry())

        assertEquals(setOf("direct", "official"), loaded.state.enabledProfileIds)
        assertEquals(
            mapOf("direct" to DIRECT_MODULE_ID, "official" to OFFICIAL_MODULE_ID),
            loaded.state.loadedModuleIds,
        )
        assertEquals(listOf("direct", "official"), loaded.state.loadedProfiles.map { it.id })
        assertEquals("direct", loaded.state.activeProfileId)
        assertEquals(DIRECT_MODULE_ID, loaded.state.loadedModuleId)
        assertNull(loaded.state.error)
    }

    @Test
    fun persistsEmptyAndMultipleEnabledProfileSets() {
        DebugA2aModuleConfig.setEnabledProfiles(context, setOf("direct-local", "official-local"))
        assertEquals(
            setOf("direct-local", "official-local"),
            DebugA2aModuleConfig.load(context).state.enabledProfileIds,
        )

        DebugA2aModuleConfig.setEnabledProfiles(context, emptySet())
        val reloaded = DebugA2aModuleConfig.load(context)

        assertTrue(reloaded.state.enabledProfileIds.isEmpty())
        assertTrue(reloaded.state.loadedModuleIds.isEmpty())
        assertTrue(preferences().contains(ENABLED_PROFILE_IDS_KEY))
    }

    @Test
    fun migratesLegacySelectedProfileToEnabledSet() {
        preferences().edit().putString(LEGACY_SELECTED_PROFILE_KEY, "official-local").commit()

        val loaded = DebugA2aModuleConfig.load(context)

        assertEquals(setOf("official-local"), loaded.state.enabledProfileIds)
        assertEquals(setOf("official-local"), preferences().getStringSet(ENABLED_PROFILE_IDS_KEY, null))
        assertFalse(preferences().contains(LEGACY_SELECTED_PROFILE_KEY))
    }

    @Test
    fun savedUserProfileLoadsAfterAssetProfilesAndStaysDisabled() {
        val result = DebugA2aModuleConfig.saveUserProfile(
            context, "My Service", DIRECT_MODULE_ID, "https://a2a.example.com", allowLoopbackHttpForDebug = false,
        )

        assertTrue(result is DebugA2aSaveResult.Saved)
        val loaded = DebugA2aModuleConfig.load(context)
        assertEquals(listOf("direct-local", "official-local", "user-my-service"), loaded.state.profiles.map { it.id })
        val userProfile = loaded.state.profiles.last()
        assertTrue(userProfile.userDefined)
        assertFalse(userProfile.enabled)
        assertTrue(loaded.state.enabledProfileIds.isEmpty())
        assertNull(loaded.state.error)
    }

    @Test
    fun savedUserProfileRoundTripsThroughPreferences() {
        DebugA2aModuleConfig.saveUserProfile(
            context, "Local Box", OFFICIAL_MODULE_ID, "http://127.0.0.1:8765", allowLoopbackHttpForDebug = true,
        )

        assertTrue(preferences().contains(USER_PROFILES_KEY))
        val profile = DebugA2aModuleConfig.load(context).state.profiles.single { it.userDefined }

        assertEquals("user-local-box", profile.id)
        assertEquals("Local Box", profile.label)
        assertEquals(OFFICIAL_MODULE_ID, profile.moduleId)
        assertEquals(URI("http://127.0.0.1:8765"), profile.endpoint)
        assertTrue(profile.allowLoopbackHttpForDebug)
    }

    @Test
    fun generatedUserProfileIdsStayUniquePerLabel() {
        DebugA2aModuleConfig.saveUserProfile(context, "My Service", DIRECT_MODULE_ID, "https://a.example.com", false)
        DebugA2aModuleConfig.saveUserProfile(context, "My Service", DIRECT_MODULE_ID, "https://b.example.com", false)

        val ids = DebugA2aModuleConfig.load(context).state.profiles.filter { it.userDefined }.map { it.id }

        assertEquals(listOf("user-my-service", "user-my-service-2"), ids)
    }

    @Test
    fun rejectsInvalidUserProfilesWithoutPersisting() {
        assertRejected(DebugA2aModuleConfig.saveUserProfile(context, "", DIRECT_MODULE_ID, "https://a.example.com", false))
        assertRejected(DebugA2aModuleConfig.saveUserProfile(context, "  ", DIRECT_MODULE_ID, "https://a.example.com", false))
        assertRejected(DebugA2aModuleConfig.saveUserProfile(context, "Label", DIRECT_MODULE_ID, "not a url", false))
        assertRejected(DebugA2aModuleConfig.saveUserProfile(context, "Label", DIRECT_MODULE_ID, "ftp://a.example.com", false))
        assertRejected(DebugA2aModuleConfig.saveUserProfile(context, "Label", "missing-module", "https://a.example.com", false))
        assertRejected(DebugA2aModuleConfig.saveUserProfile(context, "Label", DIRECT_MODULE_ID, "http://a.example.com", false))
        assertRejected(DebugA2aModuleConfig.saveUserProfile(context, "Label", DIRECT_MODULE_ID, "https://127.0.0.1:9999", false))
        assertRejected(DebugA2aModuleConfig.saveUserProfile(context, "Label", DIRECT_MODULE_ID, "https://localhost", false))

        assertTrue(preferences().getString(USER_PROFILES_KEY, null).isNullOrBlank())
    }

    @Test
    fun rejectsEndpointUserInfoWithoutPersistingOrEchoingCredentials() {
        val result = DebugA2aModuleConfig.saveUserProfile(
            context,
            "Credential endpoint",
            DIRECT_MODULE_ID,
            "https://alice:s3cr3t@a.example.com/path",
            allowLoopbackHttpForDebug = false,
        )

        assertTrue(result is DebugA2aSaveResult.Rejected)
        val reason = (result as DebugA2aSaveResult.Rejected).reason
        assertEquals("Endpoint must not include user credentials", reason)
        assertFalse(reason.contains("alice"))
        assertFalse(reason.contains("s3cr3t"))
        assertTrue(preferences().getString(USER_PROFILES_KEY, null).isNullOrBlank())
    }

    @Test
    fun rejectsCredentialsFromLoadedProfilesWithoutEchoingThem() {
        val text = PROFILES.replace(
            "https://direct.example",
            "https://alice:s3cr3t@direct.example",
        )

        val loaded = DebugA2aModuleConfig.loadText(text, setOf("direct"), recordingRegistry())

        assertTrue(loaded.state.profiles.isEmpty())
        assertEquals("A2A profile endpoint must not include user credentials", loaded.state.error)
        assertFalse(loaded.state.error.orEmpty().contains("alice"))
        assertFalse(loaded.state.error.orEmpty().contains("s3cr3t"))
    }

    @Test
    fun deletingUserProfileCleansEnabledSet() {
        val saved = DebugA2aModuleConfig.saveUserProfile(
            context, "Temp", DIRECT_MODULE_ID, "https://t.example.com", allowLoopbackHttpForDebug = false,
        )
        val profileId = (saved as DebugA2aSaveResult.Saved).config.state.profiles.single { it.userDefined }.id
        DebugA2aModuleConfig.setProfileEnabled(context, profileId, true)
        assertTrue(DebugA2aModuleConfig.load(context).state.enabledProfileIds.contains(profileId))

        val deleted = DebugA2aModuleConfig.deleteUserProfile(context, profileId)

        assertNotNull(deleted)
        val reloaded = DebugA2aModuleConfig.load(context)
        assertFalse(reloaded.state.profiles.any { it.id == profileId })
        assertFalse(reloaded.state.enabledProfileIds.contains(profileId))
        assertFalse(preferences().getStringSet(ENABLED_PROFILE_IDS_KEY, emptySet()).orEmpty().contains(profileId))
    }

    @Test
    fun tokenAndDescriptionAreStoredApartAndSentAsBearer() = kotlinx.coroutines.runBlocking {
        val saved = DebugA2aModuleConfig.saveUserProfile(
            context, "Grok Bot", DIRECT_MODULE_ID, "https://relay.example.com/a2a", allowLoopbackHttpForDebug = false,
            description = "发邮件、设提醒", token = "tok-0123456789",
        ) as DebugA2aSaveResult.Saved
        val profile = saved.config.state.profiles.single { it.userDefined }
        DebugA2aModuleConfig.setProfileEnabled(context, profile.id, true)

        val loaded = DebugA2aModuleConfig.load(context)
        assertEquals("发邮件、设提醒", loaded.state.profiles.single { it.id == profile.id }.description)
        assertEquals(setOf(profile.id), loaded.state.tokenProfileIds)
        assertTrue(DebugA2aModuleConfig.hasEnabledProfiles(context))
        val headers = mutableMapOf<String, String>()
        requireNotNull(loaded.clientConfig(profile.id)).authProvider
            .headers(cc.noharry.agent.a2a.A2aAuthContext(profile.id, cc.noharry.agent.a2a.A2aMethod.SEND_MESSAGE, "r1"))
            .forEachValue { name, value -> headers[name] = value }
        assertEquals(mapOf("authorization" to "Bearer tok-0123456789"), headers)
        assertFalse(preferences().all.values.joinToString().contains("tok-0123456789"))
        assertFalse(loaded.toString().contains("tok-0123456789"))
    }

    @Test
    fun sameLabelUpdatesInPlaceKeepingIdEnablementAndToken() {
        val first = DebugA2aModuleConfig.saveUserProfile(
            context, "Grok Bot", DIRECT_MODULE_ID, "https://old.example.com", false, token = "tok-0123456789",
        ) as DebugA2aSaveResult.Saved
        val id = first.config.state.profiles.single { it.userDefined }.id
        DebugA2aModuleConfig.setProfileEnabled(context, id, true)

        DebugA2aModuleConfig.saveUserProfile(
            context, "Grok Bot", DIRECT_MODULE_ID, "https://new.example.com", false, replaceSameLabel = true,
        )

        val loaded = DebugA2aModuleConfig.load(context)
        val profile = loaded.state.profiles.single { it.userDefined }
        assertEquals(id, profile.id)
        assertEquals(URI("https://new.example.com"), profile.endpoint)
        assertTrue(profile.enabled)
        assertEquals(setOf(id), loaded.state.tokenProfileIds)
    }

    @Test
    fun deletingProfileForgetsItsTokenAndSpacedTokensAreRejected() {
        val saved = DebugA2aModuleConfig.saveUserProfile(
            context, "Temp", DIRECT_MODULE_ID, "https://t.example.com", false, token = "tok-0123456789",
        ) as DebugA2aSaveResult.Saved
        val id = saved.config.state.profiles.single { it.userDefined }.id

        DebugA2aModuleConfig.deleteUserProfile(context, id)

        assertFalse(DebugA2aTokens.has(context, id))
        assertRejected(DebugA2aModuleConfig.saveUserProfile(context, "Spaced", DIRECT_MODULE_ID, "https://s.example.com", false, token = "a b"))
    }

    @Test
    fun deletingAssetProfileIsRejected() {
        val result = DebugA2aModuleConfig.deleteUserProfile(context, "direct-local")

        assertNull(result)
        assertTrue(DebugA2aModuleConfig.load(context).state.profiles.any { it.id == "direct-local" })
    }

    @Test
    fun userProfileIdCollidingWithAssetIdIsDroppedOnLoad() {
        preferences().edit().putString(
            USER_PROFILES_KEY,
            "[{\"id\":\"direct-local\",\"label\":\"Fake\",\"moduleId\":\"$DIRECT_MODULE_ID\"," +
                "\"endpoint\":\"https://fake.example\"}]",
        ).commit()

        val loaded = DebugA2aModuleConfig.load(context)

        assertEquals(listOf("direct-local", "official-local"), loaded.state.profiles.map { it.id })
        assertFalse(loaded.state.profiles.first { it.id == "direct-local" }.userDefined)
    }

    @Test
    fun corruptedUserProfileJsonDoesNotBreakAssetProfiles() {
        // lint_quality counts raw brace characters; keep corrupted payloads brace-free.
        preferences().edit().putString(USER_PROFILES_KEY, "not-json").commit()

        val loaded = DebugA2aModuleConfig.load(context)

        assertEquals(listOf("direct-local", "official-local"), loaded.state.profiles.map { it.id })
        assertNull(loaded.state.error)
    }

    private fun assertRejected(result: DebugA2aSaveResult) {
        assertTrue(result is DebugA2aSaveResult.Rejected)
        assertFalse((result as DebugA2aSaveResult.Rejected).reason.isBlank())
    }

    @Test
    fun unknownModuleErrorsOnlyItsProfile() {
        val text = PROFILES.replace(OFFICIAL_MODULE_ID, "missing-module")
        val loaded = DebugA2aModuleConfig.loadText(text, setOf("direct", "official"), recordingRegistry())

        assertEquals(mapOf("direct" to DIRECT_MODULE_ID), loaded.state.loadedModuleIds)
        assertEquals(
            "Unknown packaged A2A module: missing-module",
            loaded.state.errorsByProfileId["official"],
        )
        assertNotNull(loaded.modules["direct"])
        assertNull(loaded.modules["official"])
    }

    @Test
    fun disablingOneProfileClosesOnlyItsClient() {
        val factory = RecordingClientFactory()
        val pool = DebugA2aClientPool()
        pool.reconcile(config(setOf("direct", "official"), factory))
        val officialClient = pool.client("official")

        pool.reconcile(config(setOf("official"), factory))

        assertEquals(listOf("direct"), factory.closedProfileIds)
        assertNull(pool.client("direct"))
        assertSame(officialClient, pool.client("official"))
    }

    @Test
    fun reconcileKeepsUnchangedClients() {
        val factory = RecordingClientFactory()
        val pool = DebugA2aClientPool()
        pool.reconcile(config(setOf("direct", "official"), factory))
        val before = pool.clients

        pool.reconcile(config(setOf("direct", "official"), factory))

        assertSame(before["direct"], pool.client("direct"))
        assertSame(before["official"], pool.client("official"))
        assertEquals(2, factory.createdProfileIds.size)
        assertTrue(factory.closedProfileIds.isEmpty())
    }

    @Test
    fun clientCreationFailureDoesNotBlockOtherEnabledProfile() {
        val factory = RecordingClientFactory(failingProfileId = "official")
        val pool = DebugA2aClientPool()

        pool.reconcile(config(setOf("direct", "official"), factory))

        assertNotNull(pool.client("direct"))
        assertNull(pool.client("official"))
        assertEquals("Unable to create official", pool.errorsByProfileId["official"])
    }

    @Test
    fun changedProfileReplacesAndClosesItsClient() {
        val factory = RecordingClientFactory()
        val pool = DebugA2aClientPool()
        pool.reconcile(config(setOf("direct"), factory))
        val before = pool.client("direct")
        val changed = PROFILES.replace("https://direct.example", "https://changed.example")

        pool.reconcile(DebugA2aModuleConfig.loadText(changed, setOf("direct"), factory.registry()))

        assertTrue(before !== pool.client("direct"))
        assertEquals(listOf("direct"), factory.closedProfileIds)
    }

    @Test
    fun closeAllClosesEveryClientAndClearsPool() {
        val factory = RecordingClientFactory()
        val pool = DebugA2aClientPool()
        pool.reconcile(config(setOf("direct", "official"), factory))

        pool.closeAll()

        assertEquals(setOf("direct", "official"), factory.closedProfileIds.toSet())
        assertTrue(pool.clients.isEmpty())
    }

    @Test
    fun packagedRegistryCreatesBothShippedModulesAndClients() {
        val registry = DebugA2aModuleRegistry.packaged()
        val loaded = DebugA2aModuleConfig.loadText(PROFILES, setOf("direct", "official"), registry)
        val direct = loaded.createClient("direct")
        val official = loaded.createClient("official")

        assertEquals(setOf(DIRECT_MODULE_ID, OFFICIAL_MODULE_ID), registry.moduleIds())
        assertNotNull(direct)
        assertNotNull(official)
        direct?.close()
        official?.close()
    }

    private fun preferences() = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun config(enabledIds: Set<String>, factory: RecordingClientFactory): DebugA2aModuleConfig =
        DebugA2aModuleConfig.loadText(PROFILES, enabledIds, factory.registry())

    private fun recordingRegistry(): DebugA2aModuleRegistry = DebugA2aModuleRegistry(
        mapOf(
            DIRECT_MODULE_ID to DebugA2aModuleFactory { FakeModule() },
            OFFICIAL_MODULE_ID to DebugA2aModuleFactory { FakeModule() },
        ),
    )

    private class FakeModule : A2aClientModule {
        override fun factory(): A2aClientFactory = A2aClientFactory { error("Client creation is not expected") }
    }

    private class RecordingClientFactory(
        private val failingProfileId: String? = null,
    ) {
        val createdProfileIds = mutableListOf<String>()
        val closedProfileIds = mutableListOf<String>()

        fun registry(): DebugA2aModuleRegistry = DebugA2aModuleRegistry(
            mapOf(
                DIRECT_MODULE_ID to moduleFactory("direct"),
                OFFICIAL_MODULE_ID to moduleFactory("official"),
            ),
        )

        private fun moduleFactory(profileId: String) = DebugA2aModuleFactory {
            object : A2aClientModule {
                override fun factory(): A2aClientFactory = A2aClientFactory { _: A2aClientConfig ->
                    if (profileId == failingProfileId) error("Unable to create $profileId")
                    createdProfileIds += profileId
                    proxyClient(profileId)
                }
            }
        }

        private fun proxyClient(profileId: String): A2aClient = Proxy.newProxyInstance(
            A2aClient::class.java.classLoader,
            arrayOf(A2aClient::class.java),
        ) { proxy, method, args ->
            when (method.name) {
                "close" -> closedProfileIds += profileId
                "equals" -> proxy === args?.firstOrNull()
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "RecordingA2aClient($profileId)"
                else -> null
            }
        } as A2aClient
    }

    private companion object {
        const val PREFS_NAME = "debug_a2a_client"
        const val ENABLED_PROFILE_IDS_KEY = "enabled_profile_ids"
        const val LEGACY_SELECTED_PROFILE_KEY = "selected_profile_id"
        const val USER_PROFILES_KEY = "user_profiles_json"
        val PROFILES = """
            {
              "defaultProfileId": "direct",
              "profiles": [
                {"id":"direct","label":"Direct","moduleId":"direct-jsonrpc","endpoint":"https://direct.example","enabled":true},
                {"id":"official","label":"Official","moduleId":"official-java-sdk","endpoint":"https://official.example","enabled":true}
              ]
            }
        """.trimIndent()
    }
}
