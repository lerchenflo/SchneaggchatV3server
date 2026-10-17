package com.lerchenflo.schneaggchatv3server.feedback.model

enum class FeedbackType { FEATURE, BUG }

enum class FeedbackStatus(val type: FeedbackType) {
    // Feature requests
    REQUESTED(FeedbackType.FEATURE),
    PLANNED(FeedbackType.FEATURE),
    IMPLEMENTED(FeedbackType.FEATURE),

    // Bug reports
    OPEN(FeedbackType.BUG),
    CONFIRMED(FeedbackType.BUG),
    FIXED(FeedbackType.BUG);

    companion object {
        /** The status a freshly created entry starts in - clients cannot choose it. */
        fun initialFor(type: FeedbackType): FeedbackStatus = when (type) {
            FeedbackType.FEATURE -> REQUESTED
            FeedbackType.BUG -> OPEN
        }
    }
}

enum class FeedbackTag { CHAT, GROUPS, EVENTS, MAP, GAMES, SETTINGS, UI, NOTIFICATIONS, PERFORMANCE, OTHER }

/**
 * A user's vote on an entry. [NONE] is only ever sent by clients to remove their vote and is never
 * stored. [DIDNT_KNOW] ("I didn't know this existed") is only valid on implemented features.
 */
enum class FeedbackVote { UP, DOWN, DIDNT_KNOW, NONE }
