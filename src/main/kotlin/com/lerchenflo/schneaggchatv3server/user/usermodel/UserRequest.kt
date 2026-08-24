package com.lerchenflo.schneaggchatv3server.user.usermodel

import jakarta.validation.constraints.Size

data class UserRequest(
    @field:Size(max = 24, message = "User ID too long")
    val userId: String,
    @field:Size(max = 200, message = "Description too long")
    val newDescription: String?,
    @field:Size(max = 200, message = "Status too long")
    val newStatus: String?,
    @field:Size(max = 254, message = "Email too long")
    val newEmail: String?,
    @field:Size(max = 10, message = "Birth date too long")
    val newBirthDate: String?,
    @field:Size(max = 25, message = "Nickname too long")
    val newNickName: String?,
    @field:Size(max = 25, message = "Phone number too long")
    val newPhoneNumber: String?
)
