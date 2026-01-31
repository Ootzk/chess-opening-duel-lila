package lila.`match`

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

  private val matchColl: Coll = db(CollName("match"))

  lazy val repo: MatchRepo = wire[MatchRepo]

  lazy val api: MatchApi = wire[MatchApi]

  lazy val jsonView: MatchJson = wire[MatchJson]

  // Subscribe to game finish events
  Bus.sub[lila.core.game.FinishGame]:
    case lila.core.game.FinishGame(game, _) =>
      game.metadata.matchId.foreach: matchId =>
        api.finishGame(matchId, game.id, game.winnerColor)

  // When a match game finishes but match continues, create next game and redirect players
  Bus.sub[MatchGameFinished]:
    case MatchGameFinished(m, oldGameId, _) =>
      api.createNextGame(m.id).foreach:
        case Some(newGame) =>
          Bus.pub(lila.game.actorApi.NotifyRematch(oldGameId, newGame))
        case None => ()

  // When match is finished
  Bus.sub[MatchFinished]:
    case MatchFinished(m) =>
      // TODO: Could send match result notification here
      ()