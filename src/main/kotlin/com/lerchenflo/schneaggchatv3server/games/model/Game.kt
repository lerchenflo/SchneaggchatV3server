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
    // Lowest score this game can produce. Only matters for boards ranked ascending
    // (higherScoreWins = false), where a forged small score would otherwise top the board.
    val minScore: Long = 0,
    // Most points a run can earn per second of its reported timeMillis (plus one second of grace);
    // for endless games whose ceiling depends on how long the run lasted. null = no rate limit.
    val maxPointsPerSecond: Long? = null,
    // Online games whose client may also submit a score for a friend of the player through
    // /games/upsertbatch (Schneagg Rodeo: a lassoed friend rides along and gets the run's score),
    // even though the game counts for the global ranking.
    val allowsFriendScores: Boolean = false,
) {
    TETRIS(higherScoreWins = true, lowerTimeWins = true),
    TOWERSTACK(higherScoreWins = true, lowerTimeWins = true),
    SCHNEAGGAHUS(higherScoreWins = true, lowerTimeWins = false),
    MORSE(higherScoreWins = true, lowerTimeWins = true),
    GRIDRUSH(higherScoreWins = true, lowerTimeWins = true),
    ODDONEOUT(higherScoreWins = true, lowerTimeWins = true),
    GAME_2048(higherScoreWins = true, lowerTimeWins = true),

    // Puzzles are drawn at random and differ in size, so the score is the number of letters the
    // grid took to fill - a denser grid is worth more, the solve time ranks equal grids.
    // Difficulty encodes the puzzle language (LOW = German, HIGH = English), not hardness.
    CROSSWORD(higherScoreWins = true, lowerTimeWins = true),

    // Score is the number of letters typed to solve it (5 on the first guess, 30 on the sixth),
    // so FEWER wins here; time breaks ties. Difficulty encodes the word language.
    // Ranked ascending, so the floor is what needs guarding, not just the ceiling.
    WORDLE(higherScoreWins = false, lowerTimeWins = true, minScore = 5, maxScore = 30),

    // Endless runner without a difficulty setting (always MEDIUM). More points win; on equal points
    // the shorter run wins. Points are distance / 10 (at most 18 per second at top speed) plus 25 per
    // lassoed snail (one throw per 0.8 s at most, so at most 31.25 per second) - 50 per second of run
    // time is out of reach. The flat cap equals ~2.8 hours at that impossible pace.
    // A friend lassoed during a run rides along; the client submits the run's score for them too
    // via /games/upsertbatch (allowsFriendScores), with the same limits.
    SCHNEAGG_RODEO(higherScoreWins = true, lowerTimeWins = true, maxScore = 500_000, maxPointsPerSecond = 50, allowsFriendScores = true),

    // Offline games played on one phone; the host submits every player's result via /games/upsertbatch.
    // Final score of a finished game; timeMillis is always 0.
    // Theoretical maximum of this variant is 424 (105 upper + 35 bonus + 284 lower); the cap leaves headroom.
    YATZI(higherScoreWins = true, lowerTimeWins = true, countsForGlobalRanking = false, maxScore = 1000),

    // Three-dart average x100 of a finished game; timeMillis is always 0.
    // Difficulty encodes the countdown (LOW = 301, HIGH = 501), not hardness.
    // A three-dart average can never exceed 180.00, so 18000 is the hard ceiling.
    DART_COUNTER(higherScoreWins = true, lowerTimeWins = true, countsForGlobalRanking = false, maxScore = 18000),

    // One submission per winning player (score = 1); the board shows the number of wins. timeMillis is always 0.
    UNDERCOVER(higherScoreWins = true, lowerTimeWins = true, countsForGlobalRanking = false, sumsWins = true, maxScore = 1),

    // Daily C puzzle without a difficulty (always MEDIUM): 100 / 60 / 30 points for solving on the
    // first / second / third try, failed runs submit nothing. The solve time ranks equal points.
    C_CHALLENGE(higherScoreWins = true, lowerTimeWins = true, maxScore = 100);

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
