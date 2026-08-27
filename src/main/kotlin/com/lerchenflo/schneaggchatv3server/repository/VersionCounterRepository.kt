package com.lerchenflo.schneaggchatv3server.repository

import com.lerchenflo.schneaggchatv3server.util.VersionCounter
import org.springframework.data.mongodb.repository.MongoRepository

/**
 * Registers the `counters` collection with Spring Data so it participates in auto-index-creation
 * and startup wiring. All actual reads/writes go through [com.lerchenflo.schneaggchatv3server.util.VersionCounterService]
 * via `MongoTemplate.findAndModify`, since increments must be atomic - see that class for why.
 */
interface VersionCounterRepository : MongoRepository<VersionCounter, String>
