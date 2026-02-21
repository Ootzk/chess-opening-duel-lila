package lila.series

import reactivemongo.api.bson.*

import lila.db.dsl.{ *, given }
import lila.core.userId.UserId

final class OpeningPoolRepo(
    val poolColl: Coll
)(using Executor):

  import BsonHandlers.given

  def getPool(userId: UserId): Fu[Option[OpeningPool]] =
    poolColl.byId[OpeningPool](userId)

  def poolExists(userId: UserId): Fu[Boolean] =
    poolColl.exists($id(userId))

  def insertPool(pool: OpeningPool): Funit =
    poolColl.insert.one(pool).void

  def updatePool(pool: OpeningPool): Funit =
    poolColl.update.one($id(pool.userId), pool, upsert = true).void
