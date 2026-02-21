package lila.series

import lila.core.userId.UserId

final class OpeningPoolApi(
    repo: OpeningPoolRepo
)(using Executor):

  /** 유저의 pool을 List[OpeningPreset]로 반환.
    * Pool이 없으면 자동 생성 (ensurePool).
    */
  def getPresetsForUser(userId: UserId): Fu[List[OpeningPreset]] =
    ensurePool(userId).map: pool =>
      pool.openings.map(entryToPreset)

  /** ID 포함 반환 (pool 테이블 삭제 버튼용) */
  def getPresetsWithIdsForUser(userId: UserId): Fu[List[(PoolOpeningId, OpeningPreset)]] =
    ensurePool(userId).map: pool =>
      pool.openings.map: entry =>
        (entry.id, entryToPreset(entry))

  /** 기본 10개 pool 생성. idempotent (이미 있으면 skip). */
  def initializePool(userId: UserId): Funit =
    repo.poolExists(userId).flatMap:
      case true  => funit
      case false => repo.insertPool(OpeningPool.makeDefault(userId))

  /** pool이 없으면 기본 pool 생성 후 반환 */
  private def ensurePool(userId: UserId): Fu[OpeningPool] =
    repo.getPool(userId).flatMap:
      case Some(pool) => fuccess(pool)
      case None =>
        val pool = OpeningPool.makeDefault(userId)
        repo.insertPool(pool).inject(pool)

  /** 개별 삭제: pool.size > minPoolSize 검증 */
  def removeFromPool(userId: UserId, openingId: PoolOpeningId, color: chess.Color): Fu[Boolean] =
    ensurePool(userId).flatMap: pool =>
      val newOpenings = pool.openings.filterNot(e => e.id == openingId && e.ownerColor == color)
      if newOpenings.size == pool.openings.size then fuccess(false) // not found
      else if newOpenings.size < OpeningPool.minPoolSize then fuccess(false)
      else
        val updated = pool.copy(openings = newOpenings, updatedAt = nowInstant)
        repo.updatePool(updated).inject(true)

  /** 개별 추가: pool.size < maxPoolSize + 중복 검증 */
  def addOpeningToPool(userId: UserId, name: String, fen: chess.format.Fen.Full, url: String, color: chess.Color): Fu[Boolean] =
    ensurePool(userId).flatMap: pool =>
      if pool.openings.size >= OpeningPool.maxPoolSize then fuccess(false)
      else
        val openingId = PoolOpeningId(PoolEntry.nameToSlug(name))
        if pool.openings.exists(e => e.id == openingId && e.ownerColor == color) then fuccess(false)
        else
          val entry = PoolEntry(openingId, name, fen, url, color)
          val updated = pool.copy(openings = pool.openings :+ entry, updatedAt = nowInstant)
          repo.updatePool(updated).inject(true)

  /** PoolEntry → OpeningPreset 변환 */
  private def entryToPreset(e: PoolEntry): OpeningPreset =
    OpeningPreset(e.name, e.fen, e.url, e.ownerColor)
