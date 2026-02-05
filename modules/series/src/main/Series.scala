package lila.series

import chess.Clock.Config as ClockConfig
import scalalib.ThreadLocalRandom

import lila.core.id.{ GameId, SeriesId }
import lila.core.userId.UserId
import lila.rating.PerfType

case class Series(
    id: SeriesId,
    players: (SeriesPlayer, SeriesPlayer),
    openings: List[SeriesOpening],
    games: List[SeriesGame],
    phase: Series.Phase,
    phaseStartedAt: Option[Instant],
    status: Series.Status,
    variant: chess.variant.Variant,
    clock: ClockConfig,
    createdAt: Instant,
    finishedAt: Option[Instant] = None
):
  // ===== 플레이어 접근 =====

  def player(index: Int): SeriesPlayer =
    if index == 0 then players._1 else players._2

  def playerByUserId(userId: UserId): Option[SeriesPlayer] =
    if players._1.userId == userId then Some(players._1)
    else if players._2.userId == userId then Some(players._2)
    else None

  def playerIndex(userId: UserId): Option[Int] =
    if players._1.userId == userId then Some(0)
    else if players._2.userId == userId then Some(1)
    else None

  def opponent(index: Int): SeriesPlayer = player(1 - index)

  // ===== 점수/상태 =====

  def currentRound: Int = games.length + 1

  def isCreated: Boolean = status == Series.Status.Created
  def isStarted: Boolean = status == Series.Status.Started
  def isFinished: Boolean = status == Series.Status.Finished
  def isNotFinished: Boolean = !isFinished

  def winner: Option[Int] =
    if players._1.score >= Series.winsNeeded then Some(0)
    else if players._2.score >= Series.winsNeeded then Some(1)
    else None

  def hasEnded: Boolean = winner.isDefined

  def speed = chess.Speed(clock)
  def perfType: PerfType = lila.rating.PerfType(variant, speed)

  // ===== 오프닝 필터링 =====

  def picks(playerIndex: Int): List[SeriesOpening] =
    openings.filter(o => o.ownerIndex == playerIndex && o.isPick)

  def bans(playerIndex: Int): List[SeriesOpening] =
    openings.filter(o => o.ownerIndex == playerIndex && o.isBan)

  def remainingPicks(playerIndex: Int): List[SeriesOpening] =
    val myPicks = picks(playerIndex)
    val bannedByOpponent = bans(1 - playerIndex)
    val bannedNames = bannedByOpponent.map(_.name).toSet
    myPicks.filterNot(p => bannedNames.contains(p.name) || p.isUsed)

  def unusedBans: List[SeriesOpening] =
    openings.filter(o => o.isBan && !o.isUsed)

  def allBans: List[SeriesOpening] =
    openings.filter(_.isBan)

  def bannedOpenings: List[SeriesOpening] = allBans

  // ===== 게임 색상 배정 =====

  def whitePlayerIndex(round: Int): Int =
    if round % 2 == 1 then 0 else 1

  def currentGame: Option[SeriesGame] =
    games.lastOption.filter(_.result.isEmpty)

  def currentGameId: Option[GameId] =
    currentGame.map(_.gameId)

  def lastFinishedGame: Option[SeriesGame] =
    games.reverse.find(_.result.isDefined)

  def lastGameLoser: Option[Int] =
    lastFinishedGame.flatMap: game =>
      game.result.flatMap:
        case GameResult.WhiteWins => Some(1 - game.whitePlayerIndex)
        case GameResult.BlackWins => Some(game.whitePlayerIndex)
        case GameResult.Draw => None

  def openingForRound(round: Int): Option[SeriesOpening] =
    games.find(_.round == round).flatMap(g => openings.find(_.id == g.openingId))

  def currentOpening: Option[SeriesOpening] =
    currentGame.flatMap(g => openings.find(_.id == g.openingId))

  def gamesWithResults: List[(GameId, Option[Int])] =
    games.map: g =>
      val winnerIdx = g.result.flatMap:
        case GameResult.WhiteWins => Some(g.whitePlayerIndex)
        case GameResult.BlackWins => Some(1 - g.whitePlayerIndex)
        case GameResult.Draw => None
      (g.gameId, winnerIdx)

  // ===== 상태 업데이트 메서드 =====

  def updatePlayer(index: Int, f: SeriesPlayer => SeriesPlayer): Series =
    if index == 0 then copy(players = (f(players._1), players._2))
    else copy(players = (players._1, f(players._2)))

  def updatePlayers(f: SeriesPlayer => SeriesPlayer): Series =
    copy(players = (f(players._1), f(players._2)))

  def addOpening(opening: SeriesOpening): Series =
    copy(openings = openings :+ opening)

  def addOpenings(newOpenings: List[SeriesOpening]): Series =
    copy(openings = openings ++ newOpenings)

  def removeOpeningsByOwnerAndSource(ownerIndex: Int, source: OpeningSource): Series =
    copy(openings = openings.filterNot(o => o.ownerIndex == ownerIndex && o.source == source))

  def markOpeningUsed(openingId: SeriesOpeningId, round: Int, method: SelectionMethod): Series =
    copy(openings = openings.map: o =>
      if o.id == openingId then o.markUsed(round, method)
      else o
    )

  def addGame(game: SeriesGame): Series =
    copy(
      games = games :+ game,
      status = if status == Series.Status.Created then Series.Status.Started else status
    )

  def finishGame(gameId: GameId, result: GameResult): Series =
    val updatedGames = games.map: g =>
      if g.gameId == gameId then g.copy(result = Some(result))
      else g

    val winnerIndex = games.find(_.gameId == gameId).flatMap: game =>
      result match
        case GameResult.WhiteWins => Some(game.whitePlayerIndex)
        case GameResult.BlackWins => Some(1 - game.whitePlayerIndex)
        case GameResult.Draw => None

    val updatedPlayers = winnerIndex match
      case Some(0) => (players._1.addWin, players._2)
      case Some(1) => (players._1, players._2.addWin)
      case _ => players

    val finished = updatedPlayers._1.score >= Series.winsNeeded ||
                   updatedPlayers._2.score >= Series.winsNeeded

    copy(
      games = updatedGames,
      players = updatedPlayers,
      status = if finished then Series.Status.Finished else status,
      finishedAt = if finished then Some(nowInstant) else finishedAt
    )

  def setPhase(newPhase: Series.Phase): Series =
    copy(phase = newPhase, phaseStartedAt = Some(nowInstant))

  def timeLeftInPhase: Int =
    phaseStartedAt match
      case None => Series.phaseTimeout
      case Some(startedAt) =>
        val elapsed = java.time.Duration.between(startedAt, nowInstant).getSeconds.toInt
        math.max(0, Series.phaseTimeout - elapsed)

  def bothPicksConfirmed: Boolean =
    players._1.confirmedPicks && players._2.confirmedPicks

  def bothBansConfirmed: Boolean =
    players._1.confirmedBans && players._2.confirmedBans

