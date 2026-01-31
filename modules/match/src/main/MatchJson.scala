package lila.`match`

import play.api.libs.json.*

import lila.core.LightUser
import lila.core.userId.UserId

final class MatchJson(
    lightUserApi: lila.core.user.LightUserApi
)(using Executor):

  def apply(m: Match, povUserId: Option[UserId]): Fu[JsObject] =
    lightUserApi
      .asyncMany(List(m.players.white, m.players.black))
      .map: users =>
        val (white, black) = (users.headOption.flatten, users.lastOption.flatten)
        Json.obj(
          "id" -> m.id,
          "bestOf" -> m.bestOf,
          "round" -> m.currentRound,
          "status" -> m.status.id,
          "players" -> Json.obj(
            "white" -> userJson(white, m.scores.white),
            "black" -> userJson(black, m.scores.black)
          ),
          "scores" -> Json.arr(m.scores.white, m.scores.black),
          "finished" -> m.isFinished,
          "winner" -> m.winner.map(_.name)
        ).add("povColor" -> povUserId.flatMap(m.colorOf).map(_.name))

  def roundInfo(m: Match): JsObject =
    Json.obj(
      "id" -> m.id,
      "round" -> m.currentRound,
      "bestOf" -> m.bestOf,
      "scores" -> Json.arr(m.scores.white, m.scores.black)
    )

  private def userJson(user: Option[LightUser], score: Int): JsObject =
    Json
      .obj("score" -> score)
      .add("user" -> user.map(u => Json.obj("id" -> u.id, "name" -> u.name)))