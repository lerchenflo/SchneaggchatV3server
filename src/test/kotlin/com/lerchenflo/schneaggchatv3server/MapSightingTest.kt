package com.lerchenflo.schneaggchatv3server

import com.lerchenflo.schneaggchatv3server.notifications.NotificationService
import com.lerchenflo.schneaggchatv3server.repository.MapEntryRepository
import com.lerchenflo.schneaggchatv3server.schneaggmap.LocationData
import com.lerchenflo.schneaggchatv3server.schneaggmap.MapEntryVersionService
import com.lerchenflo.schneaggchatv3server.schneaggmap.MapSightingType
import com.lerchenflo.schneaggchatv3server.schneaggmap.SchneaggmapService
import com.lerchenflo.schneaggchatv3server.schneaggmap.model.AttributeValue
import com.lerchenflo.schneaggchatv3server.schneaggmap.model.LatLong
import com.lerchenflo.schneaggchatv3server.schneaggmap.model.MapEntry
import com.lerchenflo.schneaggchatv3server.schneaggmap.newSighting
import com.lerchenflo.schneaggchatv3server.schneaggmap.withoutStaleLastSeen
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.bson.types.ObjectId
import org.junit.jupiter.api.Test
import java.util.Optional
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

class MapSightingTest {

    private val now = Clock.System.now()
    private val user = ObjectId()

    private fun ago(age: Duration) = AttributeValue.LongValue((now - age).toEpochMilliseconds())

    private fun police(age: Duration?) = LocationData.Police(policeLastSeen = age?.let { ago(it) })

    private fun radar(mobile: Boolean?, age: Duration?) = LocationData.Radar(
        radarSpeedLimit = AttributeValue.IntValue(50),
        radarMobile = mobile?.let { AttributeValue.BoolValue(it) },
        radarRedLight = AttributeValue.BoolValue(false),
        radarLastSeen = age?.let { ago(it) },
    )

    private fun entry(vararg data: LocationData) = MapEntry(
        coordinates = LatLong(47.0, 9.0),
        name = "Bödele",
        description = "",
        locationData = data.toList(),
        createdBy = user,
        createdAt = now,
        updatedBy = user,
        updatedAt = now,
    )

    @Test
    fun `newer recent police last seen counts`() {
        assertEquals(MapSightingType.POLICE, newSighting(entry(police(4.hours)), entry(police(1.minutes)), now))
    }

    @Test
    fun `first police last seen on an existing entry counts`() {
        assertEquals(MapSightingType.POLICE, newSighting(entry(police(null)), entry(police(1.minutes)), now))
    }

    @Test
    fun `same or older last seen stays quiet`() {
        assertNull(newSighting(entry(police(5.minutes)), entry(police(5.minutes)), now))
        assertNull(newSighting(entry(police(5.minutes)), entry(police(20.minutes)), now))
    }

    @Test
    fun `report inside the cooldown stays quiet`() {
        assertNull(newSighting(entry(police(179.minutes)), entry(police(1.minutes)), now))
    }

    @Test
    fun `report after the cooldown counts`() {
        assertEquals(MapSightingType.POLICE, newSighting(entry(police(181.minutes)), entry(police(1.minutes)), now))
    }

    @Test
    fun `last seen older than an hour stays quiet`() {
        assertNull(newSighting(entry(police(5.hours)), entry(police(61.minutes)), now))
    }

    @Test
    fun `last seen just under an hour old still counts`() {
        assertEquals(MapSightingType.POLICE, newSighting(null, entry(police(59.minutes)), now))
    }

    @Test
    fun `small clock skew counts, a date far ahead does not`() {
        assertEquals(MapSightingType.POLICE, newSighting(null, entry(police((-3).minutes)), now))
        assertNull(newSighting(null, entry(police((-2).hours)), now))
    }

    @Test
    fun `new entry counts only with last seen set`() {
        assertEquals(MapSightingType.POLICE, newSighting(null, entry(police(1.minutes)), now))
        assertNull(newSighting(null, entry(police(null)), now))
    }

    @Test
    fun `mobile radar counts, a fixed radar never does`() {
        assertEquals(MapSightingType.RADAR, newSighting(null, entry(radar(true, 1.minutes)), now))
        assertNull(newSighting(null, entry(radar(false, 1.minutes)), now))
        assertNull(newSighting(null, entry(radar(null, 1.minutes)), now))
    }

    @Test
    fun `an entry with several kinds reports the one that changed`() {
        val old = entry(police(10.minutes), radar(true, 4.hours))
        val new = entry(police(10.minutes), radar(true, 1.minutes))

        assertEquals(MapSightingType.RADAR, newSighting(old, new, now))
    }

    @Test
    fun `deleted entry stays quiet`() {
        assertNull(newSighting(null, entry(police(1.minutes)).copy(deleted = true), now))
    }

    @Test
    fun `last seen is dropped when the radar is not mobile`() {
        assertNull((radar(false, 1.minutes).withoutStaleLastSeen() as LocationData.Radar).radarLastSeen)
        assertNull((radar(null, 1.minutes).withoutStaleLastSeen() as LocationData.Radar).radarLastSeen)
        assertEquals(ago(1.minutes), (radar(true, 1.minutes).withoutStaleLastSeen() as LocationData.Radar).radarLastSeen)
    }

    private val repository = mockk<MapEntryRepository>()
    private val notifications = mockk<NotificationService>(relaxed = true)
    private val service = SchneaggmapService(
        mapEntryRepository = repository,
        notificationService = notifications,
        userLookupService = mockk(relaxed = true),
        loggingService = mockk(relaxed = true),
        mapEntryVersionService = mockk<MapEntryVersionService>(relaxed = true),
    )

    private fun save(existing: MapEntry?, data: LocationData): MapEntry {
        every { repository.save(any<MapEntry>()) } answers { firstArg() }
        existing?.let { every { repository.findById(it.id) } returns Optional.of(it) }
        return service.upsertMapEntry(existing?.id, "Bödele", "", LatLong(47.0, 9.0), listOf(data), user)
    }

    @Test
    fun `sighting sends one push, without the person who reported it`() {
        val saved = save(entry(police(4.hours)), police(0.minutes))

        verify(exactly = 1) { notifications.notifyMapSighting(saved, MapSightingType.POLICE, user) }
    }

    @Test
    fun `cooldown skips the push, not the save or the map update`() {
        val saved = save(entry(police(30.minutes)), police(0.minutes))

        verify(exactly = 1) { repository.save(any<MapEntry>()) }
        verify(exactly = 1) { notifications.notifyMapUpdate(saved, false, false, user) }
        verify(exactly = 0) { notifications.notifyMapSighting(any(), any(), any()) }
    }

    @Test
    fun `other edit sends no sighting push`() {
        save(entry(police(4.hours)), police(4.hours))

        verify(exactly = 0) { notifications.notifyMapSighting(any(), any(), any()) }
    }

    @Test
    fun `saved radar loses its last seen when mobile goes off`() {
        val saved = save(entry(radar(true, 5.minutes)), radar(false, 5.minutes))

        assertNull((saved.locationData.single() as LocationData.Radar).radarLastSeen)
    }
}