object Series:
  val bestOf = 5
  val winsNeeded = 3
  val maxPicks = 5
  val maxBans = 2
  val phaseTimeout = 30 // seconds

  enum Status(val id: Int):
    case Created extends Status(10)
    case Started extends Status(20)
    case Finished extends Status(30)

  object Status:
    def apply(id: Int): Option[Status] = values.find(_.id == id)

  enum Phase(val id: Int):
    case Picking extends Phase(10)
    case Banning extends Phase(20)
    case Shuffling extends Phase(25)
    case Playing extends Phase(30)
    case Selecting extends Phase(40)
    case Finished extends Phase(50)

  object Phase:
    def apply(id: Int): Option[Phase] = values.find(_.id == id)

  def makeId: SeriesId = SeriesId(ThreadLocalRandom.nextString(8))

  def make(
      player0: UserId,
      player1: UserId,
      variant: chess.variant.Variant,
      clock: ClockConfig
  ): Series =
    val now = nowInstant
    Series(
      id = makeId,
      players = (
        SeriesPlayer(player0, index = 0),
        SeriesPlayer(player1, index = 1)
      ),
      openings = Nil,
      games = Nil,
      phase = Phase.Picking,
      phaseStartedAt = Some(now),
      status = Status.Created,
      variant = variant,
      clock = clock,
      createdAt = now,
      finishedAt = None
    )
