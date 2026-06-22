package com.lerchenflo.schneaggchatv3server.notifications.firebase.model

import org.bson.types.ObjectId
import org.springframework.data.annotation.Id
import org.springframework.data.annotation.TypeAlias
import org.springframework.data.mongodb.core.index.CompoundIndex
import org.springframework.data.mongodb.core.index.Indexed
import org.springframework.data.mongodb.core.mapping.Document

@TypeAlias("firebasetoken")
@Document("firebasetokens")
@CompoundIndex(
    name = "user_token_unique_idx",
    def = "{'userId': 1, 'token': 1}",
    unique = true
)
data class FirebaseToken(
    @Id val id: ObjectId = ObjectId.get(),

    @Indexed
    val userId: ObjectId,
    @Indexed
    val token: String,
)