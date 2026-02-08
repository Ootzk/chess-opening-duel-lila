package lila.series

import akka.actor.Scheduler
import scala.concurrent.duration.*
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
    onStart: lila.core.game.OnStart,
    scheduler: Scheduler
)(using Executor, lila.core.game.IdGenerator):

  import lila.core.game.Game

  def create(
      player0: UserId,
      player1: UserId,
      variant: chess.variant.Variant,
      clock: ClockConfig
  ): Fu[Series] =
    val s = Series.make(player0, player1, variant, clock)
    repo.insert(s).map: _ =>
      Bus.pub(SeriesCreated(s))
      s

  def byId(id: SeriesId): Fu[Option[Series]] = repo.byId(id)

  def byGameId(gameId: GameId): Fu[Option[Series]] = repo.byGameId(gameId)

  // ===== Player Online Status =====

  def updateLastSeen(seriesId: SeriesId, userId: UserId): Fu[Option[Series]] =
    repo.byId(seriesId).flatMap:
      case None => fuccess(None)
      case Some(s) =>
        s.playerIndex(userId) match
          case None => fuccess(Some(s))
          case Some(idx) =>
            val updated = s.updatePlayer(idx, _.updateLastSeen)
            repo.update(updated).inject(Some(updated))

  /** WebSocket ping - atomically updates lastSeen for a specific player.
    * Uses $set to avoid read-modify-write race conditions with concurrent operations
    * (setPicks, confirmPicks, etc.) that could overwrite lastSeenAt with stale values.
    */
  def ping(seriesId: SeriesId, playerIndex: Int): Funit =
    repo.setLastSeen(seriesId, playerIndex)

  /** WebSocket gone - player connected/disconnected, notify opponent via socket */
  def setPlayerGone(seriesId: SeriesId, playerIndex: Int, gone: Boolean): Unit =
    socket.foreach(_.notifyGone(seriesId, playerIndex, gone))

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
              // Atomic: only set this player's confirm flag to avoid lost-update race
              repo.setConfirmedPicks(seriesId, idx, true).flatMap: _ =>
                socketNotifyConfirmed(seriesId, idx, "picks")
                // Re-read to check if both players confirmed (after atomic write)
                repo.byId(seriesId).map:
                  case Some(updated) =>
                    if updated.bothPicksConfirmed then
                      scheduler.scheduleOnce(Series.bothConfirmedDelay.seconds):
                        transitionToPhase(seriesId, Series.Phase.Banning)
                    Some(updated)
                  case None => None

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
              // Atomic: only unset this player's confirm flag
              repo.setConfirmedPicks(seriesId, idx, false).flatMap: _ =>
                socketNotifyCancelConfirmed(seriesId, idx, "picks")
                repo.byId(seriesId).map(_.orElse(Some(s)))

  // ===== 픽 타임아웃 =====

  def timeoutPicks(seriesId: SeriesId, userId: UserId, selectedNames: List[String]): Fu[Option[Series]] =
    repo.byId(seriesId).flatMap:
      case None => fuccess(None)
      case Some(s) =>
        s.playerIndex(userId) match
          case None => fuccess(None)
          case Some(idx) =>
            if s.phase != Series.Phase.Picking then fuccess(None)
            else if s.timeLeftInPhase > 0 then fuccess(None) // 아직 타임아웃 아님
            else if s.player(idx).confirmedPicks then fuccess(Some(s)) // 이미 확정됨
            else
              // 현재 선택 + 랜덤 채우기
              val currentPicks = selectedNames.flatMap(name => OpeningPresets.all.find(_.name == name))
              val remaining = OpeningPresets.all.filterNot(p => currentPicks.exists(_.name == p.name))
              val needed = Series.maxPicks - currentPicks.size
              val randomFills = scala.util.Random.shuffle(remaining).take(needed)
              val finalPicks = currentPicks ++ randomFills

              // 기존 픽 제거 후 새 픽 추가
              val withoutOldPicks = s.removeOpeningsByOwnerAndSource(idx, OpeningSource.Pick)
              val newPicks = finalPicks.map(preset => SeriesOpening.makePick(preset, idx))
              val withPicks = withoutOldPicks.addOpenings(newPicks)

              // 확정 처리
              val confirmed = withPicks.updatePlayer(idx, _.confirmPicks)
              val updated =
                if confirmed.bothPicksConfirmed then confirmed.setPhase(Series.Phase.Banning)
                else confirmed
              repo.update(updated).map: _ =>
                socketNotifyConfirmed(seriesId, idx, "picks")
                if updated.phase == Series.Phase.Banning then
                  Bus.pub(SeriesPhaseChanged(updated))
                Some(updated)

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
              // Atomic: only set this player's confirm flag to avoid lost-update race
              repo.setConfirmedBans(seriesId, idx, true).flatMap: _ =>
                socketNotifyConfirmed(seriesId, idx, "bans")
                // Re-read to check if both players confirmed (after atomic write)
                repo.byId(seriesId).map:
                  case Some(updated) =>
                    if updated.bothBansConfirmed then
                      scheduler.scheduleOnce(Series.bothConfirmedDelay.seconds):
                        startGame1Delayed(seriesId)
                    Some(updated)
                  case None => None

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
              // Atomic: only unset this player's confirm flag
              repo.setConfirmedBans(seriesId, idx, false).flatMap: _ =>
                socketNotifyCancelConfirmed(seriesId, idx, "bans")
                repo.byId(seriesId).map(_.orElse(Some(s)))

  // ===== Delayed Phase Transition (after both confirm) =====

  private def transitionToPhase(seriesId: SeriesId, phase: Series.Phase): Unit =
    // Atomic: only transition if phase is still Picking (prevents double transition)
    repo.setPhaseIfCurrent(seriesId, Series.Phase.Picking, phase).foreach:
      case true =>
        repo.byId(seriesId).foreach:
          case Some(s) => Bus.pub(SeriesPhaseChanged(s))
          case None    => ()
      case false => () // Already transitioned by the other player's confirm

  private def startGame1Delayed(seriesId: SeriesId): Unit =
    // Atomic: claim phase transition from Banning to prevent double game creation
    repo.setPhaseIfCurrent(seriesId, Series.Phase.Banning, Series.Phase.RandomSelecting).foreach:
      case true =>
        repo.byId(seriesId).foreach:
          case Some(s) if s.bothBansConfirmed =>
            val withNeutral = s.addNeutralOpening
            startGame1(withNeutral)
          case _ => ()
      case false => () // Already transitioned

  // ===== 밴 타임아웃 =====

  def timeoutBans(seriesId: SeriesId, userId: UserId, selectedNames: List[String]): Fu[Option[Series]] =
    repo.byId(seriesId).flatMap:
      case None => fuccess(None)
      case Some(s) =>
        s.playerIndex(userId) match
          case None => fuccess(None)
          case Some(idx) =>
            if s.phase != Series.Phase.Banning then fuccess(None)
            else if s.timeLeftInPhase > 0 then fuccess(None) // 아직 타임아웃 아님
            else if s.player(idx).confirmedBans then fuccess(Some(s)) // 이미 확정됨
            else
              // 상대 픽에서 선택 가능한 밴 후보
              val opponentPicks = s.picks(1 - idx).map(_.name).toSet
              val currentBans = selectedNames.filter(opponentPicks.contains)
                .flatMap(name => OpeningPresets.all.find(_.name == name))

              // 남은 상대 픽 중에서 랜덤 채우기
              val remaining = s.picks(1 - idx).filterNot(p => currentBans.exists(_.name == p.name))
              val needed = Series.maxBans - currentBans.size
              val randomFills = scala.util.Random.shuffle(remaining.toList).take(needed).map(_.toPreset)
              val finalBans = currentBans ++ randomFills

              // 기존 밴 제거 후 새 밴 추가
              val withoutOldBans = s.removeOpeningsByOwnerAndSource(idx, OpeningSource.Ban)
              val newBans = finalBans.map(preset => SeriesOpening.makeBan(preset, idx))
              val withBans = withoutOldBans.addOpenings(newBans)

              // 확정 처리
              val confirmed = withBans.updatePlayer(idx, _.confirmBans)
              if confirmed.bothBansConfirmed then
                val withNeutral = confirmed.addNeutralOpening
                startGame1(withNeutral)
              else
                repo.update(confirmed).map: _ =>
                  socketNotifyConfirmed(seriesId, idx, "bans")
                  Some(confirmed)

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
      yield
        Bus.pub(SeriesPhaseChanged(withGame))
        Some(withGame)

  // ===== 게임 종료 처리 =====

  def finishGame(seriesId: SeriesId, gameId: GameId, winnerId: Option[UserId]): Funit =
    repo.byId(seriesId).flatMap:
      case None => funit
      case Some(s) =>
        if s.isFinished || s.isAborted then funit // forfeit로 이미 종료됨
        else s.games.find(_.gameId == gameId) match
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
      val finished = s.copy(
        phase = Series.Phase.Finished,
        status = Series.Status.Finished,
        finishedAt = Some(nowInstant)
      )
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

  // ===== Selecting Phase: 패자가 오프닝 선택 =====

  /** 실시간 선택 동기화 — DB에 저장하지 않고 WS로만 브로드캐스트 */
  def setSelectingPick(seriesId: SeriesId, userId: UserId, presetName: Option[String]): Fu[Option[Series]] =
    repo.byId(seriesId).flatMap:
      case None => fuccess(None)
      case Some(s) =>
        s.playerIndex(userId) match
          case None => fuccess(None)
          case Some(idx) =>
            if s.phase != Series.Phase.Selecting then fuccess(None)
            else if s.lastGameLoser != Some(idx) then fuccess(None)
            else
              socket.foreach(_.notifySelectingPick(seriesId, presetName))
              fuccess(Some(s))

  /** Confirm selecting pick — 3초 delay 후 게임 생성 */
  def confirmSelectingPick(seriesId: SeriesId, userId: UserId, presetName: String): Fu[Option[Series]] =
    repo.byId(seriesId).flatMap:
      case None => fuccess(None)
      case Some(s) =>
        s.playerIndex(userId) match
          case None => fuccess(None)
          case Some(idx) =>
            if s.phase != Series.Phase.Selecting then fuccess(None)
            else if s.lastGameLoser != Some(idx) then fuccess(None)
            else if s.player(idx).confirmedSelecting then fuccess(Some(s))
            else
              val remaining = s.remainingPicks(idx)
              remaining.find(_.name == presetName) match
                case None => fuccess(None)
                case Some(_) =>
                  repo.setConfirmedSelecting(seriesId, idx, true, Some(presetName)).flatMap: _ =>
                    socketNotifyConfirmed(seriesId, idx, "selecting")
                    scheduler.scheduleOnce(Series.bothConfirmedDelay.seconds):
                      startSelectingDelayed(seriesId)
                    repo.byId(seriesId).map(_.orElse(Some(s)))

  /** Cancel selecting confirm — 3초 이내 취소 */
  def cancelConfirmSelecting(seriesId: SeriesId, userId: UserId): Fu[Option[Series]] =
    repo.byId(seriesId).flatMap:
      case None => fuccess(None)
      case Some(s) =>
        s.playerIndex(userId) match
          case None => fuccess(None)
          case Some(idx) =>
            if s.phase != Series.Phase.Selecting then fuccess(None)
            else if !s.player(idx).confirmedSelecting then fuccess(Some(s))
            else
              repo.setConfirmedSelecting(seriesId, idx, false, None).flatMap: _ =>
                socketNotifyCancelConfirmed(seriesId, idx, "selecting")
                repo.byId(seriesId).map(_.orElse(Some(s)))

  /** 3초 delay 후 실행 — confirmedSelecting이 여전히 true면 게임 생성 */
  private def startSelectingDelayed(seriesId: SeriesId): Unit =
    repo.byId(seriesId).foreach:
      case Some(s) if s.phase == Series.Phase.Selecting =>
        s.lastGameLoser match
          case Some(loserIdx) if s.player(loserIdx).confirmedSelecting =>
            s.player(loserIdx).selectingPick match
              case Some(pickName) =>
                val remaining = s.remainingPicks(loserIdx)
                remaining.find(_.name == pickName).foreach: opening =>
                  val round = s.currentRound
                  val withOpening = s.markOpeningUsed(opening.id, round, SelectionMethod.LoserChoice)
                  val clearSelecting = withOpening.updatePlayer(loserIdx, _.clearSelecting)

                  for
                    game <- createGame(clearSelecting, round, opening)
                    withGame = clearSelecting
                      .addGame(SeriesGame(
                        gameId = game.id,
                        round = round,
                        openingId = opening.id,
                        whitePlayerIndex = clearSelecting.whitePlayerIndex(round)
                      ))
                      .setPhase(Series.Phase.Playing)
                    _ <- repo.update(withGame)
                    _ <- onStart.exec(game.id)
                    _ = Bus.pub(SeriesPhaseChanged(withGame))
                  yield ()
              case None => () // no pick name — should not happen
          case _ => () // cancelled or phase changed
      case _ => ()

  /** Legacy: immediate select (for backward compatibility with selectNextOpening endpoint) */
  def selectNextOpening(seriesId: SeriesId, userId: UserId, preset: OpeningPreset): Fu[Option[lila.core.game.Game]] =
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
                  val clearSelecting = withOpening.updatePlayer(idx, _.clearSelecting)

                  for
                    game <- createGame(clearSelecting, round, opening)
                    withGame = clearSelecting
                      .addGame(SeriesGame(
                        gameId = game.id,
                        round = round,
                        openingId = opening.id,
                        whitePlayerIndex = clearSelecting.whitePlayerIndex(round)
                      ))
                      .setPhase(Series.Phase.Playing)
                    _ <- repo.update(withGame)
                    _ <- onStart.exec(game.id)
                    _ = Bus.pub(SeriesPhaseChanged(withGame))
                  yield Some(game)

  // ===== Server-side Phase Timeout =====

  def serverTimeoutPhase(seriesId: SeriesId): Fu[Option[Series]] =
    repo.byId(seriesId).flatMap:
      case None => fuccess(None)
      case Some(s) =>
        s.phase match
          case Series.Phase.Picking   => serverTimeoutPicking(s)
          case Series.Phase.Banning   => serverTimeoutBanning(s)
          case Series.Phase.Selecting => serverTimeoutSelecting(s)
          case _                      => fuccess(Some(s))

  private def serverTimeoutPicking(s: Series): Fu[Option[Series]] =
    // 양측 모두 확정됐으면 3초 스케줄이 처리하므로 여기서는 아무것도 안 함
    if s.bothPicksConfirmed then fuccess(Some(s))
    else
      // 미확정 플레이어들 자동 처리
      val unconfirmedPlayers = List(0, 1).filter(idx => !s.player(idx).confirmedPicks)

      // 미확정 플레이어 중 disconnected인 플레이어가 있으면 시리즈 abort
      val disconnectedPlayer = unconfirmedPlayers.find(idx => s.player(idx).isDisconnected)
      disconnectedPlayer match
        case Some(_) => abortSeries(s)
        case None =>
          unconfirmedPlayers
            .foldLeft(fuccess(s)): (fuSeries, idx) =>
              fuSeries.map: currentS =>
                // 현재 픽 + 랜덤 채우기
                val currentPicks = currentS.picks(idx)
                val remaining    = OpeningPresets.all.filterNot(p => currentPicks.exists(_.name == p.name))
                val needed       = Series.maxPicks - currentPicks.size
                val randomFills  = scala.util.Random.shuffle(remaining).take(needed)
                val finalPicks   = currentPicks.map(_.toPreset) ++ randomFills

                val withoutOld = currentS.removeOpeningsByOwnerAndSource(idx, OpeningSource.Pick)
                val newPicks   = finalPicks.map(preset => SeriesOpening.makePick(preset, idx))
                val withPicks  = withoutOld.addOpenings(newPicks)
                val confirmed  = withPicks.updatePlayer(idx, _.confirmPicks)
                confirmed
            .flatMap: updated =>
              val final_ =
                if updated.bothPicksConfirmed then updated.setPhase(Series.Phase.Banning)
                else updated
              repo.update(final_).map: _ =>
                if final_.phase == Series.Phase.Banning then
                  Bus.pub(SeriesPhaseChanged(final_))
                Some(final_)

  private def serverTimeoutBanning(s: Series): Fu[Option[Series]] =
    // 양측 모두 확정됐으면 3초 스케줄이 처리하므로 여기서는 아무것도 안 함
    if s.bothBansConfirmed then fuccess(Some(s))
    else
      // 미확정 플레이어들 자동 처리
      val unconfirmedPlayers = List(0, 1).filter(idx => !s.player(idx).confirmedBans)

      // 미확정 플레이어 중 disconnected인 플레이어가 있으면 시리즈 abort
      val disconnectedPlayer = unconfirmedPlayers.find(idx => s.player(idx).isDisconnected)
      disconnectedPlayer match
        case Some(_) => abortSeries(s)
        case None =>
          unconfirmedPlayers
            .foldLeft(fuccess(s)): (fuSeries, idx) =>
              fuSeries.map: currentS =>
                val opponentPicks = currentS.picks(1 - idx)
                val currentBans   = currentS.bans(idx)
                val remaining     = opponentPicks.filterNot(p => currentBans.exists(_.name == p.name))
                val needed        = Series.maxBans - currentBans.size
                val randomFills = scala.util.Random.shuffle(remaining.toList).take(needed).map(_.toPreset)
                val finalBans   = currentBans.map(_.toPreset) ++ randomFills

                val withoutOld = currentS.removeOpeningsByOwnerAndSource(idx, OpeningSource.Ban)
                val newBans    = finalBans.map(preset => SeriesOpening.makeBan(preset, idx))
                val withBans   = withoutOld.addOpenings(newBans)
                val confirmed  = withBans.updatePlayer(idx, _.confirmBans)
                confirmed
            .flatMap: updated =>
              if updated.bothBansConfirmed then
                val withNeutral = updated.addNeutralOpening
                startGame1(withNeutral)
              else repo.update(updated).inject(Some(updated))

  def forfeitSeries(seriesId: SeriesId, userId: UserId): Fu[Option[Series]] =
    repo.byId(seriesId).flatMap:
      case None => fuccess(None)
      case Some(s) =>
        if s.isFinished || s.isAborted then fuccess(None)
        else
          s.playerIndex(userId) match
            case None => fuccess(None)
            case Some(loserIdx) =>
              val forfeited = s.copy(
                forfeitBy = Some(loserIdx),
                phase = Series.Phase.Finished,
                status = Series.Status.Finished,
                finishedAt = Some(nowInstant)
              )
              repo.update(forfeited).map: _ =>
                Bus.pub(SeriesForfeited(forfeited))
                Some(forfeited)

  private def abortSeries(s: Series): Fu[Option[Series]] =
    val aborted = s.copy(
      status = Series.Status.Aborted,
      phase = Series.Phase.Finished,
      finishedAt = Some(nowInstant)
    )
    repo.update(aborted).map: _ =>
      Bus.pub(SeriesAborted(aborted))
      Some(aborted)

  /** Forfeit by player index (for server-side disconnect timeout) */
  private def forfeitByIndex(s: Series, loserIdx: Int): Fu[Option[Series]] =
    val forfeited = s.copy(
      forfeitBy = Some(loserIdx),
      phase = Series.Phase.Finished,
      status = Series.Status.Finished,
      finishedAt = Some(nowInstant)
    )
    repo.update(forfeited).map: _ =>
      Bus.pub(SeriesForfeited(forfeited))
      Some(forfeited)

  private def serverTimeoutSelecting(s: Series): Fu[Option[Series]] =
    s.lastGameLoser match
      case None => fuccess(Some(s))
      case Some(loserIdx) =>
        // 이미 confirmedSelecting이면 3초 스케줄이 처리 중
        if s.player(loserIdx).confirmedSelecting then fuccess(Some(s))
        else
          val p0dc = s.player(0).isDisconnected
          val p1dc = s.player(1).isDisconnected
          if p0dc && p1dc then abortSeries(s)           // 양측 DC → Abort
          else if p0dc || p1dc then                       // 한쪽 DC → DC한 쪽 패배
            val dcIdx = if p0dc then 0 else 1
            forfeitByIndex(s, dcIdx)
          else                                            // 양측 online → 랜덤 선택
            handleSelectingTimeout(s.id).map(_ => Some(s))

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
      _ = Bus.pub(SeriesPhaseChanged(withGame))
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

  // Work around circular dependency
  private var socket: Option[SeriesSocket] = None
  private[series] def registerSocket(s: SeriesSocket) = socket = s.some

  private def socketReload(seriesId: SeriesId): Unit =
    socket.foreach(_.reload(seriesId))

  private def socketNotifyConfirmed(seriesId: SeriesId, playerIndex: Int, phase: String): Unit =
    socket.foreach(_.notifyConfirmed(seriesId, playerIndex, phase))

  private def socketNotifyCancelConfirmed(seriesId: SeriesId, playerIndex: Int, phase: String): Unit =
    socket.foreach(_.notifyConfirmed(seriesId, playerIndex, phase, confirmed = false))

  /** Offer or accept a rematch. Returns Some(newSeriesId) if accepted, None if just offered. */
  def offerOrAcceptRematch(seriesId: SeriesId, userId: UserId): Fu[Option[SeriesId]] =
    repo.byId(seriesId).flatMap:
      case None => fuccess(None)
      case Some(s) =>
        s.playerIndex(userId) match
          case None => fuccess(None)
          case Some(idx) =>
            if !s.isFinished then fuccess(None)
            else if s.rematchOfferedBy.exists(_ != idx) then
              // Opponent already offered -> accept: create new series with random player order
              val (p0, p1) =
                if scala.util.Random.nextBoolean() then (s.player(0).userId, s.player(1).userId)
                else (s.player(1).userId, s.player(0).userId)
              create(p0, p1, s.variant, s.clock).map: newSeries =>
                socket.foreach(_.notifyRematchTaken(seriesId, newSeries.id))
                Some(newSeries.id)
            else if s.rematchOfferedBy.contains(idx) then
              fuccess(None) // Already offering
            else
              // First offer
              val updated = s.offerRematch(idx)
              repo.update(updated).map: _ =>
                socket.foreach(_.notifyRematchOffer(seriesId, idx))
                None

// Events
case class SeriesCreated(s: Series)
case class SeriesAborted(s: Series)
case class SeriesForfeited(s: Series)
case class SeriesFinished(s: Series)
case class SeriesGameFinished(s: Series, gameId: GameId, winnerId: Option[UserId])
case class SeriesEnterSelecting(s: Series, oldGameId: GameId)
case class SeriesDrawRandomSelecting(s: Series, oldGameId: GameId)
case class SeriesPhaseChanged(s: Series)
