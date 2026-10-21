package cc.noharry.bothy.sample

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DebugSettingsNavigationTest {
    @Test
    fun basicToolsHasIndependentPageAndBackReturnsToOverview() {
        val navigation = DebugSettingsNavigationState(DebugSettingsPage.BASIC_TOOLS)
        assertEquals(DebugSettingsNavigationState(), navigation.back())
        assertFalse(navigation.showsProviderDetail(twoPane = true))
        assertFalse(navigation.showsProviderDetail(twoPane = false))
    }

    @Test
    fun runtimeFeaturesReturnToSettingsWithoutOpeningProviderEditor() {
        for (page in listOf(DebugSettingsPage.RUNTIME_SKILLS, DebugSettingsPage.JEV)) {
            val navigation = DebugSettingsNavigationState(page)
            assertEquals(DebugSettingsNavigationState(), navigation.back())
            assertFalse(navigation.showsProviderDetail(twoPane = true))
        }
    }

    @Test
    fun settingsStartsAtOverview() {
        val navigation = DebugSettingsNavigationState()

        assertEquals(DebugSettingsPage.OVERVIEW, navigation.page)
        assertNull(navigation.requestedProfileId)
        assertNull(navigation.back())
    }

    @Test
    fun providerSectionOpensProviderList() {
        val navigation = DebugSettingsNavigationState().openProviders()

        assertEquals(DebugSettingsPage.PROVIDERS, navigation.page)
        assertNull(navigation.requestedProfileId)
    }

    @Test
    fun selectingProviderOpensRequestedDetail() {
        val navigation = DebugSettingsNavigationState().openProviders().openProvider("profile-2")

        assertEquals(DebugSettingsPage.PROVIDER_DETAIL, navigation.page)
        assertEquals("profile-2", navigation.requestedProfileId)
    }

    @Test
    fun backFromDetailReturnsToProviderList() {
        val navigation = DebugSettingsNavigationState().openProvider("profile-2").back()

        assertEquals(DebugSettingsNavigationState(DebugSettingsPage.PROVIDERS), navigation)
    }

    @Test
    fun addingProviderAndCancelReturnsToProviderList() {
        val navigation = DebugSettingsNavigationState().openProviders().openNewProvider()

        assertEquals(DebugSettingsPage.NEW_PROVIDER, navigation.page)
        assertEquals(DebugSettingsNavigationState(DebugSettingsPage.PROVIDERS), navigation.back())
    }

    @Test
    fun compactProviderListDoesNotInlineLongForm() {
        val navigation = DebugSettingsNavigationState().openProviders()

        assertFalse(navigation.showsProviderDetail(twoPane = false))
        assertTrue(navigation.showsProviderDetail(twoPane = true))
    }

    @Test
    fun a2aProfilesStartAtListAndBackExitsToSettings() {
        val navigation = DebugA2aProfilesNavigationState()

        assertEquals(DebugA2aProfilesPage.LIST, navigation.page)
        assertNull(navigation.back())
    }

    @Test
    fun a2aAddServiceAndBackReturnsToProfileList() {
        val navigation = DebugA2aProfilesNavigationState().openAdd()

        assertEquals(DebugA2aProfilesPage.ADD, navigation.page)
        assertEquals(DebugA2aProfilesNavigationState(), navigation.back())
    }

    @Test
    fun a2aBreadcrumbTracksListAndAddSteps() {
        val list = DebugA2aProfilesNavigationState()

        assertEquals("A2A clients", list.title())
        assertEquals("Settings › A2A", list.breadcrumb())
        assertEquals("Add A2A service", list.openAdd().title())
        assertEquals("Settings › A2A › Add", list.openAdd().breadcrumb())
    }
}
