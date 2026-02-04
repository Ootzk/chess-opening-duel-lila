package lila.challenge

import chess.format.Fen
import chess.variant.Variant
import chess.{ Position, ByColor, Rated }

import lila.core.id.SeriesId
import lila.core.user.GameUser
import lila.series.Series

final private class ChallengeJoiner(
    gameRepo: lila.game.GameRepo,
    userApi: lila.core.user.UserApi,
    onStart: lila.core.game.OnStart,
    seriesApi: lila.series.SeriesApi
)(using Executor, Scheduler):

  import Challenge.AcceptResult

  def apply(c: Challenge, destUser: GameUser): FuRaise[String, AcceptResult] = for
    exists <- gameRepo.exists(c.gameId)
    _ <- raiseIf(exists)("The challenge has already been accepted")
    origUser <- c.challengerUserId.so(userApi.byIdWithPerf(_, c.perfType))
    seriesOpt <- createSeriesIfNeeded(c, origUser, destUser)
    result <- seriesOpt match
      // Series가 Picking phase면 게임 생성 스킵, 밴픽 페이지로 이동
      case Some(s) if s.phase == lila.series.Series.Phase.Picking =>
        fuccess(AcceptResult.SeriesPicking(s.id))
      // 그 외의 경우 게임 생성
      case _ =>
        val initialFen = seriesOpt.flatMap(_.openingForRound(1)).map(_.fen)
        val game = ChallengeJoiner.createGame(c, origUser, destUser, seriesOpt.map(_.id), initialFen)
        for
          _ <- gameRepo.insertDenormalized(game, initialFen)
          _ <- seriesOpt.map(_.id).so(sid => seriesApi.addFirstGame(sid, game.id))
          _ <- onStartOrRetry(game.id).recover: _ =>
            logger.error(s"onStart failed for game ${game.id}")
        yield AcceptResult.GameStarted(Pov(game, !c.finalColor))
  yield result

  private def createSeriesIfNeeded(
      c: Challenge,
      origUser: GameUser,
      destUser: GameUser
  ): Fu[Option[Series]] =
    c.matchType match
      case Some(Challenge.MatchType.OpeningDuel) =>
        (origUser.map(_.id), destUser.map(_.id)).tupled match
          case Some((origId, destId)) =>
            // player0 = white in round 1, player1 = black in round 1
            val player0 = if c.finalColor.white then origId else destId
            val player1 = if c.finalColor.white then destId else origId
            val clock = c.timeControl match
              case Challenge.TimeControl.Clock(config) => config
              case _ => chess.Clock.Config(chess.Clock.LimitSeconds(300), chess.Clock.IncrementSeconds(0))
            seriesApi.create(player0, player1, c.variant, clock).map(_.some)
          case None => fuccess(none[Series]) // Anonymous players can't play series
      case _ => fuccess(none[Series])

  private def onStartOrRetry(id: GameId, retries: Int = 3): Funit =
    onStart
      .exec(id)
      .recoverWith:
        case _ if retries > 0 =>
          logger.warn(s"onStart failed for game $id. Retries left: $retries")
          lila.common.LilaFuture.delay(500.millis)(onStartOrRetry(id, retries - 1))
      .void

private object ChallengeJoiner:

  def createGame(
      c: Challenge,
      origUser: GameUser,
      destUser: GameUser,
      seriesId: Option[SeriesId] = None,
      seriesInitialFen: Option[Fen.Full] = None  // Series의 오프닝 FEN
  ): Game =
    // Series 게임이면 Series의 오프닝 FEN 사용, 아니면 Challenge의 initialFen 사용
    val effectiveFen = seriesInitialFen.orElse(c.initialFen)
    val effectiveVariant = if seriesInitialFen.isDefined then chess.variant.FromPosition else c.variant
    val (chessGame, state) = gameSetup(effectiveVariant, c.timeControl, effectiveFen)
    val game = lila.core.game
      .newGame(
        chess = chessGame,
        players = ByColor: color =>
          lila.game.Player.make(color, if c.finalColor == color then origUser else destUser),
        rated = c.rated.map(_ && !chessGame.position.variant.fromPosition),
        source = lila.core.game.Source.Friend,
        daysPerTurn = c.daysPerTurn,
        pgnImport = None,
        rules = c.rules
      )
      .withId(c.gameId)
      .pipe(addGameHistory(state))
    seriesId.fold(game)(sid => game.copy(metadata = game.metadata.copy(seriesId = sid.some))).start

  def gameSetup(
      variant: Variant,
      tc: Challenge.TimeControl,
      initialFen: Option[Fen.Full]
  ): (chess.Game, Option[Position.AndFullMoveNumber]) =

    def makeChess(variant: Variant): chess.Game =
      chess.Game(position = variant.initialPosition, clock = tc.realTime.map(_.toClock))

    val baseState = initialFen
      .ifTrue(variant.fromPosition || variant.chess960)
      .flatMap:
        Fen.readWithMoveNumber(variant, _)

    baseState.fold(makeChess(variant) -> none[Position.AndFullMoveNumber]): sp =>
      val game = chess.Game(
        position = sp.position,
        ply = sp.ply,
        startedAtPly = sp.ply,
        clock = tc.realTime.map(_.toClock)
      )
      if variant.fromPosition && Fen.write(game).isInitial then makeChess(chess.variant.Standard) -> none
      else game -> baseState

  def addGameHistory(position: Option[Position.AndFullMoveNumber])(game: Game): Game =
    position.fold(game): sp =>
      game.copy(
        chess = game.chess.copy(
          position = game.position.copy(history = sp.position.history),
          ply = sp.ply
        )
      )
