package lila.opening

import com.softwaremill.macwire.*
import com.softwaremill.tagging.*
import play.api.Configuration
import play.api.libs.ws.StandaloneWSClient

import lila.core.config.CollName
import lila.memo.CacheApi

@Module
final class Env(
    db: lila.db.Db,
    gameRepo: lila.core.game.GameRepo,
    pgnDump: lila.core.game.PgnDump,
    cacheApi: CacheApi,
    appConfig: Configuration,
    cookieBaker: lila.core.security.LilaCookie,
    ws: StandaloneWSClient
)(using scheduler: Scheduler, ec: Executor):

  private val explorerEndpoint = appConfig.get[String]("explorer.endpoint").taggedWith[ExplorerEndpoint]
  private val explorerToken =
    appConfig.getOptional[String]("explorer.token").filter(_.nonEmpty).map(_.taggedWith[ExplorerToken])
  private lazy val wikiColl          = db(CollName("opening_wiki"))
  private lazy val explorerCacheColl = db(CollName("opening_explorer_cache"))

  private lazy val explorer = wire[OpeningExplorer]

  lazy val explorerCache = OpeningExplorerCache(explorerCacheColl, cacheApi)

  lazy val config = wire[OpeningConfigStore]

  lazy val wiki = OpeningWikiApi(wikiColl, explorer, cacheApi)

  lazy val api = wire[OpeningApi]

  lazy val search = wire[OpeningSearch]

  scheduler.scheduleWithFixedDelay(5.minutes, 6.hours): () =>
    explorerCache.refreshStale(explorer)

  scheduler.scheduleOnce(30.seconds):
    explorerCache.isEmpty.foreach:
      if _ then explorerCache.seedPresets(explorer)

trait ExplorerEndpoint
trait ExplorerToken
