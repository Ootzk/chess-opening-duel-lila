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
      .asyncMany(List(s.players._1.userId, s.players._2.userId))
      .map: users =>
        val (user0, user1) = (users.headOption.flatten, users.lastOption.flatten)
        val povIndex = povUserId.flatMap(s.playerIndex)

        // Pick Phase에서는 상대 픽을 숨김
        val visibleOpenings = if s.phase == Series.Phase.Picking then
          povIndex match
            case Some(idx) => s.openings.filter(o => o.ownerIndex == idx && o.isPick)
            case None => Nil
        else
          s.openings

        // timeLeft는 Picking, Banning, Selecting phase에서만 의미 있음
        val timeLeft =
          if s.phase == Series.Phase.Playing || s.phase == Series.Phase.Finished then None
          else Some(s.timeLeftInPhase)

        Json.obj(
          "id" -> s.id.value,
          "phase" -> s.phase.id,
          "phaseName" -> phaseName(s.phase),
          "status" -> s.status.id,
          "bestOf" -> Series.bestOf,
          "round" -> s.currentRound,
          "players" -> Json.arr(
            playerJson(s.players._1, user0),
            playerJson(s.players._2, user1)
          ),
          "openings" -> visibleOpenings.map(openingJson),
          "games" -> s.games.map(gameJson),
          "finished" -> s.isFinished,
          "winner" -> s.winner
        )
        .add("povIndex" -> povIndex)
        .add("currentGame" -> s.currentGameId.map(_.value))
        .add("timeLeft" -> timeLeft)
        .add("selectingPlayer" -> {
          if s.phase == Series.Phase.Selecting then s.lastGameLoser
          else None
        })
        .add("rematchOfferedBy" -> s.rematchOfferedBy)
        .add("forfeitBy" -> s.forfeitBy)

  def roundInfo(s: Series): JsObject =
    Json.obj(
      "id" -> s.id.value,
      "round" -> s.currentRound,
      "bestOf" -> Series.bestOf,
      "scores" -> Json.arr(s.players._1.displayScore, s.players._2.displayScore)
    )

  private def playerJson(p: SeriesPlayer, user: Option[LightUser]): JsObject =
    Json.obj(
      "index" -> p.index,
      "score" -> p.displayScore,
      "confirmedPicks" -> p.confirmedPicks,
      "confirmedBans" -> p.confirmedBans,
      "confirmedSelecting" -> p.confirmedSelecting,
      "isOnline" -> p.isOnline
    )
    .add("selectingPick" -> p.selectingPick)
    .add("user" -> user.map(u =>
      Json.obj("id" -> u.id, "name" -> u.name)
        .add("title" -> u.title.map(_.value))
        .add("flair" -> u.flair)
    ))

  private def openingJson(o: SeriesOpening): JsObject = Json.obj(
    "id" -> o.id.value,
    "name" -> o.name,
    "fen" -> o.fen.value,
    "url" -> o.url,
    "source" -> (if o.isPick then "pick" else if o.isNeutral then "neutral" else "ban"),
    "owner" -> o.ownerIndex,
    "usedInRound" -> o.usedInRound,
    "selectedBy" -> o.selectedBy.map(_.toString.toLowerCase)
  )

  private def gameJson(g: SeriesGame): JsObject = Json.obj(
    "gameId" -> g.gameId.value,
    "round" -> g.round,
    "openingId" -> g.openingId.value,
    "whitePlayer" -> g.whitePlayerIndex,
    "result" -> g.result.map:
      case GameResult.WhiteWins => "white"
      case GameResult.BlackWins => "black"
      case GameResult.Draw => "draw"
  )

  private def phaseName(phase: Series.Phase): String = phase match
    case Series.Phase.Picking => "Pick Phase"
    case Series.Phase.Banning => "Ban Phase"
    case Series.Phase.RandomSelecting => "Starting..."
    case Series.Phase.Playing => "Playing"
    case Series.Phase.Selecting => "Select Opening"
    case Series.Phase.Finished => "Finished"
