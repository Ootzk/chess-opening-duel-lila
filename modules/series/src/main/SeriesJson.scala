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
        Json.obj(
          "id" -> s.id,
          "bestOf" -> s.bestOf,
          "round" -> s.currentRound,
          "status" -> s.status.id,
          "players" -> Json.obj(
            "white" -> userJson(white, s.scores.white),
            "black" -> userJson(black, s.scores.black)
          ),
          "scores" -> Json.arr(s.scores.white, s.scores.black),
          "finished" -> s.isFinished,
          "winner" -> s.winner.map(_.name)
        ).add("povColor" -> povUserId.flatMap(s.colorOf).map(_.name))

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
