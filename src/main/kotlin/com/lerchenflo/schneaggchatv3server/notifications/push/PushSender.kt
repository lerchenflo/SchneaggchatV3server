package com.lerchenflo.schneaggchatv3server.notifications.push

import com.lerchenflo.schneaggchatv3server.notifications.firebase.model.NotificationResponse

/** One push service. Called from [PushDispatcher]'s threads, so a send may block. */
interface PushSender {
    val provider: PushProvider

    fun send(target: PushTarget, notification: NotificationResponse, options: PushOptions)
}
