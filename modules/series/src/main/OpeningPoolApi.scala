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

  /** pool 교체 (10개 검증) */
  def setPool(userId: UserId, entries: List[PoolEntry]): Fu[Boolean] =
    if entries.size != OpeningPool.poolSize then fuccess(false)
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

  private def resolvePoolToPresets(pool: OpeningPool): Fu[List[OpeningPreset]] =
    val ids = pool.openings.map(_.openingId)
    repo.openingsByIds(ids).map: masterOpenings =>
      val openingMap = masterOpenings.map(o => o.id -> o).toMap
      pool.openings.flatMap: entry =>
        openingMap.get(entry.openingId).map: master =>
          OpeningPreset(master.name, master.fen, master.url, entry.ownerColor)
