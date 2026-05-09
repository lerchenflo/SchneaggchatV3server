package com.lerchenflo.schneaggchatv3server.notifications

import com.lerchenflo.schneaggchatv3server.repository.UserRepository
import com.lerchenflo.schneaggchatv3server.user.friends.FriendsLookupService
import com.lerchenflo.schneaggchatv3server.util.AppLogger
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.time.LocalDate
import java.time.ZoneId

@Service
class BirthdayNotificationScheduler(
    private val userRepository: UserRepository,
    private val friendsLookupService: FriendsLookupService,
    private val notificationService: NotificationService,
) {

    //@Scheduled(cron = "10 0 0 * * *", zone = "Europe/Vienna") //10 sek nach mitternacht
    @Scheduled(cron = "0 0 * * * *", zone = "Europe/Vienna") //Jede stunde (Debug)
    fun sendBirthdayNotifications() {
        val today = LocalDate.now(ZoneId.of("Europe/Vienna"))
        val mmdd = String.format("%02d-%02d", today.monthValue, today.dayOfMonth)
        val regex = ".*-$mmdd$"
        val birthdayUsers = userRepository.findByBirthDateRegex(regex)

        AppLogger.info("[Birthday] Checking birthdays for $mmdd — found ${birthdayUsers.size} user(s)")

        birthdayUsers.forEach { user ->

            runCatching {
                notificationService.notifyBirthday(user.id, user.id, ownBirthday = true)
            }.onFailure {
                AppLogger.error("[Birthday] self notify failed for $user.id: ${it.message}")
            }

            friendsLookupService.getFriends(user.id).forEach { friendId ->
                runCatching {
                    notificationService.notifyBirthday(user.id, friendId, ownBirthday = false)
                }.onFailure {
                    AppLogger.error("[Birthday] friend notify failed for $user.id -> $friendId: ${it.message}")
                }
            }
        }
    }
}
