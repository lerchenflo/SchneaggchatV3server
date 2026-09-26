package com.lerchenflo.schneaggchatv3server.games.model

import com.lerchenflo.schneaggchatv3server.games.GamesService
import org.springframework.data.domain.Sort

enum class Game(
    val higherScoreWins: Boolean,
    val lowerTimeWins: Boolean,
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
    WORDLE(higherScoreWins = false, lowerTimeWins = true);

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
