package cc.noharry.bothy.playground

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The world on its own: fast enough for a plan to fire in a demo, and settling where it should. */
class SimulationTest {
    private fun World.after(seconds: Int, notes: MutableList<String> = mutableListOf()): World {
        val random = Random(1)
        return (1..seconds).fold(this) { world, _ -> world.tick(1.0, random, notes) }
    }

    @Test
    fun theAirConditioningCoolsTheCabinToItsSettingAndOpenWindowsLetTheHeatIn() {
        val cooling = World(cabin = Cabin(climate = CabinClimate(on = true, driverTemp = 22.0, mode = ClimateMode.COOL), insideTempC = 30.0))
        assertTrue(cooling.after(60).cabin.insideTempC < 26.0)
        assertEquals(22.0, cooling.after(200).cabin.insideTempC, 0.001)
        val parked = World(cabin = Cabin(insideTempC = 24.0))
        val open = parked.copy(cabin = parked.cabin.copy(windows = Seat.entries.associateWith { 100 }))
        assertTrue(open.after(60).cabin.insideTempC > parked.after(60).cabin.insideTempC)
    }

    @Test
    fun aDriveDrainsTheBatteryAndANavigationSetsTheSpeed() {
        val start = World(cabin = Cabin(drive = Drive(moving = true, speedKmh = 60.0)))
        assertTrue(start.after(180).cabin.drive.batteryPct < start.cabin.drive.batteryPct - 5)
        assertEquals(0.0, World().after(60).cabin.drive.speedKmh, 0.0)
        val navigating = World(cabin = Cabin(nav = NavState(status = NavStatus.NAVIGATING, speedKmh = 88)))
        assertEquals(88.0, navigating.after(5).cabin.drive.speedKmh, 0.0)
        // An hour navigated at 120 km/h takes what 120 km of range takes, not the whole battery.
        val hour = World(cabin = Cabin(nav = NavState(status = NavStatus.NAVIGATING, speedKmh = 120))).after(3_600)
        assertEquals(navigating.cabin.drive.batteryPct - 120 / KM_PER_PERCENT, hour.cabin.drive.batteryPct, 0.5)
    }

    @Test
    fun roomsWarmTowardTheEveningUnlessTheirAirConditionerRuns() {
        val home = World().home.withRoom(Room.LIVING) { it.copy(climate = RoomClimate(on = true, targetTemp = 25.0), tempC = 28.5) }
        val later = World(home = home).after(120).home
        assertEquals(25.0, later.room(Room.LIVING).tempC, 0.001)
        assertTrue(later.room(Room.BEDROOM).tempC == 29.0)
    }

    @Test
    fun theVacuumSweepsItsRoomInRowsAndDocksOnTheWayBack() {
        val cleaning = Vacuum(status = VacuumStatus.CLEANING, room = Room.KITCHEN)
        val kitchen = HomeSpots.rooms.getValue(Room.KITCHEN)
        val start = vacuumSpot(cleaning, 0f)
        val end = vacuumSpot(cleaning, 1f)
        assertTrue(kitchen.contains(start) && kitchen.contains(end) && end.y > start.y)
        assertEquals(HomeSpots.dock, vacuumSpot(Vacuum(), 0.5f))
        assertEquals(HomeSpots.dock, vacuumSpot(cleaning.copy(status = VacuumStatus.RETURNING), 1f))
    }
}
