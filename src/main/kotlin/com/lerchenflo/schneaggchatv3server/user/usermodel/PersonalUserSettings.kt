package com.lerchenflo.schneaggchatv3server.user.usermodel

/**
 * Per-user app settings, synced across every device of an account via [User.settings].
 * The server never interprets [theme] / [language] / [mapStyle] itself - they are opaque
 * strings mirroring the client-side enum names, sent as-is in [com.lerchenflo.schneaggchatv3server.user.UserController.UserSettingsRequest]
 * and returned as-is in [UserResponse.SelfUserResponse].
 */
data class PersonalUserSettings(
    val mdFormat: Boolean = true,
    val highlightTodaysMessageTimestamp: Boolean = false,

    val theme: String = "SYSTEM",
    val language: String = "SYSTEM",

    val mergeMapLocations: Boolean = true,
    val mergeMapUsers: Boolean = true,
    val mapStyle: String = "LIBERTY",

    val pinnedChats: List<PinnedChat> = emptyList(),

    val developerSettings: Boolean = false,

    // Epoch millis of the last time the contribute popup was shown on any device.
    // 0 means never seeded - a fresh account gets this filled in at registration, and
    // MainController.migrateContributePopupShown backfills accounts created before this field existed.
    val lastContributePopupShown: Long = 0L,
)

data class PinnedChat(
    val chatId: String,
    val group: Boolean,
    val pinTimePoint: Long
)
