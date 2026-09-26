package com.lerchenflo.schneaggchatv3server.games.model

import com.lerchenflo.schneaggchatv3server.games.GamesService
import org.springframework.data.domain.Sort

enum class Game(
    val higherScoreWins: Boolean,
    val lowerTimeWins: Boolean,
    // Offline party games keep their own boards but never feed the cross-game ranking
    val countsForGlobalRanking: Boolean = true,
    // Win-counting games: every submission is one win (score = 1) and the board ranks the sum
    // per user instead of the best single submission
    val sumsWins: Boolean = false,
    // Highest score this game can possibly produce; anything above it is a forged submission.
    // null = no known upper bound (endless games).
    val maxScore: Long? = null,
) {
    TETRIS(higherScoreWins = true, lowerTimeWins = true),
    TOWERSTACK(higherScoreWins = true, lowerTimeWins = true),
    SCHNEAGGAHUS(higherScoreWins = true, lowerTimeWins = false),
    MORSE(higherScoreWins = true, lowerTimeWins = true),
    GRIDRUSH(higherScoreWins = true, lowerTimeWins = true),
    ODDONEOUT(higherScoreWins = true, lowerTimeWins = true),
    GAME_2048(higherScoreWins = true, lowerTimeWins = true),

    // Pure race: clients always submit score = 0, so the time tiebreaker ranks the board.
    // Difficulty encodes the puzzle language (LOW = German, HIGH = English), not hardness.
    CROSSWORD(higherScoreWins = true, lowerTimeWins = true),

    // Offline games played on one phone; the host submits every player's result via /games/upsertbatch.
    // Final score of a finished game; timeMillis is always 0.
    // Theoretical maximum of this variant is 424 (105 upper + 35 bonus + 284 lower); the cap leaves headroom.
    YATZI(higherScoreWins = true, lowerTimeWins = true, countsForGlobalRanking = false, maxScore = 1000),

    // Three-dart average x100 of a finished game; timeMillis is always 0.
    // Difficulty encodes the countdown (LOW = 301, HIGH = 501), not hardness.
    // A three-dart average can never exceed 180.00, so 18000 is the hard ceiling.
    DART_COUNTER(higherScoreWins = true, lowerTimeWins = true, countsForGlobalRanking = false, maxScore = 18000),

    // One submission per winning player (score = 1); the board shows the number of wins. timeMillis is always 0.
    UNDERCOVER(higherScoreWins = true, lowerTimeWins = true, countsForGlobalRanking = false, sumsWins = true, maxScore = 1);

    /** Best result first: score, then time as tiebreaker, earliest submission wins full ties. */
    fun leaderboardSort(): Sort = Sort.by(
        Sort.Order(if (higherScoreWins) Sort.Direction.DESC else Sort.Direction.ASC, "score"),
        Sort.Order(if (lowerTimeWins) Sort.Direction.ASC else Sort.Direction.DESC, "timeMillis"),
        Sort.Order(Sort.Direction.ASC, "createdAt"),
    )

    /** Same ordering as [leaderboardSort], for ranking per-user bests in memory. */
    fun leaderboardComparator(): Comparator<GamesService.UserBestScore> {
        val byScore =
            if (higherScoreWins) compareByDescending<GamesService.UserBestScore> { it.score }
            else compareBy<GamesService.UserBestScore> { it.score }
        val byTime =
            if (lowerTimeWins) compareBy<GamesService.UserBestScore> { it.timeMillis }
            else compareByDescending<GamesService.UserBestScore> { it.timeMillis }
        return byScore.then(byTime).thenBy { it.achievedAt }
    }

    companion object {
        fun fromId(id: String): Game? = entries.find { it.name.equals(id, ignoreCase = true) }
    }
}
