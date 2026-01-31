package controllers

import chess.Color
import play.api.libs.json.{ Json, JsArray }

import lila.app.*
import lila.core.id.SeriesId
import lila.series.OpeningPresets

final class Series(env: Env) extends LilaController(env):
  def api = env.series.api

  def show(id: SeriesId) = Open:
    Found(api.byId(id)): s =>
      for
        json <- env.series.jsonView(s, ctx.me.map(_.userId))
      yield JsonOk(json)

  // 밴픽 페이지 (HTML)
  def pickPage(id: SeriesId) = Auth { ctx ?=> me ?=>
    Found(api.byId(id)): s =>
      if !s.players.contains(me.userId) then Forbidden("Not a player of this series")
      else if s.phase == lila.series.Series.Phase.Game1Shuffling then
        Redirect(routes.Series.shufflingPage(id))
      else
        for
          json <- env.series.jsonView(s, Some(me.userId))
          page <- Ok.page(views.series.pick(s, json, OpeningPresets.all))
        yield page
  }

  // Game1 셔플링 페이지 (HTML)
  def shufflingPage(id: SeriesId) = Auth { ctx ?=> me ?=>
    Found(api.byId(id)): s =>
      if !s.players.contains(me.userId) then Forbidden("Not a player of this series")
      else if s.phase != lila.series.Series.Phase.Game1Shuffling then
        Redirect(routes.Series.pickPage(id))
      else
        for
          json <- env.series.jsonView(s, Some(me.userId))
          page <- Ok.page(views.series.shuffling(s, json))
        yield page
  }

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

  // 오프닝 프리셋 목록 조회
  def presets = Action:
    JsonOk(Json.obj(
      "presets" -> OpeningPresets.all.map(op => Json.obj(
        "name" -> op.name,
        "fen" -> op.fen.value,
        "url" -> op.url
      ))
    ))

  // 픽 설정 (이름 리스트로 받아서 OpeningPreset으로 변환)
  def setPicks(id: SeriesId) = AuthBody(parse.json) { ctx ?=> me ?=>
    Found(api.byId(id)): s =>
      if !s.players.contains(me.userId) then
        fuccess(JsonBadRequest(jsonError("Not a player of this series")))
      else
        ctx.body.body.asOpt[JsArray].map(_.value.flatMap(_.asOpt[String]).toList) match
          case None => fuccess(JsonBadRequest(jsonError("Invalid request body")))
          case Some(names) =>
            val picks = names.flatMap(name => OpeningPresets.all.find(_.name == name))
            api.setPicks(id, me.userId, picks).map:
              case Some(_) => JsonOk(Json.obj("ok" -> true, "picks" -> picks.map(_.name)))
              case None => JsonBadRequest(jsonError("Cannot set picks in current phase"))
  }

  // 밴 설정 (이름 리스트로 받아서 OpeningPreset으로 변환)
  def setBans(id: SeriesId) = AuthBody(parse.json) { ctx ?=> me ?=>
    Found(api.byId(id)): s =>
      if !s.players.contains(me.userId) then
        fuccess(JsonBadRequest(jsonError("Not a player of this series")))
      else
        ctx.body.body.asOpt[JsArray].map(_.value.flatMap(_.asOpt[String]).toList) match
          case None => fuccess(JsonBadRequest(jsonError("Invalid request body")))
          case Some(names) =>
            val bans = names.flatMap(name => OpeningPresets.all.find(_.name == name))
            api.setBans(id, me.userId, bans).map:
              case Some(_) => JsonOk(Json.obj("ok" -> true, "bans" -> bans.map(_.name)))
              case None => JsonBadRequest(jsonError("Cannot set bans in current phase"))
  }

  // 픽 확정 (양측 완료 시 다음 페이즈로)
  def confirmPicks(id: SeriesId) = Auth { ctx ?=> me ?=>
    Found(api.byId(id)): s =>
      if !s.players.contains(me.userId) then
        JsonBadRequest(jsonError("Not a player of this series"))
      else
        api.confirmPicks(id, me.userId).map:
          case Some(updated) =>
            val myColor = updated.colorOf(me.userId).get
            JsonOk(Json.obj(
              "ok" -> true,
              "phase" -> updated.phase.id,
              "myConfirmed" -> updated.confirmedPicks(myColor),
              "opponentConfirmed" -> updated.confirmedPicks(!myColor)
            ))
          case None => JsonBadRequest(jsonError("Cannot confirm picks"))
  }

  // 픽 확정 취소
  def cancelConfirmPicks(id: SeriesId) = Auth { ctx ?=> me ?=>
    Found(api.byId(id)): s =>
      if !s.players.contains(me.userId) then
        JsonBadRequest(jsonError("Not a player of this series"))
      else
        api.cancelConfirmPicks(id, me.userId).map:
          case Some(updated) =>
            val myColor = updated.colorOf(me.userId).get
            JsonOk(Json.obj(
              "ok" -> true,
              "phase" -> updated.phase.id,
              "myConfirmed" -> updated.confirmedPicks(myColor),
              "opponentConfirmed" -> updated.confirmedPicks(!myColor)
            ))
          case None => JsonBadRequest(jsonError("Cannot cancel confirm"))
  }

  // 밴 확정 (양측 완료 시 다음 페이즈로)
  def confirmBans(id: SeriesId) = Auth { ctx ?=> me ?=>
    Found(api.byId(id)): s =>
      if !s.players.contains(me.userId) then
        JsonBadRequest(jsonError("Not a player of this series"))
      else
        api.confirmBans(id, me.userId).map:
          case Some(updated) =>
            val myColor = updated.colorOf(me.userId).get
            JsonOk(Json.obj(
              "ok" -> true,
              "phase" -> updated.phase.id,
              "myConfirmed" -> updated.confirmedBans(myColor),
              "opponentConfirmed" -> updated.confirmedBans(!myColor)
            ))
          case None => JsonBadRequest(jsonError("Cannot confirm bans"))
  }

  // 밴 확정 취소
  def cancelConfirmBans(id: SeriesId) = Auth { ctx ?=> me ?=>
    Found(api.byId(id)): s =>
      if !s.players.contains(me.userId) then
        JsonBadRequest(jsonError("Not a player of this series"))
      else
        api.cancelConfirmBans(id, me.userId).map:
          case Some(updated) =>
            val myColor = updated.colorOf(me.userId).get
            JsonOk(Json.obj(
              "ok" -> true,
              "phase" -> updated.phase.id,
              "myConfirmed" -> updated.confirmedBans(myColor),
              "opponentConfirmed" -> updated.confirmedBans(!myColor)
            ))
          case None => JsonBadRequest(jsonError("Cannot cancel confirm"))
  }

  // Game1 랜덤 오프닝 선택 (Game1Shuffling → Playing)
  def selectGame1Opening(id: SeriesId) = Auth { ctx ?=> me ?=>
    Found(api.byId(id)): s =>
      if !s.players.contains(me.userId) then
        JsonBadRequest(jsonError("Not a player of this series"))
      else
        api.selectGame1Opening(id).map:
          case Some(updated) =>
            val opening = updated.openings.lastOption
            JsonOk(Json.obj(
              "ok" -> true,
              "phase" -> updated.phase.id,
              "opening" -> opening.map(op => Json.obj("name" -> op.name, "fen" -> op.fen.value, "url" -> op.url))
            ))
          case None => JsonBadRequest(jsonError("Cannot select opening"))
  }

  // 다음 오프닝 선택 (패자용, Selecting → Playing)
  def selectNextOpening(id: SeriesId) = AuthBody(parse.json) { ctx ?=> me ?=>
    Found(api.byId(id)): s =>
      if !s.players.contains(me.userId) then
        fuccess(JsonBadRequest(jsonError("Not a player of this series")))
      else
        ctx.body.body.asOpt[String] match
          case None => fuccess(JsonBadRequest(jsonError("Invalid request body")))
          case Some(name) =>
            OpeningPresets.all.find(_.name == name) match
              case None => fuccess(JsonBadRequest(jsonError("Invalid opening name")))
              case Some(opening) =>
                api.selectNextOpening(id, me.userId, opening).map:
                  case Some(updated) =>
                    JsonOk(Json.obj(
                      "ok" -> true,
                      "phase" -> updated.phase.id,
                      "opening" -> Json.obj("name" -> opening.name, "fen" -> opening.fen.value, "url" -> opening.url)
                    ))
                  case None => JsonBadRequest(jsonError("Cannot select opening"))
  }
