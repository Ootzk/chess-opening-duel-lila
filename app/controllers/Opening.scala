package controllers

import play.api.libs.json.Json
import play.api.mvc.*

import lila.app.{ *, given }
import lila.common.HTTPRequest
import lila.core.net.Crawler
import lila.core.security.IsProxy
import lila.opening.OpeningQuery.queryFromUrl
import lila.security.UserAgentParser

final class Opening(env: Env) extends LilaController(env):

  private type PoolData = List[(String, String, String, String)]

  private def loadPoolData(using ctx: Context): Fu[PoolData] =
    ctx.me.fold(fuccess(Nil)): me =>
      env.series.poolApi
        .getPresetsWithIdsForUser(me.userId)
        .map(_.map((id, p) => (id.value, p.name, p.url, p.ownerColor.name)))

  def index(q: Option[String] = None) = Open:
    val searchQuery = ~q
    if searchQuery.nonEmpty then
      val results = env.opening.search(searchQuery)
      if HTTPRequest.isXhr(ctx.req)
      then Ok.snip(views.opening.ui.resultsList(results))
      else Ok.page(views.opening.ui.resultsPage(searchQuery, results, env.opening.api.readConfig))
    else
      for
        page     <- env.opening.api.index
        poolData <- loadPoolData
        res <- page.fold(notFound): p =>
          isGrantedOpt(_.OpeningWiki)
            .so(env.opening.wiki.popularOpeningsWithShortWiki)
            .flatMap: wikiMissing =>
              Ok.page(views.opening.ui.index(p, wikiMissing, poolData))
      yield res

  def pool = Auth { ctx ?=> me ?=>
    for
      page     <- env.opening.api.index
      poolData <- loadPoolData
      res <- page.fold(notFound)(p => Ok.page(views.opening.ui.pool(p, poolData)))
    yield res
  }

  def removeFromPool(id: String, color: String) = AuthBody { ctx ?=> me ?=>
    val chessColor = chess.Color.fromName(color).getOrElse(chess.White)
    env.series.poolApi.removeFromPool(me.userId, lila.series.PoolOpeningId(id), chessColor).flatMap:
      case false => BadRequest(jsonError("Cannot remove: minimum 5 openings"))
      case true =>
        loadPoolData.map: pd =>
          Ok.snip(views.opening.ui.poolTableSnippet(pd))
  }

  def addToPool = AuthBody(parse.json) { ctx ?=> me ?=>
    import play.api.libs.json.*
    val body = ctx.body.body
    (for
      name  <- (body \ "name").asOpt[String]
      fen   <- (body \ "fen").asOpt[String]
      url   <- (body \ "url").asOpt[String]
      color <- (body \ "color").asOpt[String].flatMap(chess.Color.fromName)
    yield (name, fen, url, color)) match
      case None => fuccess(BadRequest(jsonError("Invalid request body")))
      case Some((name, fen, url, color)) =>
        val fenFull = chess.format.Fen.Full(fen)
        env.opening.api.isWinRateBalanced(fenFull).flatMap:
          case false => fuccess(BadRequest(jsonError("Win rate too imbalanced")))
          case true =>
            env.series.poolApi.addOpeningToPool(me.userId, name, fenFull, url, color).flatMap:
              case false => fuccess(BadRequest(jsonError("Cannot add: pool full or duplicate")))
              case true =>
                loadPoolData.map: pd =>
                  Ok.snip(views.opening.ui.poolTableSnippet(pd))
  }

  private val ipRateLimit =
    env.security.ipTrust.rateLimit(50, 10.minutes, "opening.byKeyAndMoves", _.proxyMultiplier(3))

  def byKeyAndMoves(key: String, moves: String) = Open:
    Firewall:
      WithProxy: proxy ?=>
        val crawler = HTTPRequest.isCrawler(req)
        if moves.sizeIs > 10 && crawler.yes then Forbidden
        else if moves.sizeIs > 6 && proxy.isFloodish && ctx.isAnon then Forbidden
        else
          limit.enumeration.opening(rateLimited):
            val suspUA = UserAgentParser.trust.isSuspicious(req.userAgent)
            val cost = if ctx.isAuth then 1 else if suspUA then 5 else 2
            ipRateLimit(rateLimited, cost = cost):
              env.opening.api
                .lookup(queryFromUrl(key, moves.some), isGrantedOpt(_.OpeningWiki), crawler, proxy)
                .flatMap:
                  case None => Redirect(routes.Opening.index(key.some))
                  case Some(page) =>
                    val query = page.query.query
                    if query.key.isEmpty then Redirect(routes.Opening.index(key.some))
                    else if query.key != key then Redirect(routes.Opening.byKeyAndMoves(query.key, moves))
                    else if moves.nonEmpty && page.query.pgnUnderscored != moves && !getBool("r") then
                      Redirect:
                        s"${routes.Opening.byKeyAndMoves(query.key, page.query.pgnUnderscored)}?r=1"
                    else
                      Ok.async:
                        for
                          puzzle   <- page.query.exactOpening.so(env.puzzle.opening.getClosestTo(_))
                          poolData <- loadPoolData
                        yield
                          val puzzleKey = puzzle.map(_.fold(_.family.key.value, _.opening.key.value))
                          views.opening.ui.show(page, puzzleKey, poolData)

  def config(thenTo: String) = OpenBody:
    NoCrawlers:
      val redir = Redirect:
        lila.common.HTTPRequest.referer(ctx.req) | {
          if thenTo.isEmpty || thenTo == "index" then routes.Opening.index().url
          else if thenTo.startsWith("q:") then routes.Opening.index(thenTo.drop(2).some).url
          else routes.Opening.byKeyAndMoves(thenTo, "").url
        }
      bindForm(lila.opening.OpeningConfig.form)(
        _ => redir,
        cfg => redir.withCookies(env.opening.config.write(cfg))
      )

  def wikiWrite(key: String, moves: String) = SecureBody(_.OpeningWiki) { ctx ?=> me ?=>
    env.opening.api
      .lookup(queryFromUrl(key, moves.some), isGranted(_.OpeningWiki), Crawler.No, IsProxy.empty)
      .map(_.flatMap(_.query.exactOpening))
      .orNotFound: op =>
        val redirect = Redirect(routes.Opening.byKeyAndMoves(key, moves))
        bindForm(lila.opening.OpeningWiki.form)(
          _ => redirect,
          text =>
            for _ <- env.opening.wiki.write(op, text, me.userId)
            yield
              env.irc.api.openingEdit(me.light, key, moves)
              redirect
        )
  }

  def tree = Open:
    loadPoolData.flatMap: poolData =>
      Ok.page(views.opening.ui.tree(lila.opening.OpeningTree.compute, env.opening.api.readConfig, poolData))

  // 검증용 API: 로그인 유저의 opening pool
  def myPool = Auth { ctx ?=> me ?=>
    env.series.poolApi.getPresetsForUser(me.userId).map: pool =>
      JsonOk(Json.obj("pool" -> pool.map(presetToJson)))
  }

  // 검증용 API: 기본 오프닝 프리셋 목록
  def masterOpenings = Open:
    val openings = lila.series.OpeningPresets.all
    JsonOk(Json.obj("openings" -> openings.map(o => Json.obj(
      "id"   -> lila.series.PoolEntry.nameToSlug(o.name),
      "name" -> o.name,
      "fen"  -> o.fen.value,
      "url"  -> o.url
    ))))

  private def presetToJson(p: lila.series.OpeningPreset) = Json.obj(
    "name"       -> p.name,
    "fen"        -> p.fen.value,
    "url"        -> p.url,
    "ownerColor" -> p.ownerColor.name
  )
