package com.lerchenflo.schneaggchatv3server.notifications

import com.lerchenflo.schneaggchatv3server.notifications.firebase.model.NotificationResponse
import com.lerchenflo.schneaggchatv3server.notifications.push.PushOptions
import com.lerchenflo.schneaggchatv3server.notifications.websocket.model.SocketConnectionMessage
import org.bson.types.ObjectId

/** One update for one user. [DeliveryService] gets it to every device of that user. */
sealed interface Delivery {

    /** Socket only. Offline devices catch up with their next sync. */
    data class Live(val socket: SocketConnectionMessage) : Delivery

    /** Socket for connected devices, push for the others. */
    data class Alert(
        val socket: SocketConnectionMessage,
        val push: NotificationResponse,
        /** What an ack refers to. New messages use their message id, the client acks that. */
        val id: String = ObjectId.get().toHexString(),
        /**
         * Hold the push until the client confirmed the socket message. False means a socket write
         * counts as delivered: released apps only ack new messages (see the release order in
         * docs/UNIFIED_NOTIFICATIONS_PLAN.md).
         */
        val awaitAck: Boolean = false,
    ) : Delivery

    /** Push to every device, sockets ignored. */
    data class PushOnly(val push: NotificationResponse, val options: PushOptions = PushOptions()) : Delivery
}
