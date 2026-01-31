package lila.`match`

import chess.Clock.Config as ClockConfig
import chess.format.Fen
import chess.{ ByColor, Color }

import lila.common.Bus
import lila.core.id.{ GameId, MatchId }
import lila.core.user.GameUser
import lila.core.userId.UserId

final class MatchApi(
    repo: MatchRepo,
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
  ): Fu[Match] =
    val m = Match.make(players, variant, clock)
    repo.insert(m).inject(m)

  def byId(id: MatchId): Fu[Option[Match]] = repo.byId(id)

  def byGameId(gameId: GameId): Fu[Option[Match]] = repo.byGameId(gameId)

  def addFirstGame(matchId: MatchId, gameId: GameId): Funit =
    repo.addGame(matchId, gameId)

  def finishGame(matchId: MatchId, gameId: GameId, winnerId: Option[UserId]): Funit =
    repo.byId(matchId).flatMap:
      case None => funit
      case Some(m) =>
        // Convert winner userId to match color
        val winnerMatchColor = winnerId.flatMap(m.colorOf)
        repo.recordResult(matchId, winnerMatchColor).map:
          case Some(updated) if updated.isFinished =>
            Bus.pub(MatchFinished(updated))
          case Some(updated) =>
            Bus.pub(MatchGameFinished(updated, gameId, winnerId))
          case _ => ()

  def createNextGame(matchId: MatchId): Fu[Option[Game]] =
    repo.byId(matchId).flatMap:
      case None => fuccess(None)
      case Some(m) =>
        if m.isFinished || m.currentRound > Match.bestOf then fuccess(None)
        else
          val colorMapping = m.colorForRound(m.currentRound)
          val initialFen = m.openingForRound(m.currentRound).map(_.fen)
          for
            whiteUser <- userApi.byIdWithPerf(colorMapping.white, m.perfType)
            blackUser <- userApi.byIdWithPerf(colorMapping.black, m.perfType)
            game <- makeGame(m, whiteUser, blackUser)
            _ <- gameRepo.insertDenormalized(game, initialFen)
            _ <- repo.addGame(m.id, game.id)
            _ <- onStart.exec(game.id)
          yield Some(game)

  private def makeGame(
      m: Match,
      whiteUser: GameUser,
      blackUser: GameUser
  )(using idGenerator: lila.core.game.IdGenerator): Fu[Game] =
    idGenerator.game.dmap: id =>
      // 현재 라운드의 오프닝 가져오기
      val opening = m.openingForRound(m.currentRound)
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
                clock = chess.Clock(m.clock).some
              )
            case None =>
              // FEN 파싱 실패 시 폴백
              chess.Game(
                position = m.variant.initialPosition,
                clock = chess.Clock(m.clock).some
              )
        case None =>
          chess.Game(
            position = m.variant.initialPosition,
            clock = chess.Clock(m.clock).some
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
            matchId = m.id.some
          )
        )
        .start

// Events
case class MatchFinished(m: Match)
case class MatchGameFinished(m: Match, gameId: GameId, winnerId: Option[UserId])