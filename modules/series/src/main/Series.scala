package lila.series

import chess.Clock.Config as ClockConfig
import chess.format.Fen
import chess.{ ByColor, Color }
import reactivemongo.api.bson.Macros.Annotations.Key
import scalalib.ThreadLocalRandom

import lila.core.id.{ GameId, SeriesId }
import lila.core.userId.UserId
import lila.rating.PerfType

case class Series(
    @Key("_id") id: SeriesId,
    players: ByColor[UserId],
    scores: ByColor[Int],
    gameIds: List[GameId],
    results: List[Option[Color]], // 각 게임 승자 색상 (None=무승부)
    currentRound: Int,
    status: Series.Status,
    phase: Series.Phase,                   // 밴픽 단계
    picks: ByColor[List[OpeningPreset]],   // 각 플레이어가 픽한 오프닝 (최대 5개)
    bans: ByColor[List[OpeningPreset]],    // 각 플레이어가 밴한 상대 오프닝 (최대 2개)
    confirmedPicks: ByColor[Boolean],      // 픽 확정 여부
    confirmedBans: ByColor[Boolean],       // 밴 확정 여부
    winner: Option[Color],
    variant: chess.variant.Variant,
    clock: ClockConfig,
    openings: List[OpeningPreset], // 각 게임의 오프닝 (밴픽 후 결정)
    createdAt: Instant,
    finishedAt: Option[Instant]
):
  def isCreated = status == Series.Status.Created
  def isStarted = status == Series.Status.Started
  def isFinished = status == Series.Status.Finished
  def isNotFinished = !isFinished

  def bestOf = Series.bestOf

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

  def addGame(gameId: GameId): Series =
    copy(
      gameIds = gameIds :+ gameId,
      status = if status == Series.Status.Created then Series.Status.Started else status
    )

  def recordResult(winnerColor: Option[Color]): Series =
    val newScores = winnerColor match
      case Some(color) => scores.update(color, _ + 1)
      case None        => scores // draw doesn't count
    val finished = newScores.white >= winsNeeded || newScores.black >= winsNeeded
    copy(
      scores = newScores,
      results = results :+ winnerColor,
      currentRound = if finished then currentRound else currentRound + 1,
      status = if finished then Series.Status.Finished else status,
      winner = if finished then Some(if newScores.white >= winsNeeded then Color.White else Color.Black) else None,
      finishedAt = if finished then Some(nowInstant) else None
    )

  // 밴픽 관련 헬퍼 메서드

  // 플레이어의 남은 픽 (상대에게 밴되지 않은 것)
  def remainingPicks(color: Color): List[OpeningPreset] =
    val myPicks = picks(color)
    val opponentBans = bans(!color)
    myPicks.filterNot(p => opponentBans.exists(_.name == p.name))

  // 밴된 모든 오프닝 (양측 합산)
  def bannedOpenings: List[OpeningPreset] =
    bans.white ++ bans.black

  // 픽 설정
  def setPicks(color: Color, newPicks: List[OpeningPreset]): Series =
    copy(picks = picks.update(color, _ => newPicks))

  // 밴 설정
  def setBans(color: Color, newBans: List[OpeningPreset]): Series =
    copy(bans = bans.update(color, _ => newBans))

  // 페이즈 변경
  def setPhase(newPhase: Series.Phase): Series =
    copy(phase = newPhase)

  // 픽 확정
  def confirmPicks(color: Color): Series =
    copy(confirmedPicks = confirmedPicks.update(color, _ => true))

  // 픽 확정 취소
  def cancelConfirmPicks(color: Color): Series =
    copy(confirmedPicks = confirmedPicks.update(color, _ => false))

  // 밴 확정
  def confirmBans(color: Color): Series =
    copy(confirmedBans = confirmedBans.update(color, _ => true))

  // 밴 확정 취소
  def cancelConfirmBans(color: Color): Series =
    copy(confirmedBans = confirmedBans.update(color, _ => false))

  // 양측 픽 확정 여부
  def bothPicksConfirmed: Boolean = confirmedPicks.white && confirmedPicks.black

  // 양측 밴 확정 여부
  def bothBansConfirmed: Boolean = confirmedBans.white && confirmedBans.black

  // 오프닝 추가 (게임 시작 시)
  def addOpening(opening: OpeningPreset): Series =
    copy(openings = openings :+ opening)

object Series:

  val bestOf = 5
  val winsNeeded = 3

  enum Status(val id: Int):
    case Created extends Status(10)
    case Started extends Status(20)
    case Finished extends Status(30)

  object Status:
    def apply(id: Int): Option[Status] = values.find(_.id == id)

  // 밴픽 단계
  enum Phase(val id: Int):
    case Picking extends Phase(10)        // 각자 5개 오프닝 픽
    case Banning extends Phase(20)        // 상대 픽 중 2개 밴
    case Game1Shuffling extends Phase(25) // Game 1 랜덤 선택 애니메이션 (10초)
    case Playing extends Phase(30)        // 게임 진행 중
    case Selecting extends Phase(40)      // 패자가 다음 오프닝 선택 (Game 2+)
    case Finished extends Phase(50)       // 시리즈 종료

  object Phase:
    def apply(id: Int): Option[Phase] = values.find(_.id == id)

  def makeId: SeriesId = SeriesId(ThreadLocalRandom.nextString(8))

  def make(
      players: ByColor[UserId],
      variant: chess.variant.Variant,
      clock: ClockConfig
  ): Series = Series(
    id = makeId,
    players = players,
    scores = ByColor.fill(0),
    gameIds = Nil,
    results = Nil,
    currentRound = 1,
    status = Status.Created,
    phase = Phase.Picking,
    picks = ByColor.fill(Nil),
    bans = ByColor.fill(Nil),
    confirmedPicks = ByColor.fill(false),
    confirmedBans = ByColor.fill(false),
    winner = None,
    variant = variant,
    clock = clock,
    openings = Nil, // 밴픽 완료 후 결정
    createdAt = nowInstant,
    finishedAt = None
  )
