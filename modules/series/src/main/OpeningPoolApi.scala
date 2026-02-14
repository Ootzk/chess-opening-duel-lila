package lila.series

import lila.core.userId.UserId

final class OpeningPoolApi(
    repo: OpeningPoolRepo
)(using Executor):

  private val logger = lila.log("openingPool")

  /** 유저의 pool을 List[OpeningPreset]로 반환.
    * Pool은 계정 생성 시 반드시 초기화되므로 항상 존재.
    * case None은 데이터 이상 시 safety net.
    */
  def getPresetsForUser(userId: UserId): Fu[List[OpeningPreset]] =
    repo.getPool(userId).flatMap:
      case None =>
        logger.error(s"Opening pool not found for user $userId, using defaults")
        fuccess(OpeningPresets.all.toList)
      case Some(pool) =>
        resolvePoolToPresets(pool)

  /** 타인 pool 열람용 */
  def getPoolForUser(userId: UserId): Fu[Option[List[OpeningPreset]]] =
    repo.getPool(userId).flatMap:
      case None => fuccess(None)
      case Some(pool) =>
        resolvePoolToPresets(pool).map(Some(_))

  /** 마스터 오프닝 전체 목록 */
  def allMasterOpenings: Fu[List[PoolOpening]] =
    repo.allOpenings

  /** 기본 10개 pool 생성. idempotent (이미 있으면 skip). */
  def initializePool(userId: UserId): Funit =
    repo.poolExists(userId).flatMap:
      case true  => funit
      case false => repo.insertPool(OpeningPool.makeDefault(userId))

  /** pool 교체 (5~10개 검증) */
  def setPool(userId: UserId, entries: List[PoolEntry]): Fu[Boolean] =
    if entries.size < OpeningPool.minPoolSize || entries.size > OpeningPool.maxPoolSize then fuccess(false)
    else
      val ids = entries.map(_.openingId)
      repo.openingsByIds(ids).flatMap: masterOpenings =>
        if masterOpenings.size != entries.size then fuccess(false)
        else
          val pool = OpeningPool(userId, entries, nowInstant)
          repo.updatePool(pool).inject(true)

  /** 마스터에 기본 10개 upsert (앱 시작 시, idempotent) */
  def seedMasterOpenings(): Funit =
    OpeningPresets.all.toList
      .map(PoolOpening.fromPreset)
      .traverse_(repo.upsertOpening)

  /** ID 포함 반환 (pool 테이블 삭제 버튼용) */
  def getPresetsWithIdsForUser(userId: UserId): Fu[List[(PoolOpeningId, OpeningPreset)]] =
    repo.getPool(userId).flatMap:
      case None =>
        logger.error(s"Opening pool not found for user $userId, using defaults")
        fuccess(OpeningPresets.all.toList.map: p =>
          (PoolOpeningId(PoolOpening.nameToSlug(p.name)), p)
        )
      case Some(pool) =>
        resolvePoolToPresetsWithIds(pool)

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
      val newOpenings = pool.openings.filterNot(e => e.openingId == openingId && e.ownerColor == color)
      if newOpenings.size == pool.openings.size then fuccess(false) // not found
      else if newOpenings.size < OpeningPool.minPoolSize then fuccess(false)
      else
        val updated = pool.copy(openings = newOpenings, updatedAt = nowInstant)
        repo.updatePool(updated).inject(true)

  /** 개별 추가: pool.size < maxPoolSize + 중복 검증 + 마스터 upsert */
  def addOpeningToPool(userId: UserId, name: String, fen: chess.format.Fen.Full, url: String, color: chess.Color): Fu[Boolean] =
    ensurePool(userId).flatMap: pool =>
      if pool.openings.size >= OpeningPool.maxPoolSize then fuccess(false)
      else
        val openingId = PoolOpeningId(PoolOpening.nameToSlug(name))
        if pool.openings.exists(e => e.openingId == openingId && e.ownerColor == color) then fuccess(false)
        else
          // 마스터 컬렉션에 upsert
          val master = PoolOpening(openingId, name, fen, url)
          repo.upsertOpening(master) >>
            val entry = PoolEntry(openingId, color)
            val updated = pool.copy(openings = pool.openings :+ entry, updatedAt = nowInstant)
            repo.updatePool(updated).inject(true)

  private def resolvePoolToPresetsWithIds(pool: OpeningPool): Fu[List[(PoolOpeningId, OpeningPreset)]] =
    val ids = pool.openings.map(_.openingId)
    repo.openingsByIds(ids).map: masterOpenings =>
      val openingMap = masterOpenings.map(o => o.id -> o).toMap
      pool.openings.flatMap: entry =>
        openingMap.get(entry.openingId).map: master =>
          (entry.openingId, OpeningPreset(master.name, master.fen, master.url, entry.ownerColor))

  private def resolvePoolToPresets(pool: OpeningPool): Fu[List[OpeningPreset]] =
    val ids = pool.openings.map(_.openingId)
    repo.openingsByIds(ids).map: masterOpenings =>
      val openingMap = masterOpenings.map(o => o.id -> o).toMap
      pool.openings.flatMap: entry =>
        openingMap.get(entry.openingId).map: master =>
          OpeningPreset(master.name, master.fen, master.url, entry.ownerColor)
