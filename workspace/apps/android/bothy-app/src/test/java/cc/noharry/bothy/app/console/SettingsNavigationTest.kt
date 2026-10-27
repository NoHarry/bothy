package cc.noharry.bothy.app.console
import cc.noharry.bothy.app.a2a.A2aProfilesNavigationState
import cc.noharry.bothy.app.a2a.A2aProfilesPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsNavigationTest {
    @Test
    fun basicToolsHasIndependentPageAndBackReturnsToOverview() {
        val navigation = SettingsNavigationState(SettingsPage.BASIC_TOOLS)
        assertEquals(SettingsNavigationState(), navigation.back())
        assertFalse(navigation.showsProviderDetail(twoPane = true))
        assertFalse(navigation.showsProviderDetail(twoPane = false))
    }

    @Test
    fun runtimeFeaturesReturnToSettingsWithoutOpeningProviderEditor() {
        for (page in listOf(SettingsPage.RUNTIME_SKILLS, SettingsPage.JEV)) {
            val navigation = SettingsNavigationState(page)
            assertEquals(SettingsNavigationState(), navigation.back())
            assertFalse(navigation.showsProviderDetail(twoPane = true))
        }
    }

    @Test
    fun settingsStartsAtOverview() {
        val navigation = SettingsNavigationState()

        assertEquals(SettingsPage.OVERVIEW, navigation.page)
        assertNull(navigation.requestedProfileId)
        assertNull(navigation.back())
    }

    @Test
    fun providerSectionOpensProviderList() {
        val navigation = SettingsNavigationState().openProviders()

        assertEquals(SettingsPage.PROVIDERS, navigation.page)
        assertNull(navigation.requestedProfileId)
    }

    @Test
    fun selectingProviderOpensRequestedDetail() {
        val navigation = SettingsNavigationState().openProviders().openProvider("profile-2")

        assertEquals(SettingsPage.PROVIDER_DETAIL, navigation.page)
        assertEquals("profile-2", navigation.requestedProfileId)
    }

    @Test
    fun backFromDetailReturnsToProviderList() {
        val navigation = SettingsNavigationState().openProvider("profile-2").back()

        assertEquals(SettingsNavigationState(SettingsPage.PROVIDERS), navigation)
    }

    @Test
    fun addingProviderAndCancelReturnsToProviderList() {
        val navigation = SettingsNavigationState().openProviders().openNewProvider()

        assertEquals(SettingsPage.NEW_PROVIDER, navigation.page)
        assertEquals(SettingsNavigationState(SettingsPage.PROVIDERS), navigation.back())
    }

    @Test
    fun compactProviderListDoesNotInlineLongForm() {
        val navigation = SettingsNavigationState().openProviders()

        assertFalse(navigation.showsProviderDetail(twoPane = false))
        assertTrue(navigation.showsProviderDetail(twoPane = true))
    }

    @Test
    fun a2aProfilesStartAtListAndBackExitsToSettings() {
        val navigation = A2aProfilesNavigationState()

        assertEquals(A2aProfilesPage.LIST, navigation.page)
        assertNull(navigation.back())
    }

    @Test
    fun a2aAddServiceAndBackReturnsToProfileList() {
        val navigation = A2aProfilesNavigationState().openAdd()

        assertEquals(A2aProfilesPage.ADD, navigation.page)
        assertEquals(A2aProfilesNavigationState(), navigation.back())
    }

    @Test
    fun a2aBreadcrumbTracksListAndAddSteps() {
        val list = A2aProfilesNavigationState()

        assertEquals("A2A clients", list.title())
        assertEquals("Settings › A2A", list.breadcrumb())
        assertEquals("Add A2A service", list.openAdd().title())
        assertEquals("Settings › A2A › Add", list.openAdd().breadcrumb())
    }
}
