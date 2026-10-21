package com.lerchenflo.schneaggchatv3server.announcement

import com.lerchenflo.schneaggchatv3server.announcement.model.Announcement
import com.lerchenflo.schneaggchatv3server.repository.AnnouncementRepository
import com.lerchenflo.schneaggchatv3server.util.VersionCounter
import org.springframework.data.mongodb.core.FindAndModifyOptions
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.data.mongodb.core.query.Update
import org.springframework.stereotype.Service
import kotlin.jvm.optionals.getOrNull
import kotlin.time.Instant

@Service
class AnnouncementLookupService(
    private val announcementRepository: AnnouncementRepository,
    private val mongoTemplate: MongoTemplate,
) {

    companion object {
        // `_id` of this counter's document in the `counters` collection. Deliberately not a
        // SyncCollection key: announcements are never version-synced, and a shared key would mix
        // two counters.
        private const val COUNTER_ID = "announcementids"
    }

    fun findById(id: Long): Announcement? = announcementRepository.findById(id).getOrNull()

    /** The newest announcement above [aboveId] that has not expired at [now], or null. */
    fun findNewestActive(aboveId: Long, now: Instant): Announcement? =
        announcementRepository.findFirstByIdGreaterThanAndExpiresAtAfterOrderByIdDesc(aboveId, now)

    /** All announcements including expired ones, newest first. */
    fun findAllNewestFirst(): List<Announcement> = announcementRepository.findAllByOrderByIdDesc()

    fun save(announcement: Announcement): Announcement = announcementRepository.save(announcement)

    fun deleteById(id: Long) = announcementRepository.deleteById(id)

    /**
     * Hands out the next announcement number with an atomic `$inc` + upsert on one counter
     * document, the same pattern as [com.lerchenflo.schneaggchatv3server.util.VersionCounterService].
     * The counter is never decremented, so the number of a deleted announcement is never reused -
     * reusing it would hide the new announcement from everyone who dismissed the old one.
     */
    fun nextId(): Long {
        val result = mongoTemplate.findAndModify(
            Query(Criteria.where("_id").`is`(COUNTER_ID)),
            Update().inc("seq", 1L),
            FindAndModifyOptions.options().returnNew(true).upsert(true),
            VersionCounter::class.java,
        ) ?: throw IllegalStateException("Failed to increment the announcement counter")

        return result.seq
    }
}
