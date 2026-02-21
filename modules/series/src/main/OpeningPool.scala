package lila.series

import chess.format.Fen
import lila.core.userId.UserId

opaque type PoolOpeningId = String
object PoolOpeningId extends lila.core.OpaqueString[PoolOpeningId]

/** 유저 pool 내 오프닝 (데이터 임베딩) */
case class PoolEntry(
    id: PoolOpeningId,
    name: String,
    fen: Fen.Full,
    url: String,
    ownerColor: chess.Color
)

object PoolEntry:
  def nameToSlug(name: String): String =
    name.toLowerCase
      .replace(": ", "-")
      .replace(", ", "-")
      .replace(" ", "-")
      .replaceAll("[^a-z0-9-]", "")

  def fromPreset(preset: OpeningPreset): PoolEntry =
    PoolEntry(
      id = PoolOpeningId(nameToSlug(preset.name)),
      name = preset.name,
      fen = preset.fen,
      url = preset.url,
      ownerColor = preset.ownerColor
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
      openings = OpeningPresets.all.toList.map(PoolEntry.fromPreset),
      updatedAt = nowInstant
    )
