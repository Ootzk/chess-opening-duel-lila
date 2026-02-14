package lila.series

import akka.actor.Scheduler
import com.softwaremill.macwire.*

import lila.common.Bus
import lila.core.config.*
import lila.core.socket.{ GetVersion, SocketKit, SocketVersion }
import lila.core.id.SeriesId
import lila.db.dsl.Coll

@Module
final class Env(
    db: lila.db.Db,
    gameRepo: lila.core.game.GameRepo,
    userApi: lila.core.user.UserApi,
    onStart: lila.core.game.OnStart,
    lightUserApi: lila.core.user.LightUserApi,
    socketKit: SocketKit
)(using scheduler: Scheduler)(using
    Executor,
    lila.core.game.IdGenerator
):

  private val seriesColl: Coll      = db(CollName("series"))
  private val openingsColl: Coll    = db(CollName("openings"))
  private val openingPoolColl: Coll = db(CollName("opening_pool"))

  // Make scheduler available for wire macro
  private val apiScheduler: Scheduler = scheduler

  // 명시적 생성 (macwire Coll 다중 인스턴스 disambiguation 방지)
  lazy val repo: SeriesRepo = SeriesRepo(seriesColl)

  lazy val poolRepo: OpeningPoolRepo = OpeningPoolRepo(openingsColl, openingPoolColl)

  lazy val poolApi: OpeningPoolApi = OpeningPoolApi(poolRepo)

  lazy val api: SeriesApi = SeriesApi(repo, gameRepo, userApi, onStart, poolApi, apiScheduler)

  // 앱 시작 시 마스터 오프닝 시드 (idempotent)
  poolApi.seedMasterOpenings()

  lazy val jsonView: SeriesJson = wire[SeriesJson]

  lazy val socket: SeriesSocket = wire[SeriesSocket]

  def version(seriesId: SeriesId): Fu[SocketVersion] =
    socket.rooms.ask[SocketVersion](seriesId.into(RoomId))(GetVersion.apply)

  // Server-side timeout scheduler (lazy to avoid circular dependency)
  lazy val timeouts: SeriesTimeouts = SeriesTimeouts(scheduler, () => api)

  // Subscribe to series creation to schedule initial timeout
  Bus.sub[SeriesCreated]:
    case SeriesCreated(s) =>
      timeouts.schedule(s.id)

  // Subscribe to phase changes to reschedule/cancel timeouts and notify clients
  Bus.sub[SeriesPhaseChanged]:
    case SeriesPhaseChanged(s) =>
      socket.notifyPhase(s.id, s.phase.id, s.currentGameId)
      s.phase match
        case Series.Phase.Banning =>
          timeouts.schedule(s.id) // Reschedule for Banning phase
        case Series.Phase.Selecting =>
          timeouts.schedule(s.id) // Schedule timeout for Selecting phase (DC → forfeit)
        case Series.Phase.Resting =>
          timeouts.schedule(s.id) // Schedule timeout for Resting phase (auto-transition)
        case Series.Phase.RandomSelecting | Series.Phase.Playing | Series.Phase.Finished =>
          timeouts.cancel(s.id) // Cancel timeout when game starts
        case _ => ()

  // Subscribe to series aborted
  Bus.sub[SeriesAborted]:
    case SeriesAborted(s) =>
      socket.notifyAborted(s.id)

  // Subscribe to series forfeited - resign current game + redirect to finished page
  Bus.sub[SeriesForfeited]:
    case SeriesForfeited(s) =>
      socket.reload(s.id)
      s.currentGame.foreach: sg =>
        s.forfeitBy.foreach: loserIdx =>
          val loserColor =
            if sg.whitePlayerIndex == loserIdx then chess.Color.White
            else chess.Color.Black
          Bus.pub(lila.game.actorApi.NotifySeriesForfeited(sg.gameId, loserColor))
        Bus.pub(lila.game.actorApi.NotifySeriesFinished(s.id, sg.gameId))

  // Subscribe to game finish events
  Bus.sub[lila.core.game.FinishGame]:
    case lila.core.game.FinishGame(game, _) =>
      game.metadata.seriesId.foreach: seriesId =>
        // rageQuit (disconnect) = Status.Timeout with a winner
        // normal clock expiry = Status.Outoftime (not forfeit)
        val isRageQuit = game.status == chess.Status.Timeout && game.winner.isDefined
        // Game aborted in series = player didn't make their move (disconnected)
        // turnColor = the side that was supposed to move but didn't → they forfeit
        val isAbortForfeit = game.status == chess.Status.Aborted
        val isDisconnectForfeit = isRageQuit || isAbortForfeit
        val effectiveWinnerId =
          if isAbortForfeit then game.player(!game.turnColor).userId
          else game.winnerUserId
        api.finishGame(seriesId, game.id, effectiveWinnerId, isDisconnectForfeit)

  // When a series game finishes but series continues (Game 1 only)
  // Game 2+ with winner goes to Selecting, Game 2+ draw goes to RandomSelecting
  Bus.sub[SeriesGameFinished]:
    case SeriesGameFinished(s, oldGameId, _) =>
      api.createNextGame(s.id).foreach:
        case Some(newGame) =>
          Bus.pub(lila.game.actorApi.NotifyRematch(oldGameId, newGame))
        case None => ()

  // When entering Resting phase (between games) - notify round socket clients
  Bus.sub[SeriesEnterResting]:
    case SeriesEnterResting(s, oldGameId) =>
      val isLastGame = s.isFinished || s.unusedRemainingPicks.isEmpty
      Bus.pub(lila.game.actorApi.NotifySeriesResting(s.id, oldGameId, s.timeLeftInPhase, isLastGame))

  // When entering Selecting phase (Game 2+ with winner)
  Bus.sub[SeriesEnterSelecting]:
    case SeriesEnterSelecting(s, oldGameId) =>
      Bus.pub(lila.game.actorApi.NotifySeriesSelecting(s.id, oldGameId))

  // When draw occurs in Game 2+ - redirect to random selecting page
  Bus.sub[SeriesDrawRandomSelecting]:
    case SeriesDrawRandomSelecting(s, oldGameId) =>
      Bus.pub(lila.game.actorApi.NotifySeriesRandomSelecting(s.id, oldGameId))

  // When series is finished - redirect both players to finished page
  Bus.sub[SeriesFinished]:
    case SeriesFinished(s) =>
      s.lastFinishedGame.foreach: lastGame =>
        Bus.pub(lila.game.actorApi.NotifySeriesFinished(s.id, lastGame.gameId))
