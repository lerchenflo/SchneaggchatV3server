@file:OptIn(ExperimentalTime::class)

package com.lerchenflo.schneaggchatv3server.schneaggmap

import com.lerchenflo.schneaggchatv3server.repository.MapEntryRepository
import com.lerchenflo.schneaggchatv3server.schneaggmap.model.LatLong
import com.lerchenflo.schneaggchatv3server.schneaggmap.model.MapEntry
import com.lerchenflo.schneaggchatv3server.user.UserLookupService
import com.lerchenflo.schneaggchatv3server.util.AppLogger
import com.lerchenflo.schneaggchatv3server.util.Json
import org.springframework.core.io.ClassPathResource
import org.springframework.stereotype.Service
import tools.jackson.core.type.TypeReference
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Inserts the bike service stations from `seed/map_bike_service_stations.json` (public repair
 * stands in Vorarlberg, taken from OpenStreetMap `amenity=bicycle_repair_station`) as map entries.
 *
 * Runs on every startup and inserts only the seeds whose [MapEntry.seedKey] is missing from the
 * collection, soft-deleted entries included - an entry users edited or an admin deleted stays that
 * way. The entries are attributed to the first admin; without an admin nothing is seeded until a
 * later startup. Each insert is recorded in the change log like a user-created entry, so an admin
 * can revert it there. No push: clients pick the entries up with their next `/map/sync`.
 */
@Service
class MapSeedService(
    private val mapEntryRepository: MapEntryRepository,
    private val mapEntryVersionService: MapEntryVersionService,
    private val userLookupService: UserLookupService,
) {

    internal data class MapSeed(
        val seedKey: String = "",
        val name: String = "",
        /** Source credit, e.g. the ODbL attribution OpenStreetMap data requires. */
        val description: String = "",
        val lat: Double? = null,
        val long: Double? = null,
    )

    fun seedMissingEntries() {
        val resource = ClassPathResource(SEED_FILE)
        if (!resource.exists()) {
            AppLogger.warn("Map seed file not found, skipping map seeding", tag = "seed_map_file_missing")
            return
        }

        val seeds = try {
            Json.mapper.readValue(resource.inputStream, object : TypeReference<List<MapSeed>>() {})
        } catch (e: Exception) {
            AppLogger.error("Could not parse map seed file: ${e.message}", tag = "seed_map_parse_failed")
            return
        }

        val existingKeys = mapEntryRepository.findBySeedKeyNotNull().mapNotNull { it.seedKey }.toSet()
        val missing = seeds
            .filter { it.seedKey.isNotBlank() && it.seedKey !in existingKeys }
            .distinctBy { it.seedKey }
        if (missing.isEmpty()) return

        val admin = userLookupService.findFirstAdmin()
        if (admin == null) {
            AppLogger.warn("No admin user yet, skipping ${missing.size} map seeds", tag = "seed_map_no_admin")
            return
        }

        val now = Clock.System.now()
        val entries = missing.mapNotNull { seed ->
            val name = seed.name.trim()
            val description = seed.description.trim()
            val lat = seed.lat
            val long = seed.long
            if (name.isEmpty() || name.length > MAX_NAME_LENGTH || description.length > MAX_DESCRIPTION_LENGTH ||
                lat == null || lat !in -90.0..90.0 || long == null || long !in -180.0..180.0
            ) {
                AppLogger.warn("Skipping invalid map seed '${seed.seedKey}'", tag = "seed_map_invalid_entry")
                return@mapNotNull null
            }
            MapEntry(
                coordinates = LatLong(lat = lat, long = long),
                name = name,
                description = description,
                locationData = listOf(LocationData.BikeServiceStation(bikeServiceStationMostlyWorking = null)),
                createdBy = admin.id,
                createdAt = now,
                updatedBy = admin.id,
                updatedAt = now,
                seedKey = seed.seedKey,
            )
        }
        if (entries.isEmpty()) return

        mapEntryRepository.saveAll(entries).forEach { mapEntryVersionService.recordCreate(it, admin.id) }
        AppLogger.info("Seeded ${entries.size} map entries", user = admin.id)
    }

    private companion object {
        const val SEED_FILE = "seed/map_bike_service_stations.json"

        /** Same limits as `SchneaggmapController.MapEntryRequest`. */
        const val MAX_NAME_LENGTH = 100
        const val MAX_DESCRIPTION_LENGTH = 500
    }
}
