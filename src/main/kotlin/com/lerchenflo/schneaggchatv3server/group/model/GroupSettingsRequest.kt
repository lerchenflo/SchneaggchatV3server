package com.lerchenflo.schneaggchatv3server.group.model

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

/**
 * Body of `/groups/settings`. Every setting is optional: null = leave it unchanged, so a client
 * only sends what it changed and never overwrites a parallel change of another member.
 */
data class GroupSettingsRequest(
    @field:NotBlank(message = "Group ID must not be blank")
    @field:Size(max = 24, message = "Group ID too long")
    val groupId: String,

    /** Every member. Same rules as a group name at creation. */
    val name: String? = null,

    /** Every member. Empty is allowed (no description). */
    val description: String? = null,

    /** Admins only. Epoch millis, must be in the future. To remove the timer use [clearExpiresAt]. */
    val expiresAt: Long? = null,

    /** Admins only. True removes the delete timer (null [expiresAt] already means "unchanged"). Not together with [expiresAt]. */
    val clearExpiresAt: Boolean? = null,

    /** Admins only. See [Group.membersCanAdd]. */
    val membersCanAdd: Boolean? = null,
)
