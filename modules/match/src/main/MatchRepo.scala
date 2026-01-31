package lila.`match`

import reactivemongo.api.bson.*

import lila.db.dsl.{ *, given }
import lila.core.id.{ GameId, MatchId }

final class MatchRepo(val coll: Coll)(using Executor):

  import BsonHandlers.given

  def byId(id: MatchId): Fu[Option[Match]] =
    coll.byId[Match](id)

  def insert(m: Match): Funit =
    coll.insert.one(m).void

  def update(m: Match): Funit =
    coll.update.one($id(m.id), m).void

  def addGame(matchId: MatchId, gameId: GameId): Funit =
    coll.update
      .one(
        $id(matchId),
        $push("g" -> gameId) ++ $set("s" -> Match.Status.Started.id)
      )
      .void

  def recordResult(matchId: MatchId, winnerColor: Option[chess.Color]): Fu[Option[Match]] =
    byId(matchId).flatMap:
      case None => fuccess(None)
      case Some(m) =>
        val updated = m.recordResult(winnerColor)
        update(updated).inject(Some(updated))

  def byGameId(gameId: GameId): Fu[Option[Match]] =
    coll.one[Match]($doc("g" -> gameId))
