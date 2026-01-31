package lila.series

import reactivemongo.api.bson.*

import lila.db.dsl.{ *, given }
import lila.core.id.{ GameId, SeriesId }
import lila.core.userId.UserId

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

  // 두 플레이어로 가장 최근 생성된 series 찾기 (밴픽 리다이렉트용)
  // "p"는 [whiteUserId, blackUserId] 배열
  def byPlayers(user1: UserId, user2: UserId): Fu[Option[Series]] =
    coll
      .find($doc("p".$all(List(user1, user2))))
      .sort($doc("ca" -> -1)) // createdAt 내림차순
      .one[Series]
