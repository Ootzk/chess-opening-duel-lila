package lila.`match`

import chess.Clock.Config as ClockConfig
import chess.format.Fen
import chess.{ ByColor, Color }

import lila.common.Bus
import lila.core.id.{ GameId, MatchId }
import lila.core.userId.UserId

final class MatchApi(
    repo: MatchRepo,
    gameRepo: lila.core.game.GameRepo,
    newPlayer: lila.core.game.NewPlayer,
    userApi: lila.core.user.UserApi,
    onStart: lila.core.game.OnStart,
    cacheApi: lila.memo.CacheApi
)(using Executor, lila.core.game.IdGenerator):

  import lila.core.game.{ Game, Player }

  def create(
      players: ByColor[UserId],
      variant: chess.variant.Variant,
      clock: ClockConfig,
      initialFen: Option[Fen.Full]
  ): Fu[Match] =
    val m = Match.make(players, variant, clock, initialFen)
    repo.insert(m).inject(m)

  def byId(id: MatchId): Fu[Option[Match]] = repo.byId(id)

  def byGameId(gameId: GameId): Fu[Option[Match]] = repo.byGameId(gameId)

  def finishGame(matchId: MatchId, gameId: GameId, winnerColor: Option[Color]): Funit =
    repo.recordResult(matchId, winnerColor).map:
      case Some(m) if m.isFinished =>
        Bus.pub(MatchFinished(m))
      case Some(m) =>
        Bus.pub(MatchGameFinished(m, gameId, winnerColor))
      case _ => ()

  def createNextGame(matchId: MatchId): Fu[Option[GameId]] =
    repo.byId(matchId).flatMapz: m =>
      if m.isFinished || m.currentRound > Match.bestOf then fuccess(None)
      else
        for
          whiteUser <- userApi.withPerf(m.colorForRound(m.currentRound).white, m.perfType)
          blackUser <- userApi.withPerf(m.colorForRound(m.currentRound).black, m.perfType)
          game <- makeGame(m, whiteUser, blackUser)
          _ <- gameRepo.insertDenormalized(game)
          _ <- repo.addGame(m.id, game.id)
          _ <- onStart.exec(game.id)
        yield Some(game.id)

  private def makeGame(
      m: Match,
      whiteUser: Option[lila.core.user.UserWithPerfs],
      blackUser: Option[lila.core.user.UserWithPerfs]
  )(using idGenerator: lila.core.game.IdGenerator): Fu[Game] =
    idGenerator.game.dmap: id =>
      val start = chess.Game(
        variantOption = Some(m.variant),
        fen = m.initialFen
      )
      Game
        .make(
          id = id,
          chess = start.copy(clock = chess.Clock(m.clock).some),
          players = ByColor: color =>
            val user = color.fold(whiteUser, blackUser)
            newPlayer(color, user, m.perfType)
          ,
          mode = chess.Rated.No,
          source = lila.core.game.Source.Friend,
          pgnImport = None
        )
        .copy(
          metadata = lila.core.game.newMetadata(lila.core.game.Source.Friend).copy(
            matchId = m.id.some
          )
        )
        .start

// Events
case class MatchFinished(m: Match)
case class MatchGameFinished(m: Match, gameId: GameId, winnerColor: Option[Color])