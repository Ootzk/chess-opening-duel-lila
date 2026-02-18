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
      else if s.phase == lila.series.Series.Phase.Playing || s.phase == lila.series.Series.Phase.Resting then
        // Playing/Resting 중이면 현재/마지막 게임으로 리다이렉트
        val gameOpt = s.currentGame.orElse(s.lastFinishedGame)
        gameOpt match
          case Some(game) =>
            val povIndex = s.playerIndex(me.userId).getOrElse(0)
            val isWhite  = game.whitePlayerIndex == povIndex
            val povColor = if isWhite then Color.white else Color.black
            Redirect(s"/${game.gameId}/${povColor.name}")
          case None => Redirect(routes.Series.show(id))
      else if s.isFinished then
        Redirect(routes.Series.finishedPage(id))
      else
        val povIndex = s.playerIndex(me.userId).getOrElse(0)
        for
          myPool <- api.userPool(me.userId)
          displayOpenings: Vector[lila.series.OpeningPreset] = s.phase match
            case lila.series.Series.Phase.Picking => myPool
            case lila.series.Series.Phase.Banning =>
              // 상대의 픽을 표시
              s.picks(1 - povIndex).map(_.toPreset).toVector
            case lila.series.Series.Phase.Selecting =>
              // 양측 동일: 승자(selectingPlayer)의 remaining picks 표시
              val selectingIdx = s.lastGameWinner.getOrElse(0)
              s.remainingPicks(selectingIdx).map(_.toPreset).toVector
            case _ => myPool
          json <- env.series.jsonView(s, Some(me.userId))
          socketVersion <- env.series.version(s.id)
          page <- Ok.page(views.series.pick(s, json, myPool, displayOpenings, socketVersion))
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
            val povColor = Pov(game, me).fold(Color.white)(_.color)
            JsonOk(Json.obj("redirect" -> s"/${game.id}/${povColor.name}"))
          case None => JsonOk(Json.obj("redirect" -> routes.Series.show(id).url))
  }

  // 오프닝 프리셋 목록 조회 (로그인 시 유저 pool, 비로그인 시 기본)
  def presets = Open:
    ctx.me.fold(fuccess(OpeningPresets.all.toList))(me => api.userPool(me.userId).map(_.toList)).map: pool =>
      JsonOk(Json.obj(
        "presets" -> pool.map(op => Json.obj(
          "name" -> op.name,
          "fen" -> op.fen.value,
          "url" -> op.url,
          "ownerColor" -> op.ownerColor.name
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
            api.userPool(me.userId).flatMap: pool =>
              val picks = names.flatMap(name => pool.find(_.name == name))
              api.setPicks(id, me.userId, picks.toList).map:
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
            // Ban phase: 상대 픽에서 선택하므로 상대 픽을 사용
            val oppIdx = 1 - s.playerIndex(me.userId).getOrElse(0)
            val opponentPicks = s.picks(oppIdx)
            val bans = names.flatMap(name => opponentPicks.find(_.name == name).map(_.toPreset))
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
            // Selecting phase: 자신의 remaining picks에서 선택
            val myIdx = s.playerIndex(me.userId).getOrElse(0)
            s.remainingPicks(myIdx).find(_.name == name).map(_.toPreset) match
              case None => fuccess(JsonBadRequest(jsonError("Invalid opening name")))
              case Some(opening) =>
                api.selectNextOpening(id, me.userId, opening).map:
                  case Some(game) =>
                    val povColor = Pov(game, me).fold(Color.white)(_.color)
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

  // Resting phase: Next Game 확인
  def confirmNextGame(id: SeriesId) = Auth { ctx ?=> me ?=>
    api.confirmNextGame(id, me.userId).map:
      case Some(_) => jsonOkResult
      case None    => NotFound
  }

  // Resting phase: Next Game 확인 취소
  def cancelConfirmNextGame(id: SeriesId) = Auth { ctx ?=> me ?=>
    api.cancelConfirmNextGame(id, me.userId).map:
      case Some(_) => jsonOkResult
      case None    => NotFound
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
            val povColor = Pov(game, me).fold(Color.white)(_.color)
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
