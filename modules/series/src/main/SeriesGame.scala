package lila.series

import lila.core.id.GameId

/** 시리즈 내 개별 게임 정보 */
case class SeriesGame(
    gameId: GameId,
    round: Int,
    openingId: SeriesOpeningId,
    whitePlayerIndex: Int,
    result: Option[GameResult] = None
)

enum GameResult:
  case WhiteWins
  case BlackWins
  case Draw

object GameResult:
  def fromWinnerIndex(winnerIndex: Option[Int], whitePlayerIndex: Int): GameResult =
    winnerIndex match
      case Some(idx) if idx == whitePlayerIndex => WhiteWins
      case Some(_) => BlackWins
      case None => Draw
