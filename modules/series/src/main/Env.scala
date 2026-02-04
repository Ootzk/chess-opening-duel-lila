package lila.series

import com.softwaremill.macwire.*

import lila.common.Bus
import lila.core.config.*
import lila.db.dsl.Coll

@Module
final class Env(
    db: lila.db.Db,
    gameRepo: lila.core.game.GameRepo,
    userApi: lila.core.user.UserApi,
    onStart: lila.core.game.OnStart,
    lightUserApi: lila.core.user.LightUserApi
)(using
    Executor,
    lila.core.game.IdGenerator
):

  private val seriesColl: Coll = db(CollName("series"))

  lazy val repo: SeriesRepo = wire[SeriesRepo]

  lazy val api: SeriesApi = wire[SeriesApi]

  lazy val jsonView: SeriesJson = wire[SeriesJson]

  // Subscribe to game finish events
  Bus.sub[lila.core.game.FinishGame]:
    case lila.core.game.FinishGame(game, _) =>
      game.metadata.seriesId.foreach: seriesId =>
        api.finishGame(seriesId, game.id, game.winnerUserId)

  // When a series game finishes but series continues (Game 1 only)
  // Game 2+ with winner goes to Selecting, Game 2+ draw goes to Shuffling
  Bus.sub[SeriesGameFinished]:
    case SeriesGameFinished(s, oldGameId, _) =>
      api.createNextGame(s.id).foreach:
        case Some(newGame) =>
          Bus.pub(lila.game.actorApi.NotifyRematch(oldGameId, newGame))
        case None => ()

  // When entering Selecting phase (Game 2+ with winner)
  Bus.sub[SeriesEnterSelecting]:
    case SeriesEnterSelecting(s, oldGameId) =>
      Bus.pub(lila.game.actorApi.NotifySeriesSelecting(s.id, oldGameId))

  // When draw occurs in Game 2+ - redirect to shuffling page
  Bus.sub[SeriesDrawShuffling]:
    case SeriesDrawShuffling(s, oldGameId) =>
      Bus.pub(lila.game.actorApi.NotifySeriesShuffling(s.id, oldGameId))

  // When series is finished
  Bus.sub[SeriesFinished]:
    case SeriesFinished(s) =>
      // TODO: Could send series result notification here
      ()
