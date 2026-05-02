package com.lerchenflo.schneaggchatv3server.notifications.apns.model

import org.bson.types.ObjectId
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.index.CompoundIndex
import org.springframework.data.mongodb.core.index.Indexed
import org.springframework.data.mongodb.core.mapping.Document

@Document("apnstokens")
@CompoundIndex(
    name = "apns_user_token_unique_idx",
    def = "{'userId': 1, 'token': 1}",
    unique = true
)
data class ApnsToken(
    @Id val id: ObjectId = ObjectId.get(),

    @Indexed
    val userId: ObjectId,
    val token: String,
)
