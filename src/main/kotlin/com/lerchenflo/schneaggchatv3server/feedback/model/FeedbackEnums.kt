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
 * [NOT_NEEDED] ("Don't need it, but fine") means seen, no use for me, but not against it: neutral,
 * never a downvote or a rejection. Valid on features in every status, survives status changes. Responses never put it into `myVote`: released clients only know the first four
 * values, so they get [NONE] plus `isNotNeeded`.
 */
enum class FeedbackVote { UP, DOWN, DIDNT_KNOW, NONE, NOT_NEEDED }
