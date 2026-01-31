package lila.series

import play.api.libs.json.*

import lila.common.Json.given
import lila.core.LightUser
import lila.core.userId.UserId

final class SeriesJson(
    lightUserApi: lila.core.user.LightUserApi
)(using Executor):

  def apply(s: Series, povUserId: Option[UserId]): Fu[JsObject] =
    lightUserApi
      .asyncMany(List(s.players.white, s.players.black))
      .map: users =>
        val (white, black) = (users.headOption.flatten, users.lastOption.flatten)
        val povColor = povUserId.flatMap(s.colorOf)
        // Pick Phase에서는 상대 픽을 숨김 (동시 비밀 공개 방식)
        val visiblePicks = if s.phase == Series.Phase.Picking then
          povColor match
            case Some(chess.Color.White) => chess.ByColor(s.picks.white, Nil)
            case Some(chess.Color.Black) => chess.ByColor(Nil, s.picks.black)
            case None => chess.ByColor.fill(Nil) // 관전자는 양쪽 다 안 보임
        else
          s.picks
        Json.obj(
          "id" -> s.id,
          "bestOf" -> s.bestOf,
          "round" -> s.currentRound,
          "status" -> s.status.id,
          "phase" -> s.phase.id,
          "phaseName" -> s.phase.toString,
          "players" -> Json.obj(
            "white" -> userJson(white, s.scores.white),
            "black" -> userJson(black, s.scores.black)
          ),
          "scores" -> Json.arr(s.scores.white, s.scores.black),
          "picks" -> Json.obj(
            "white" -> visiblePicks.white.map(openingJson),
            "black" -> visiblePicks.black.map(openingJson)
          ),
          "bans" -> Json.obj(
            "white" -> s.bans.white.map(openingJson),
            "black" -> s.bans.black.map(openingJson)
          ),
          "remainingPicks" -> Json.obj(
            "white" -> s.remainingPicks(chess.Color.White).map(openingJson),
            "black" -> s.remainingPicks(chess.Color.Black).map(openingJson)
          ),
          "bannedOpenings" -> s.bannedOpenings.map(openingJson),
          "confirmedPicks" -> Json.obj(
            "white" -> s.confirmedPicks.white,
            "black" -> s.confirmedPicks.black
          ),
          "confirmedBans" -> Json.obj(
            "white" -> s.confirmedBans.white,
            "black" -> s.confirmedBans.black
          ),
          "finished" -> s.isFinished,
          "winner" -> s.winner.map(_.name)
        ).add("povColor" -> povColor.map(_.name))

  def roundInfo(s: Series): JsObject =
    Json.obj(
      "id" -> s.id,
      "round" -> s.currentRound,
      "bestOf" -> s.bestOf,
      "scores" -> Json.arr(s.scores.white, s.scores.black)
    )

  private def userJson(user: Option[LightUser], score: Int): JsObject =
    Json
      .obj("score" -> score)
      .add("user" -> user.map(u => Json.obj("id" -> u.id, "name" -> u.name)))

  private def openingJson(op: OpeningPreset): JsObject =
    Json.obj(
      "name" -> op.name,
      "fen" -> op.fen.value,
      "url" -> op.url
    )
