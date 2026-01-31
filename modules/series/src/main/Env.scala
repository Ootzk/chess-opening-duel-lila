package lila.series

import com.softwaremill.macwire.*
import play.api.Configuration

import lila.common.Bus
import lila.core.config.*
import lila.db.dsl.Coll

@Module
final class Env(
    db: lila.db.Db,
    gameRepo: lila.core.game.GameRepo,
    newPlayer: lila.core.game.NewPlayer,
    userApi: lila.core.user.UserApi,
    onStart: lila.core.game.OnStart,
    cacheApi: lila.memo.CacheApi,
    lightUserApi: lila.core.user.LightUserApi,
    baseUrl: BaseUrl
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

  // When a series game finishes but series continues, create next game and redirect players
  Bus.sub[SeriesGameFinished]:
    case SeriesGameFinished(s, oldGameId, _) =>
      api.createNextGame(s.id).foreach:
        case Some(newGame) =>
          Bus.pub(lila.game.actorApi.NotifyRematch(oldGameId, newGame))
        case None => ()

  // When series is finished
  Bus.sub[SeriesFinished]:
    case SeriesFinished(s) =>
      // TODO: Could send series result notification here
      ()
