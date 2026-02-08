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

  def byGameId(gameId: GameId): Fu[Option[Series]] =
    coll.one[Series]($doc("gm.g" -> gameId))

  /** Atomically update lastSeenAt for a specific player using $set */
  def setLastSeen(id: SeriesId, playerIndex: Int): Funit =
    val field = s"p$playerIndex.ls"
    coll.update.one($id(id), $set(field -> nowInstant)).void

  /** Atomically set confirmedPicks for a specific player */
  def setConfirmedPicks(id: SeriesId, playerIndex: Int, confirmed: Boolean): Funit =
    val field = s"p$playerIndex.cp"
    coll.update.one($id(id), $set(field -> confirmed)).void

  /** Atomically set confirmedBans for a specific player */
  def setConfirmedBans(id: SeriesId, playerIndex: Int, confirmed: Boolean): Funit =
    val field = s"p$playerIndex.cb"
    coll.update.one($id(id), $set(field -> confirmed)).void

  /** Atomically set phase and phaseStartedAt, only if current phase matches expected.
    * Returns true if the update was applied (i.e., phase was still as expected).
    */
  def setPhaseIfCurrent(id: SeriesId, expectedPhase: Series.Phase, newPhase: Series.Phase): Fu[Boolean] =
    coll.update
      .one(
        $id(id) ++ $doc("ph" -> expectedPhase.id),
        $set("ph" -> newPhase.id, "psa" -> nowInstant)
      )
      .dmap(_.nModified > 0)

  // 두 플레이어로 가장 최근 생성된 series 찾기 (밴픽 리다이렉트용)
  def byPlayers(user1: UserId, user2: UserId): Fu[Option[Series]] =
    coll
      .find($or(
        $doc("p0.u" -> user1, "p1.u" -> user2),
        $doc("p0.u" -> user2, "p1.u" -> user1)
      ))
      .sort($doc("ca" -> -1)) // createdAt 내림차순
      .one[Series]
