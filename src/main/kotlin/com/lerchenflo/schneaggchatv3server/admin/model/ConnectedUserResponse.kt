package com.lerchenflo.schneaggchatv3server.admin.model

data class ConnectedUserResponse(
    val userId: String,
    val username: String,
    val sessionCount: Int,
    val onlineSince: Long,
)

data class ConnectedUsersSnapshot(
    val users: List<ConnectedUserResponse>,
)
