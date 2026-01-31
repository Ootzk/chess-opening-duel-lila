package lila.`match`

import chess.Clock.Config as ClockConfig
import chess.format.Fen
import chess.{ ByColor, Color }
import reactivemongo.api.bson.Macros.Annotations.Key
import scalalib.ThreadLocalRandom

import lila.core.id.{ GameId, MatchId }
import lila.core.userId.UserId
import lila.rating.PerfType

case class Match(
    @Key("_id") id: MatchId,
    players: ByColor[UserId],
    scores: ByColor[Int],
    gameIds: List[GameId],
    results: List[Option[Color]], // 각 게임 승자 색상 (None=무승부)
    currentRound: Int,
    status: Match.Status,
    winner: Option[Color],
    variant: chess.variant.Variant,
    clock: ClockConfig,
    openings: List[OpeningPreset], // 각 게임의 오프닝 (bestOf 개수)
    createdAt: Instant,
    finishedAt: Option[Instant]
):
  def isCreated = status == Match.Status.Created
  def isStarted = status == Match.Status.Started
  def isFinished = status == Match.Status.Finished
  def isNotFinished = !isFinished

  def bestOf = Match.bestOf

  def speed = chess.Speed(clock)
  def perfType: PerfType = lila.rating.PerfType(variant, speed)

  def player(color: Color): UserId = players(color)
  def score(color: Color): Int = scores(color)

  def colorOf(userId: UserId): Option[Color] =
    if players.white == userId then Some(Color.White)
    else if players.black == userId then Some(Color.Black)
    else None

  def currentGame: Option[GameId] = gameIds.lastOption

  // 현재 라운드의 오프닝
  def openingForRound(round: Int): Option[OpeningPreset] =
    openings.lift(round - 1)

  // 현재 게임의 오프닝
  def currentOpening: Option[OpeningPreset] = openingForRound(currentRound)

  // 게임 ID와 결과를 짝지어 반환 (UI용)
  def gamesWithResults: List[(GameId, Option[Color])] =
    gameIds.zipAll(results, GameId(""), None).filter(_._1.value.nonEmpty)

  def winsNeeded: Int = (bestOf / 2) + 1

  def hasEnded: Boolean = scores.white >= winsNeeded || scores.black >= winsNeeded

  def colorForRound(round: Int): ByColor[UserId] =
    if round % 2 == 1 then players
    else players.swap

  def addGame(gameId: GameId): Match =
    copy(
      gameIds = gameIds :+ gameId,
      status = if status == Match.Status.Created then Match.Status.Started else status
    )

  def recordResult(winnerColor: Option[Color]): Match =
    val newScores = winnerColor match
      case Some(color) => scores.update(color, _ + 1)
      case None        => scores // draw doesn't count
    val finished = newScores.white >= winsNeeded || newScores.black >= winsNeeded
    copy(
      scores = newScores,
      results = results :+ winnerColor,
      currentRound = if finished then currentRound else currentRound + 1,
      status = if finished then Match.Status.Finished else status,
      winner = if finished then Some(if newScores.white >= winsNeeded then Color.White else Color.Black) else None,
      finishedAt = if finished then Some(nowInstant) else None
    )

object Match:

  val bestOf = 5
  val winsNeeded = 3

  enum Status(val id: Int):
    case Created extends Status(10)
    case Started extends Status(20)
    case Finished extends Status(30)

  object Status:
    def apply(id: Int): Option[Status] = values.find(_.id == id)

  def makeId: MatchId = MatchId(ThreadLocalRandom.nextString(8))

  def make(
      players: ByColor[UserId],
      variant: chess.variant.Variant,
      clock: ClockConfig
  ): Match = Match(
    id = makeId,
    players = players,
    scores = ByColor.fill(0),
    gameIds = Nil,
    results = Nil,
    currentRound = 1,
    status = Status.Created,
    winner = None,
    variant = variant,
    clock = clock,
    openings = OpeningPresets.randomN(bestOf),
    createdAt = nowInstant,
    finishedAt = None
  )