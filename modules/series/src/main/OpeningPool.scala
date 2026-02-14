package lila.series

import lila.core.userId.UserId

/** 유저 pool 내 오프닝 참조 + ownerColor */
case class PoolEntry(
    openingId: PoolOpeningId,
    ownerColor: chess.Color
)

/** 유저의 opening pool (계정 생성 시 초기화, 항상 존재) */
case class OpeningPool(
    userId: UserId,
    openings: List[PoolEntry],
    updatedAt: Instant
)

object OpeningPool:
  val minPoolSize = 5
  val maxPoolSize = 10

  def makeDefault(userId: UserId): OpeningPool =
    OpeningPool(
      userId = userId,
      openings = OpeningPresets.all.toList.map: preset =>
        PoolEntry(
          openingId = PoolOpeningId(PoolOpening.nameToSlug(preset.name)),
          ownerColor = preset.ownerColor
        ),
      updatedAt = nowInstant
    )
