package controllers

import chess.Color
import play.api.libs.json.{ Json, JsArray }

import lila.app.*
import lila.core.id.SeriesId
import lila.series.OpeningPresets

final class Series(env: Env) extends LilaController(env):
  def api = env.series.api

  private def isPlayer(s: lila.series.Series, userId: lila.core.userId.UserId): Boolean =
    s.playerIndex(userId).isDefined

  def show(id: SeriesId) = Open:
    Found(api.byId(id)): s =>
      for
        json <- env.series.jsonView(s, ctx.me.map(_.userId))
      yield JsonOk(json)

  // 밴픽 페이지 (HTML)
  def pickPage(id: SeriesId) = Auth { ctx ?=> me ?=>
    Found(api.byId(id)): s =>
      if !isPlayer(s, me.userId) then Forbidden("Not a player of this series")
      else if s.phase == lila.series.Series.Phase.RandomSelecting then
        Redirect(routes.Series.randomSelectingPage(id))
      else if s.phase == lila.series.Series.Phase.Playing then
        // Playing 중이면 현재 게임으로 리다이렉트
        s.currentGameId match
          case Some(gameId) =>
            val povIndex = s.playerIndex(me.userId).getOrElse(0)
            val round = s.currentRound
            val isWhite = s.whitePlayerIndex(round) == povIndex
            val povColor = if isWhite then Color.white else Color.black
            Redirect(s"/${gameId}/${povColor.name}")
          case None => Redirect(routes.Series.show(id))
      else if s.isFinished then
        Redirect(routes.Series.finishedPage(id))
      else
        val povIndex = s.playerIndex(me.userId).getOrElse(0)
        val displayOpenings: Vector[lila.series.OpeningPreset] = s.phase match
          case lila.series.Series.Phase.Picking => OpeningPresets.all
          case lila.series.Series.Phase.Banning =>
            // 상대의 픽을 표시
            s.picks(1 - povIndex).map(_.toPreset).toVector
          case lila.series.Series.Phase.Selecting =>
            // 양측 동일: 패자(selectingPlayer)의 remaining picks 표시
            val selectingIdx = s.lastGameLoser.getOrElse(0)
            s.remainingPicks(selectingIdx).map(_.toPreset).toVector
          case _ => OpeningPresets.all
        for
          json <- env.series.jsonView(s, Some(me.userId))
          socketVersion <- env.series.version(s.id)
          page <- Ok.page(views.series.pick(s, json, OpeningPresets.all, displayOpenings, socketVersion))
        yield page
  }

  // RandomSelecting 페이지 (HTML)
  def randomSelectingPage(id: SeriesId) = Auth { ctx ?=> me ?=>
    Found(api.byId(id)): s =>
      if !isPlayer(s, me.userId) then Forbidden("Not a player of this series")
      else if s.phase != lila.series.Series.Phase.RandomSelecting then
        Redirect(routes.Series.pickPage(id))
      else
        for
          json <- env.series.jsonView(s, Some(me.userId))
          socketVersion <- env.series.version(s.id)
          page <- Ok.page(views.series.randomSelecting(s, json, socketVersion))
        yield page
  }

  def apiShow(id: SeriesId) = Open:
    Found(api.byId(id)): s =>
      for
        // Update lastSeenAt for the polling player
        updated <- ctx.me.map(_.userId).flatMap(s.playerIndex).fold(fuccess(s)): _ =>
          api.updateLastSeen(id, ctx.me.get.userId).map(_.getOrElse(s))
        json <- env.series.jsonView(updated, ctx.me.map(_.userId))
      yield JsonOk(json)

  def nextGame(id: SeriesId) = Auth { ctx ?=> me ?=>
    Found(api.byId(id)): s =>
      if s.isFinished then JsonOk(Json.obj("redirect" -> routes.Series.show(id).url))
      else if !isPlayer(s, me.userId) then Forbidden("Not a player of this series")
      else
        api.createNextGame(id).map:
          case Some(game) =>
            val povIndex = s.playerIndex(me.userId).getOrElse(0)
            val round = s.currentRound
            val isWhite = s.whitePlayerIndex(round) == povIndex
            val povColor = if isWhite then Color.white else Color.black
            JsonOk(Json.obj("redirect" -> s"/${game.id}/${povColor.name}"))
          case None => JsonOk(Json.obj("redirect" -> routes.Series.show(id).url))
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
      if !isPlayer(s, me.userId) then
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
      if !isPlayer(s, me.userId) then
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
      if !isPlayer(s, me.userId) then
        JsonBadRequest(jsonError("Not a player of this series"))
      else
        api.confirmPicks(id, me.userId).map:
          case Some(updated) =>
            val myIdx = updated.playerIndex(me.userId).get
            val oppIdx = 1 - myIdx
            JsonOk(Json.obj(
              "ok" -> true,
              "phase" -> updated.phase.id,
              "myConfirmed" -> updated.player(myIdx).confirmedPicks,
              "opponentConfirmed" -> updated.player(oppIdx).confirmedPicks
            ))
          case None => JsonBadRequest(jsonError("Cannot confirm picks"))
  }

  // 픽 확정 취소
  def cancelConfirmPicks(id: SeriesId) = Auth { ctx ?=> me ?=>
    Found(api.byId(id)): s =>
      if !isPlayer(s, me.userId) then
        JsonBadRequest(jsonError("Not a player of this series"))
      else
        api.cancelConfirmPicks(id, me.userId).map:
          case Some(updated) =>
            val myIdx = updated.playerIndex(me.userId).get
            val oppIdx = 1 - myIdx
            JsonOk(Json.obj(
              "ok" -> true,
              "phase" -> updated.phase.id,
              "myConfirmed" -> updated.player(myIdx).confirmedPicks,
              "opponentConfirmed" -> updated.player(oppIdx).confirmedPicks
            ))
          case None => JsonBadRequest(jsonError("Cannot cancel confirm"))
  }

  // 픽 타임아웃 (선택 목록 + 랜덤 채우기 + 자동 확정)
  def timeoutPicks(id: SeriesId) = AuthBody(parse.json) { ctx ?=> me ?=>
    Found(api.byId(id)): s =>
      if !isPlayer(s, me.userId) then
        fuccess(JsonBadRequest(jsonError("Not a player of this series")))
      else
        ctx.body.body.asOpt[JsArray].map(_.value.flatMap(_.asOpt[String]).toList) match
          case None => fuccess(JsonBadRequest(jsonError("Invalid request body")))
          case Some(names) =>
            api.timeoutPicks(id, me.userId, names).map:
              case Some(updated) =>
                val myIdx = updated.playerIndex(me.userId).get
                val oppIdx = 1 - myIdx
                JsonOk(Json.obj(
                  "ok" -> true,
                  "phase" -> updated.phase.id,
                  "myConfirmed" -> updated.player(myIdx).confirmedPicks,
                  "opponentConfirmed" -> updated.player(oppIdx).confirmedPicks
                ))
              case None => JsonBadRequest(jsonError("Cannot timeout picks"))
  }

  // 밴 확정 (양측 완료 시 다음 페이즈로)
  def confirmBans(id: SeriesId) = Auth { ctx ?=> me ?=>
    Found(api.byId(id)): s =>
      if !isPlayer(s, me.userId) then
        JsonBadRequest(jsonError("Not a player of this series"))
      else
        api.confirmBans(id, me.userId).map:
          case Some(updated) =>
            val myIdx = updated.playerIndex(me.userId).get
            val oppIdx = 1 - myIdx
            JsonOk(Json.obj(
              "ok" -> true,
              "phase" -> updated.phase.id,
              "myConfirmed" -> updated.player(myIdx).confirmedBans,
              "opponentConfirmed" -> updated.player(oppIdx).confirmedBans
            ))
          case None => JsonBadRequest(jsonError("Cannot confirm bans"))
  }

  // 밴 확정 취소
  def cancelConfirmBans(id: SeriesId) = Auth { ctx ?=> me ?=>
    Found(api.byId(id)): s =>
      if !isPlayer(s, me.userId) then
        JsonBadRequest(jsonError("Not a player of this series"))
      else
        api.cancelConfirmBans(id, me.userId).map:
          case Some(updated) =>
            val myIdx = updated.playerIndex(me.userId).get
            val oppIdx = 1 - myIdx
            JsonOk(Json.obj(
              "ok" -> true,
              "phase" -> updated.phase.id,
              "myConfirmed" -> updated.player(myIdx).confirmedBans,
              "opponentConfirmed" -> updated.player(oppIdx).confirmedBans
            ))
          case None => JsonBadRequest(jsonError("Cannot cancel confirm"))
  }

  // 밴 타임아웃 (선택 목록 + 랜덤 채우기 + 자동 확정)
  def timeoutBans(id: SeriesId) = AuthBody(parse.json) { ctx ?=> me ?=>
    Found(api.byId(id)): s =>
      if !isPlayer(s, me.userId) then
        fuccess(JsonBadRequest(jsonError("Not a player of this series")))
      else
        ctx.body.body.asOpt[JsArray].map(_.value.flatMap(_.asOpt[String]).toList) match
          case None => fuccess(JsonBadRequest(jsonError("Invalid request body")))
          case Some(names) =>
            api.timeoutBans(id, me.userId, names).map:
              case Some(updated) =>
                val myIdx = updated.playerIndex(me.userId).get
                val oppIdx = 1 - myIdx
                JsonOk(Json.obj(
                  "ok" -> true,
                  "phase" -> updated.phase.id,
                  "myConfirmed" -> updated.player(myIdx).confirmedBans,
                  "opponentConfirmed" -> updated.player(oppIdx).confirmedBans
                ))
              case None => JsonBadRequest(jsonError("Cannot timeout bans"))
  }

  // 다음 오프닝 선택 (패자용, Selecting → Playing)
  // 게임 생성 후 리다이렉트 URL 반환
  def selectNextOpening(id: SeriesId) = AuthBody(parse.json) { ctx ?=> me ?=>
    Found(api.byId(id)): s =>
      if !isPlayer(s, me.userId) then
        fuccess(JsonBadRequest(jsonError("Not a player of this series")))
      else if s.phase != lila.series.Series.Phase.Selecting then
        fuccess(JsonBadRequest(jsonError("Not in selecting phase")))
      else
        ctx.body.body.asOpt[String] match
          case None => fuccess(JsonBadRequest(jsonError("Invalid request body")))
          case Some(name) =>
            OpeningPresets.all.find(_.name == name) match
              case None => fuccess(JsonBadRequest(jsonError("Invalid opening name")))
              case Some(opening) =>
                api.selectNextOpening(id, me.userId, opening).map:
                  case Some(game) =>
                    val povIndex = s.playerIndex(me.userId).getOrElse(0)
                    val newRound = s.currentRound
                    val isWhite = s.whitePlayerIndex(newRound) == povIndex
                    val povColor = if isWhite then Color.white else Color.black
                    JsonOk(Json.obj(
                      "ok" -> true,
                      "redirect" -> s"/${game.id}/${povColor.name}",
                      "opening" -> Json.obj("name" -> opening.name, "fen" -> opening.fen.value, "url" -> opening.url)
                    ))
                  case None => JsonBadRequest(jsonError("Cannot select opening"))
  }

  // 실시간 선택 동기화 (Selecting phase, DB 저장 없음)
  def setSelectingPick(id: SeriesId) = AuthBody(parse.json) { ctx ?=> me ?=>
    Found(api.byId(id)): s =>
      if !isPlayer(s, me.userId) then
        fuccess(JsonBadRequest(jsonError("Not a player of this series")))
      else
        val name = ctx.body.body.asOpt[String]
        api.setSelectingPick(id, me.userId, name).map:
          case Some(_) => JsonOk(Json.obj("ok" -> true))
          case None => JsonBadRequest(jsonError("Cannot set selecting pick"))
  }

  // Selecting phase confirm (3초 delay 후 게임 생성)
  def confirmSelecting(id: SeriesId) = AuthBody(parse.json) { ctx ?=> me ?=>
    Found(api.byId(id)): s =>
      if !isPlayer(s, me.userId) then
        fuccess(JsonBadRequest(jsonError("Not a player of this series")))
      else
        ctx.body.body.asOpt[String] match
          case None => fuccess(JsonBadRequest(jsonError("Invalid request body")))
          case Some(name) =>
            api.confirmSelectingPick(id, me.userId, name).map:
              case Some(updated) =>
                val myIdx = updated.playerIndex(me.userId).get
                JsonOk(Json.obj(
                  "ok" -> true,
                  "confirmedSelecting" -> updated.player(myIdx).confirmedSelecting
                ))
              case None => JsonBadRequest(jsonError("Cannot confirm selecting"))
  }

  // Selecting phase cancel confirm
  def cancelConfirmSelecting(id: SeriesId) = Auth { ctx ?=> me ?=>
    Found(api.byId(id)): s =>
      if !isPlayer(s, me.userId) then
        JsonBadRequest(jsonError("Not a player of this series"))
      else
        api.cancelConfirmSelecting(id, me.userId).map:
          case Some(updated) =>
            val myIdx = updated.playerIndex(me.userId).get
            JsonOk(Json.obj(
              "ok" -> true,
              "confirmedSelecting" -> updated.player(myIdx).confirmedSelecting
            ))
          case None => JsonBadRequest(jsonError("Cannot cancel confirm"))
  }

  // 시리즈 포기 (전체 시리즈를 presser 패배로 종료)
  def forfeit(id: SeriesId) = Auth { ctx ?=> me ?=>
    Found(api.byId(id)): s =>
      if !isPlayer(s, me.userId) then
        JsonBadRequest(jsonError("Not a player of this series"))
      else
        api.forfeitSeries(id, me.userId).map:
          case Some(_) => JsonOk(Json.obj("ok" -> true))
          case None => JsonBadRequest(jsonError("Cannot forfeit"))
  }

  // Selecting 타임아웃 시 랜덤 선택 (패자용)
  def selectRandomOpening(id: SeriesId) = Auth { ctx ?=> me ?=>
    Found(api.byId(id)): s =>
      if !isPlayer(s, me.userId) then
        JsonBadRequest(jsonError("Not a player of this series"))
      else if s.phase != lila.series.Series.Phase.Selecting then
        JsonBadRequest(jsonError("Not in selecting phase"))
      else
        api.handleSelectingTimeout(id).map:
          case Some(game) =>
            val povIndex = s.playerIndex(me.userId).getOrElse(0)
            val newRound = s.currentRound
            val isWhite = s.whitePlayerIndex(newRound) == povIndex
            val povColor = if isWhite then Color.white else Color.black
            JsonOk(Json.obj(
              "ok" -> true,
              "redirect" -> s"/${game.id}/${povColor.name}"
            ))
          case None => JsonBadRequest(jsonError("Cannot select opening"))
  }

  // 시리즈 종료 페이지 (HTML)
  def finishedPage(id: SeriesId) = Auth { ctx ?=> me ?=>
    Found(api.byId(id)): s =>
      if !isPlayer(s, me.userId) then Forbidden("Not a player of this series")
      else if !s.isFinished then Redirect(routes.Series.pickPage(id))
      else
        for
          json <- env.series.jsonView(s, Some(me.userId))
          socketVersion <- env.series.version(s.id)
          page <- Ok.page(views.series.finished(s, json, socketVersion))
        yield page
  }

  // 리매치 offer/accept
  def rematch(id: SeriesId) = Auth { ctx ?=> me ?=>
    Found(api.byId(id)): s =>
      if !isPlayer(s, me.userId) then
        JsonBadRequest(jsonError("Not a player of this series"))
      else if !s.isFinished then
        JsonBadRequest(jsonError("Series not finished"))
      else
        api.offerOrAcceptRematch(id, me.userId).map:
          case Some(newSeriesId) =>
            JsonOk(Json.obj("ok" -> true, "newSeriesId" -> newSeriesId.value))
          case None =>
            JsonOk(Json.obj("ok" -> true, "offered" -> true))
  }
