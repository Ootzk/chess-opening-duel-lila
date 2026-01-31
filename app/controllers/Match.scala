package controllers

import play.api.libs.json.Json
import play.api.mvc.Result

import lila.app.{ *, given }
import lila.core.id.MatchId

final class Match(env: Env) extends LilaController(env):
  def api = env.`match`.api

  def show(id: MatchId) = Open:
    Found(api.byId(id)): m =>
      for
        json <- env.`match`.jsonView(m, ctx.me.map(_.userId))
      yield negotiate(
        html = Ok.page(views.html.base.embed(s"Match ${m.id}", views.html.base.topnav())),
        json = JsonOk(json)
      )

  def apiShow(id: MatchId) = AnonOrScoped() { ctx ?=>
    Found(api.byId(id)): m =>
      for
        json <- env.`match`.jsonView(m, ctx.me.map(_.userId))
      yield JsonOk(json)
  }

  def nextGame(id: MatchId) = Auth { ctx ?=> me ?=>
    Found(api.byId(id)): m =>
      if m.isFinished then Redirect(routes.Match.show(id))
      else if !m.players.contains(me.userId) then Forbidden("Not a player of this match")
      else
        api.createNextGame(id).map:
          case Some(gameId) => Redirect(routes.Round.watcher(gameId, "white"))
          case None         => Redirect(routes.Match.show(id))
  }