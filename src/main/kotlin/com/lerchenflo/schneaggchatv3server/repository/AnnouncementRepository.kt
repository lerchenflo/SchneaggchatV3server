package com.lerchenflo.schneaggchatv3server.repository

import com.lerchenflo.schneaggchatv3server.announcement.model.Announcement
import org.springframework.data.mongodb.repository.MongoRepository
import kotlin.time.Instant

interface AnnouncementRepository : MongoRepository<Announcement, Long> {
    fun findFirstByIdGreaterThanAndExpiresAtAfterOrderByIdDesc(id: Long, now: Instant): Announcement?
    fun findAllByOrderByIdDesc(): List<Announcement>
}
