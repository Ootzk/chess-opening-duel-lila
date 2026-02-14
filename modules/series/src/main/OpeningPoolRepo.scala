package lila.series

import reactivemongo.api.bson.*

import lila.db.dsl.{ *, given }
import lila.core.userId.UserId

final class OpeningPoolRepo(
    val openingsColl: Coll,
    val poolColl: Coll
)(using Executor):

  import BsonHandlers.given

  // ===== Master Openings =====

  def allOpenings: Fu[List[PoolOpening]] =
    openingsColl.list[PoolOpening]($empty)

  def openingsByIds(ids: List[PoolOpeningId]): Fu[List[PoolOpening]] =
    openingsColl.list[PoolOpening]($inIds(ids))

  def upsertOpening(o: PoolOpening): Funit =
    openingsColl.update.one($id(o.id), o, upsert = true).void

  // ===== User Pools =====

  def getPool(userId: UserId): Fu[Option[OpeningPool]] =
    poolColl.byId[OpeningPool](userId)

  def poolExists(userId: UserId): Fu[Boolean] =
    poolColl.exists($id(userId))

  def insertPool(pool: OpeningPool): Funit =
    poolColl.insert.one(pool).void

  def updatePool(pool: OpeningPool): Funit =
    poolColl.update.one($id(pool.userId), pool, upsert = true).void
