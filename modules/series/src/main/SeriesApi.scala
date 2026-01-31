package lila.series

import chess.Clock.Config as ClockConfig
import chess.format.Fen
import chess.{ ByColor, Color }

import lila.common.Bus
import lila.core.id.{ GameId, SeriesId }
import lila.core.user.GameUser
import lila.core.userId.UserId

final class SeriesApi(
    repo: SeriesRepo,
    gameRepo: lila.core.game.GameRepo,
    newPlayer: lila.core.game.NewPlayer,
    userApi: lila.core.user.UserApi,
    onStart: lila.core.game.OnStart,
    cacheApi: lila.memo.CacheApi
)(using Executor, lila.core.game.IdGenerator):

  import lila.core.game.Game

  def create(
      players: ByColor[UserId],
      variant: chess.variant.Variant,
      clock: ClockConfig
  ): Fu[Series] =
    val s = Series.make(players, variant, clock)
    repo.insert(s).inject(s)

  def byId(id: SeriesId): Fu[Option[Series]] = repo.byId(id)

  def byGameId(gameId: GameId): Fu[Option[Series]] = repo.byGameId(gameId)

  def addFirstGame(seriesId: SeriesId, gameId: GameId): Funit =
    repo.addGame(seriesId, gameId)

  def finishGame(seriesId: SeriesId, gameId: GameId, winnerId: Option[UserId]): Funit =
    repo.byId(seriesId).flatMap:
      case None => funit
      case Some(s) =>
        // Convert winner userId to series color
        val winnerSeriesColor = winnerId.flatMap(s.colorOf)
        repo.recordResult(seriesId, winnerSeriesColor).map:
          case Some(updated) if updated.isFinished =>
            Bus.pub(SeriesFinished(updated))
          case Some(updated) =>
            Bus.pub(SeriesGameFinished(updated, gameId, winnerId))
          case _ => ()

  def createNextGame(seriesId: SeriesId): Fu[Option[Game]] =
    repo.byId(seriesId).flatMap:
      case None => fuccess(None)
      case Some(s) =>
        if s.isFinished || s.currentRound > Series.bestOf then fuccess(None)
        else
          val colorMapping = s.colorForRound(s.currentRound)
          val initialFen = s.openingForRound(s.currentRound).map(_.fen)
          for
            whiteUser <- userApi.byIdWithPerf(colorMapping.white, s.perfType)
            blackUser <- userApi.byIdWithPerf(colorMapping.black, s.perfType)
            game <- makeGame(s, whiteUser, blackUser)
            _ <- gameRepo.insertDenormalized(game, initialFen)
            _ <- repo.addGame(s.id, game.id)
            _ <- onStart.exec(game.id)
          yield Some(game)

  private def makeGame(
      s: Series,
      whiteUser: GameUser,
      blackUser: GameUser
  )(using idGenerator: lila.core.game.IdGenerator): Fu[Game] =
    idGenerator.game.dmap: id =>
      // 현재 라운드의 오프닝 가져오기
      val opening = s.openingForRound(s.currentRound)
      val initialFen = opening.map(_.fen)

      // FEN으로 게임 생성 (FromPosition variant 사용)
      val chessGame = initialFen match
        case Some(fen) =>
          Fen.readWithMoveNumber(chess.variant.FromPosition, fen) match
            case Some(sit) =>
              chess.Game(
                position = sit.position,
                ply = sit.ply,
                startedAtPly = sit.ply,
                clock = chess.Clock(s.clock).some
              )
            case None =>
              // FEN 파싱 실패 시 폴백
              chess.Game(
                position = s.variant.initialPosition,
                clock = chess.Clock(s.clock).some
              )
        case None =>
          chess.Game(
            position = s.variant.initialPosition,
            clock = chess.Clock(s.clock).some
          )

      lila.core.game
        .newGame(
          chess = chessGame,
          players = ByColor: color =>
            lila.game.Player.make(color, if color.white then whiteUser else blackUser)
          ,
          rated = chess.Rated.No,
          source = lila.core.game.Source.Friend,
          daysPerTurn = None,
          pgnImport = None
        )
        .withId(id)
        .copy(
          metadata = lila.core.game.newMetadata(lila.core.game.Source.Friend).copy(
            seriesId = s.id.some
          )
        )
        .start

// Events
case class SeriesFinished(s: Series)
case class SeriesGameFinished(s: Series, gameId: GameId, winnerId: Option[UserId])
