package controllers

import chess.Color
import play.api.libs.json.Json
import play.api.mvc.Result

import lila.app.{ *, given }
import lila.core.id.SeriesId

final class Series(env: Env) extends LilaController(env):
  def api = env.series.api

  def show(id: SeriesId) = Open:
    Found(api.byId(id)): s =>
      for
        json <- env.series.jsonView(s, ctx.me.map(_.userId))
      yield JsonOk(json)

  def apiShow(id: SeriesId) = AnonOrScoped() { ctx ?=>
    Found(api.byId(id)): s =>
      for
        json <- env.series.jsonView(s, ctx.me.map(_.userId))
      yield JsonOk(json)
  }

  def nextGame(id: SeriesId) = Auth { ctx ?=> me ?=>
    Found(api.byId(id)): s =>
      if s.isFinished then Redirect(routes.Series.show(id))
      else if !s.players.contains(me.userId) then Forbidden("Not a player of this series")
      else
        api.createNextGame(id).map:
          case Some(game) => Redirect(routes.Round.watcher(game.id, Color.white))
          case None       => Redirect(routes.Series.show(id))
  }
