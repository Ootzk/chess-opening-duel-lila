package lila.series

import reactivemongo.api.bson.*

import lila.db.dsl.{ *, given }
import lila.core.id.{ GameId, SeriesId }

final class SeriesRepo(val coll: Coll)(using Executor):

  import BsonHandlers.given

  def byId(id: SeriesId): Fu[Option[Series]] =
    coll.byId[Series](id)

  def insert(s: Series): Funit =
    coll.insert.one(s).void

  def update(s: Series): Funit =
    coll.update.one($id(s.id), s).void

  def addGame(seriesId: SeriesId, gameId: GameId): Funit =
    coll.update
      .one(
        $id(seriesId),
        $push("g" -> gameId) ++ $set("s" -> Series.Status.Started.id)
      )
      .void

  def recordResult(seriesId: SeriesId, winnerColor: Option[chess.Color]): Fu[Option[Series]] =
    byId(seriesId).flatMap:
      case None => fuccess(None)
      case Some(s) =>
        val updated = s.recordResult(winnerColor)
        update(updated).inject(Some(updated))

  def byGameId(gameId: GameId): Fu[Option[Series]] =
    coll.one[Series]($doc("g" -> gameId))
