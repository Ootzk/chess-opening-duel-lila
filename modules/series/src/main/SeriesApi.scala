package lila.series

import chess.Clock.Config as ClockConfig
import chess.format.Fen
import chess.ByColor

import lila.common.Bus
import lila.core.id.{ GameId, SeriesId }
import lila.core.user.GameUser
import lila.core.userId.UserId

final class SeriesApi(
    repo: SeriesRepo,
    gameRepo: lila.core.game.GameRepo,
    userApi: lila.core.user.UserApi,
    onStart: lila.core.game.OnStart
)(using Executor, lila.core.game.IdGenerator):

  import lila.core.game.Game

  def create(
      player0: UserId,
      player1: UserId,
      variant: chess.variant.Variant,
      clock: ClockConfig
  ): Fu[Series] =
    val s = Series.make(player0, player1, variant, clock)
    repo.insert(s).inject(s)

  def byId(id: SeriesId): Fu[Option[Series]] = repo.byId(id)

  def byGameId(gameId: GameId): Fu[Option[Series]] = repo.byGameId(gameId)

  // ===== 픽 설정 =====

  def setPicks(seriesId: SeriesId, userId: UserId, presets: List[OpeningPreset]): Fu[Option[Series]] =
    repo.byId(seriesId).flatMap:
      case None => fuccess(None)
      case Some(s) =>
        s.playerIndex(userId) match
          case None => fuccess(None)
          case Some(idx) =>
            if s.phase != Series.Phase.Picking then fuccess(None)
            else
              val withoutOldPicks = s.removeOpeningsByOwnerAndSource(idx, OpeningSource.Pick)
              val newPicks = presets.take(Series.maxPicks).map: preset =>
                SeriesOpening.makePick(preset, idx)
              val updated = withoutOldPicks.addOpenings(newPicks)
              repo.update(updated).inject(Some(updated))

  // ===== 밴 설정 =====

  def setBans(seriesId: SeriesId, userId: UserId, presets: List[OpeningPreset]): Fu[Option[Series]] =
    repo.byId(seriesId).flatMap:
      case None => fuccess(None)
      case Some(s) =>
        s.playerIndex(userId) match
          case None => fuccess(None)
          case Some(idx) =>
            if s.phase != Series.Phase.Banning then fuccess(None)
            else
              val opponentPicks = s.picks(1 - idx).map(_.name).toSet
              val validPresets = presets.filter(p => opponentPicks.contains(p.name))

              val withoutOldBans = s.removeOpeningsByOwnerAndSource(idx, OpeningSource.Ban)
              val newBans = validPresets.take(Series.maxBans).map: preset =>
                SeriesOpening.makeBan(preset, idx)
              val updated = withoutOldBans.addOpenings(newBans)
              repo.update(updated).inject(Some(updated))

  // ===== 픽 확정 =====

  def confirmPicks(seriesId: SeriesId, userId: UserId): Fu[Option[Series]] =
    repo.byId(seriesId).flatMap:
      case None => fuccess(None)
      case Some(s) =>
        s.playerIndex(userId) match
          case None => fuccess(None)
          case Some(idx) =>
            if s.phase != Series.Phase.Picking then fuccess(None)
            else if s.picks(idx).isEmpty then fuccess(None)
            else if s.player(idx).confirmedPicks then fuccess(Some(s))
            else
              val confirmed = s.updatePlayer(idx, _.confirmPicks)
              val updated =
                if confirmed.bothPicksConfirmed then confirmed.setPhase(Series.Phase.Banning)
                else confirmed
              repo.update(updated).inject(Some(updated))

  def cancelConfirmPicks(seriesId: SeriesId, userId: UserId): Fu[Option[Series]] =
    repo.byId(seriesId).flatMap:
      case None => fuccess(None)
      case Some(s) =>
        s.playerIndex(userId) match
          case None => fuccess(None)
          case Some(idx) =>
            if s.phase != Series.Phase.Picking then fuccess(None)
            else if !s.player(idx).confirmedPicks then fuccess(Some(s))
            else
              val updated = s.updatePlayer(idx, _.cancelConfirmPicks)
              repo.update(updated).inject(Some(updated))

  // ===== 밴 확정 =====

  def confirmBans(seriesId: SeriesId, userId: UserId): Fu[Option[Series]] =
    repo.byId(seriesId).flatMap:
      case None => fuccess(None)
      case Some(s) =>
        s.playerIndex(userId) match
          case None => fuccess(None)
          case Some(idx) =>
            if s.phase != Series.Phase.Banning then fuccess(None)
            else if s.bans(idx).isEmpty then fuccess(None)
            else if s.player(idx).confirmedBans then fuccess(Some(s))
            else
              val confirmed = s.updatePlayer(idx, _.confirmBans)
              if confirmed.bothBansConfirmed then
                // 중립 오프닝(Standard Game) 추가
                val withNeutral = confirmed.addNeutralOpening
                startGame1(withNeutral)
              else
                repo.update(confirmed).inject(Some(confirmed))

  def cancelConfirmBans(seriesId: SeriesId, userId: UserId): Fu[Option[Series]] =
    repo.byId(seriesId).flatMap:
      case None => fuccess(None)
      case Some(s) =>
        s.playerIndex(userId) match
          case None => fuccess(None)
          case Some(idx) =>
            if s.phase != Series.Phase.Banning then fuccess(None)
            else if !s.player(idx).confirmedBans then fuccess(Some(s))
            else
              val updated = s.updatePlayer(idx, _.cancelConfirmBans)
              repo.update(updated).inject(Some(updated))

  // ===== Game 1 시작 =====

  private def startGame1(s: Series): Fu[Option[Series]] =
    val allBans = s.allBans
    if allBans.isEmpty then fuccess(None)
    else
      val selected = scala.util.Random.shuffle(allBans).head
      val round = 1
      val withOpening = s.markOpeningUsed(selected.id, round, SelectionMethod.SystemRandom)
      val inRandomSelecting = withOpening.setPhase(Series.Phase.RandomSelecting)

      for
        game <- createGame(inRandomSelecting, round, selected)
        withGame = inRandomSelecting.addGame(SeriesGame(
          gameId = game.id,
          round = round,
          openingId = selected.id,
          whitePlayerIndex = inRandomSelecting.whitePlayerIndex(round)
        ))
        _ <- repo.update(withGame)
        _ <- onStart.exec(game.id)
      yield Some(withGame)

  // ===== 게임 종료 처리 =====

  def finishGame(seriesId: SeriesId, gameId: GameId, winnerId: Option[UserId]): Funit =
    repo.byId(seriesId).flatMap:
      case None => funit
      case Some(s) =>
        s.games.find(_.gameId == gameId) match
          case None => funit
          case Some(seriesGame) =>
            val winnerIndex = winnerId.flatMap(s.playerIndex)
            val result = GameResult.fromWinnerIndex(winnerIndex, seriesGame.whitePlayerIndex)
            val updated = s.finishGame(gameId, result)

            if updated.isFinished then
              val finished = updated.setPhase(Series.Phase.Finished)
              repo.update(finished).map(_ => Bus.pub(SeriesFinished(finished)))
            else if result == GameResult.Draw then
              handleDraw(updated, gameId)
            else
              val selecting = updated.setPhase(Series.Phase.Selecting)
              repo.update(selecting).map(_ => Bus.pub(SeriesEnterSelecting(selecting, gameId)))

  // ===== 무승부 처리 =====

  private def handleDraw(s: Series, oldGameId: GameId): Funit =
    val unusedBans = s.unusedBans

    if unusedBans.isEmpty then
      // 밴 오프닝이 모두 소진되면 시리즈 종료 (무승부 처리)
      val finished = s.setPhase(Series.Phase.Finished)
      repo.update(finished).map(_ => Bus.pub(SeriesFinished(finished)))
    else
      val selected = scala.util.Random.shuffle(unusedBans).head
      val round = s.currentRound
      val withOpening = s.markOpeningUsed(selected.id, round, SelectionMethod.SystemRandom)

      for
        game <- createGame(withOpening, round, selected)
        withGame = withOpening
          .addGame(SeriesGame(
            gameId = game.id,
            round = round,
            openingId = selected.id,
            whitePlayerIndex = withOpening.whitePlayerIndex(round)
          ))
          .setPhase(Series.Phase.RandomSelecting)
        _ <- repo.update(withGame)
        _ <- onStart.exec(game.id)
        _ = Bus.pub(SeriesDrawRandomSelecting(withGame, oldGameId))
      yield ()

  // ===== 패자가 오프닝 선택 =====

  def selectNextOpening(seriesId: SeriesId, userId: UserId, preset: OpeningPreset): Fu[Option[Game]] =
    repo.byId(seriesId).flatMap:
      case None => fuccess(None)
      case Some(s) =>
        s.playerIndex(userId) match
          case None => fuccess(None)
          case Some(idx) =>
            if s.phase != Series.Phase.Selecting then fuccess(None)
            else if s.lastGameLoser != Some(idx) then fuccess(None)
            else
              val remaining = s.remainingPicks(idx)
              remaining.find(_.name == preset.name) match
                case None => fuccess(None)
                case Some(opening) =>
                  val round = s.currentRound
                  val withOpening = s.markOpeningUsed(opening.id, round, SelectionMethod.LoserChoice)

                  for
                    game <- createGame(withOpening, round, opening)
                    withGame = withOpening
                      .addGame(SeriesGame(
                        gameId = game.id,
                        round = round,
                        openingId = opening.id,
                        whitePlayerIndex = withOpening.whitePlayerIndex(round)
                      ))
                      .setPhase(Series.Phase.Playing)
                    _ <- repo.update(withGame)
                    _ <- onStart.exec(game.id)
                  yield Some(game)

  // ===== 타임아웃 처리 =====

  def handleSelectingTimeout(seriesId: SeriesId): Fu[Option[Game]] =
    repo.byId(seriesId).flatMap:
      case None => fuccess(None)
      case Some(s) =>
        if s.phase != Series.Phase.Selecting then fuccess(None)
        else
          s.lastGameLoser match
            case None => fuccess(None)
            case Some(loserIdx) =>
              val remaining = s.remainingPicks(loserIdx)
              if remaining.isEmpty then
                val unusedBans = s.unusedBans
                if unusedBans.isEmpty then fuccess(None)
                else selectRandomFromPool(s, unusedBans, SelectionMethod.Timeout)
              else
                selectRandomFromPool(s, remaining, SelectionMethod.Timeout)

  private def selectRandomFromPool(
      s: Series,
      pool: List[SeriesOpening],
      method: SelectionMethod
  ): Fu[Option[Game]] =
    val selected = scala.util.Random.shuffle(pool).head
    val round = s.currentRound
    val withOpening = s.markOpeningUsed(selected.id, round, method)

    for
      game <- createGame(withOpening, round, selected)
      withGame = withOpening
        .addGame(SeriesGame(
          gameId = game.id,
          round = round,
          openingId = selected.id,
          whitePlayerIndex = withOpening.whitePlayerIndex(round)
        ))
        .setPhase(Series.Phase.Playing)
      _ <- repo.update(withGame)
      _ <- onStart.exec(game.id)
    yield Some(game)

  // ===== 레거시 호환 (ChallengeJoiner에서 사용) =====

  def addFirstGame(_seriesId: SeriesId, _gameId: GameId): Funit =
    // 새 플로우에서는 밴픽 후 게임이 생성되므로 여기서는 아무것도 안 함
    funit

  // ===== 게임 조회 =====

  def getGameForCurrentRound(seriesId: SeriesId): Fu[Option[Game]] =
    repo.byId(seriesId).flatMap:
      case None => fuccess(None)
      case Some(s) =>
        s.currentGame match
          case Some(sg) => gameRepo.game(sg.gameId)
          case None => fuccess(None)

  def createNextGame(seriesId: SeriesId): Fu[Option[Game]] =
    repo.byId(seriesId).flatMap:
      case None => fuccess(None)
      case Some(s) => createNextGameInternal(s)

  private def createNextGameInternal(s: Series): Fu[Option[Game]] =
    if s.isFinished || s.currentRound > Series.bestOf then fuccess(None)
    else
      s.currentGame match
        case Some(sg) => gameRepo.game(sg.gameId)
        case None => fuccess(None)

  // ===== 게임 생성 =====

  private def createGame(s: Series, round: Int, opening: SeriesOpening): Fu[Game] =
    val whiteIdx = s.whitePlayerIndex(round)
    val blackIdx = 1 - whiteIdx
    for
      whiteUser <- userApi.byIdWithPerf(s.player(whiteIdx).userId, s.perfType)
      blackUser <- userApi.byIdWithPerf(s.player(blackIdx).userId, s.perfType)
      game <- makeGame(s, whiteUser, blackUser, opening.fen)
      _ <- gameRepo.insertDenormalized(game, Some(opening.fen))
    yield game

  private def makeGame(
      s: Series,
      whiteUser: GameUser,
      blackUser: GameUser,
      initialFen: Fen.Full
  )(using idGenerator: lila.core.game.IdGenerator): Fu[Game] =
    idGenerator.game.dmap: id =>
      val chessGame = Fen.readWithMoveNumber(chess.variant.FromPosition, initialFen) match
        case Some(sit) =>
          chess.Game(
            position = sit.position,
            ply = sit.ply,
            startedAtPly = sit.ply,
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
case class SeriesEnterSelecting(s: Series, oldGameId: GameId)
case class SeriesDrawRandomSelecting(s: Series, oldGameId: GameId)
