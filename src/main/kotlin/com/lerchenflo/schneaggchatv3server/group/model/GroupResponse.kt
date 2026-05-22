package com.lerchenflo.schneaggchatv3server.group.model

data class GroupResponse(
    val id: String,
    val name: String,
    val description: String,

    val updatedAt: Long,
    val profilePicUpdatedAt: Long,

    val createdAt: Long,
    val creatorId: String,
    val members: List<GroupMemberResponse>
)

data class GroupMemberResponse(
    val userid: String,
    val memberName: String,
    val joinedAt: String,
    val admin: Boolean,
    val color: Int
)
