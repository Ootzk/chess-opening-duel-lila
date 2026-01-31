package lila.challenge

import chess.format.Fen
import chess.variant.Variant
import chess.{ Position, ByColor, Rated }

import lila.core.id.MatchId
import lila.core.user.GameUser
import lila.`match`.Match

final private class ChallengeJoiner(
    gameRepo: lila.game.GameRepo,
    userApi: lila.core.user.UserApi,
    onStart: lila.core.game.OnStart,
    matchApi: lila.`match`.MatchApi
)(using Executor, Scheduler):

  def apply(c: Challenge, destUser: GameUser): FuRaise[String, Pov] = for
    exists <- gameRepo.exists(c.gameId)
    _ <- raiseIf(exists)("The challenge has already been accepted")
    origUser <- c.challengerUserId.so(userApi.byIdWithPerf(_, c.perfType))
    matchOpt <- createMatchIfNeeded(c, origUser, destUser)
    initialFen = matchOpt.flatMap(_.openingForRound(1)).map(_.fen)
    game = ChallengeJoiner.createGame(c, origUser, destUser, matchOpt.map(_.id), initialFen)
    _ <- gameRepo.insertDenormalized(game, initialFen)
    _ <- matchOpt.map(_.id).so(mid => matchApi.addFirstGame(mid, game.id))
    _ <- onStartOrRetry(game.id).recover: _ =>
      logger.error(s"onStart failed for game ${game.id}")
  yield Pov(game, !c.finalColor)

  private def createMatchIfNeeded(
      c: Challenge,
      origUser: GameUser,
      destUser: GameUser
  ): Fu[Option[Match]] =
    c.matchType match
      case Some(Challenge.MatchType.OpeningDuel) =>
        (origUser.map(_.id), destUser.map(_.id)).tupled match
          case Some((origId, destId)) =>
            val players = ByColor(
              white = if c.finalColor.white then origId else destId,
              black = if c.finalColor.white then destId else origId
            )
            val clock = c.timeControl match
              case Challenge.TimeControl.Clock(config) => config
              case _ => chess.Clock.Config(chess.Clock.LimitSeconds(300), chess.Clock.IncrementSeconds(0))
            matchApi.create(players, c.variant, clock).map(_.some)
          case None => fuccess(none[Match]) // Anonymous players can't play matches
      case _ => fuccess(none[Match])

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
      matchId: Option[MatchId] = None,
      matchInitialFen: Option[Fen.Full] = None  // Match의 오프닝 FEN
  ): Game =
    // Match 게임이면 Match의 오프닝 FEN 사용, 아니면 Challenge의 initialFen 사용
    val effectiveFen = matchInitialFen.orElse(c.initialFen)
    val effectiveVariant = if matchInitialFen.isDefined then chess.variant.FromPosition else c.variant
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
    matchId.fold(game)(mid => game.copy(metadata = game.metadata.copy(matchId = mid.some))).start

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
