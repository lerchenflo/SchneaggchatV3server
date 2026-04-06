package com.lerchenflo.schneaggchatv3server.user.friends

import com.lerchenflo.schneaggchatv3server.repository.FriendshipSettingsRepository
import com.lerchenflo.schneaggchatv3server.user.friends.friendshipmodel.FriendshipSetting
import org.bson.types.ObjectId
import org.springframework.stereotype.Component

@Component
class FriendsSettingsService(
    private val friendsLookupService: FriendsLookupService,

    private val friendshipSettingsRepository: FriendshipSettingsRepository

) {

    fun getFriendshipSettingById(id: ObjectId): FriendshipSetting? {
        return friendshipSettingsRepository.findById(id).orElse(null)
    }

    fun saveFriendshipSetting(friendshipSetting: FriendshipSetting) : FriendshipSetting {
        return friendshipSettingsRepository.save(friendshipSetting)
    }

    fun deleteFriendshipSettingById(id: ObjectId) {
        friendshipSettingsRepository.deleteById(id)
    }

}