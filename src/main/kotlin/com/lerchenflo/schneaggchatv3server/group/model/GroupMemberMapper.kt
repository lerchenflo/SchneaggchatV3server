package com.lerchenflo.schneaggchatv3server.group.model

fun GroupMember.toGroupMemberResponse(memberName: String): GroupMemberResponse {
    return GroupMemberResponse(
        userid = userid.toHexString(),
        joinedAt = joinedAt.toEpochMilliseconds().toString(),
        admin = admin,
        color = color,
        memberName = memberName
    )
}
