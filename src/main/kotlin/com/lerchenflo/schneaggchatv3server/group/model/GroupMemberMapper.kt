package com.lerchenflo.schneaggchatv3server.group.model

/** Members carry only their user id; the client resolves the name (its user rows, else `/users/lookup`). */
fun GroupMember.toGroupMemberResponse(): GroupMemberResponse {
    return GroupMemberResponse(
        userid = userid.toHexString(),
        joinedAt = joinedAt.toEpochMilliseconds().toString(),
        admin = admin,
        color = color,
    )
}
