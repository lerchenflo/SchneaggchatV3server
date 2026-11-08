package com.lerchenflo.schneaggchatv3server.message.messagemodel

fun SystemEvent.toSystemEventResponse(): SystemEventResponse {
    return SystemEventResponse(
        eventType = this.eventType,
        actorId = this.actorId.toHexString(),
        actorName = this.actorName,
        targets = this.targets.map { it.toSystemEventParticipantResponse() },
        text = this.text,
        previousText = this.previousText,
        timestamp = this.timestamp,
    )
}

fun SystemEventParticipant.toSystemEventParticipantResponse(): SystemEventParticipantResponse {
    return SystemEventParticipantResponse(
        userId = this.userId.toHexString(),
        userName = this.userName,
    )
}
