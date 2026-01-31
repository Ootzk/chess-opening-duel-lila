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

  // 밴픽 관련 메서드

  // 플레이어의 픽 설정 (최대 5개)
  def setPicks(seriesId: SeriesId, userId: UserId, picks: List[OpeningPreset]): Fu[Option[Series]] =
    repo.byId(seriesId).flatMap:
      case None => fuccess(None)
      case Some(s) =>
        s.colorOf(userId) match
          case None => fuccess(None)
          case Some(color) =>
            if s.phase != Series.Phase.Picking then fuccess(None)
            else
              val limitedPicks = picks.take(5)
              val updated = s.setPicks(color, limitedPicks)
              repo.update(updated).inject(Some(updated))

  // 플레이어의 밴 설정 (최대 2개, 상대 픽에서만 선택 가능)
  def setBans(seriesId: SeriesId, userId: UserId, bans: List[OpeningPreset]): Fu[Option[Series]] =
    repo.byId(seriesId).flatMap:
      case None => fuccess(None)
      case Some(s) =>
        s.colorOf(userId) match
          case None => fuccess(None)
          case Some(color) =>
            if s.phase != Series.Phase.Banning then fuccess(None)
            else
              // 상대방의 픽에서만 밴 가능
              val opponentPicks = s.picks(!color)
              val validBans = bans.filter(b => opponentPicks.exists(_.name == b.name)).take(2)
              val updated = s.setBans(color, validBans)
              repo.update(updated).inject(Some(updated))

  // 픽 확정 (플레이어별로 confirm, 양측 완료 시 페이즈 전환)
  def confirmPicks(seriesId: SeriesId, userId: UserId): Fu[Option[Series]] =
    repo.byId(seriesId).flatMap:
      case None => fuccess(None)
      case Some(s) =>
        s.colorOf(userId) match
          case None => fuccess(None)
          case Some(color) =>
            if s.phase != Series.Phase.Picking then fuccess(None)
            else if s.picks(color).isEmpty then fuccess(None)
            else if s.confirmedPicks(color) then fuccess(Some(s)) // 이미 확정됨
            else
              val confirmed = s.confirmPicks(color)
              // 양측 모두 확정되면 Banning phase로 전환
              val updated =
                if confirmed.bothPicksConfirmed then confirmed.setPhase(Series.Phase.Banning)
                else confirmed
              repo.update(updated).inject(Some(updated))

  // 픽 확정 취소
  def cancelConfirmPicks(seriesId: SeriesId, userId: UserId): Fu[Option[Series]] =
    repo.byId(seriesId).flatMap:
      case None => fuccess(None)
      case Some(s) =>
        s.colorOf(userId) match
          case None => fuccess(None)
          case Some(color) =>
            if s.phase != Series.Phase.Picking then fuccess(None)
            else if !s.confirmedPicks(color) then fuccess(Some(s)) // 이미 취소됨
            else
              val updated = s.cancelConfirmPicks(color)
              repo.update(updated).inject(Some(updated))

  // 밴 확정 (플레이어별로 confirm, 양측 완료 시 페이즈 전환)
  def confirmBans(seriesId: SeriesId, userId: UserId): Fu[Option[Series]] =
    repo.byId(seriesId).flatMap:
      case None => fuccess(None)
      case Some(s) =>
        s.colorOf(userId) match
          case None => fuccess(None)
          case Some(color) =>
            if s.phase != Series.Phase.Banning then fuccess(None)
            else if s.bans(color).isEmpty then fuccess(None)
            else if s.confirmedBans(color) then fuccess(Some(s)) // 이미 확정됨
            else
              val confirmed = s.confirmBans(color)
              // 양측 모두 확정되면 Game1Shuffling phase로 전환
              val updated =
                if confirmed.bothBansConfirmed then confirmed.setPhase(Series.Phase.Game1Shuffling)
                else confirmed
              repo.update(updated).inject(Some(updated))

  // 밴 확정 취소
  def cancelConfirmBans(seriesId: SeriesId, userId: UserId): Fu[Option[Series]] =
    repo.byId(seriesId).flatMap:
      case None => fuccess(None)
      case Some(s) =>
        s.colorOf(userId) match
          case None => fuccess(None)
          case Some(color) =>
            if s.phase != Series.Phase.Banning then fuccess(None)
            else if !s.confirmedBans(color) then fuccess(Some(s)) // 이미 취소됨
            else
              val updated = s.cancelConfirmBans(color)
              repo.update(updated).inject(Some(updated))

  // Game 1 오프닝 결정 (밴된 오프닝 중 랜덤)
  def selectGame1Opening(seriesId: SeriesId): Fu[Option[Series]] =
    repo.byId(seriesId).flatMap:
      case None => fuccess(None)
      case Some(s) =>
        if s.phase != Series.Phase.Game1Shuffling then fuccess(None)
        else
          val bannedOpenings = s.bannedOpenings
          if bannedOpenings.isEmpty then fuccess(None)
          else
            val selectedOpening = scala.util.Random.shuffle(bannedOpenings).head
            val updated = s.addOpening(selectedOpening).setPhase(Series.Phase.Playing)
            repo.update(updated).inject(Some(updated))

  // 패자가 다음 게임 오프닝 선택 (Game 2+)
  def selectNextOpening(seriesId: SeriesId, userId: UserId, opening: OpeningPreset): Fu[Option[Series]] =
    repo.byId(seriesId).flatMap:
      case None => fuccess(None)
      case Some(s) =>
        s.colorOf(userId) match
          case None => fuccess(None)
          case Some(color) =>
            if s.phase != Series.Phase.Selecting then fuccess(None)
            else
              // 자기 남은 픽에서만 선택 가능
              val remaining = s.remainingPicks(color)
              if !remaining.exists(_.name == opening.name) then fuccess(None)
              else
                val updated = s.addOpening(opening).setPhase(Series.Phase.Playing)
                repo.update(updated).inject(Some(updated))

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
