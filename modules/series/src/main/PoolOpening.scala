package lila.series

import chess.format.Fen

/** 마스터 오프닝 카탈로그의 오프닝 */
case class PoolOpening(
    id: PoolOpeningId,
    name: String,
    fen: Fen.Full,
    url: String
)

object PoolOpening:
  def nameToSlug(name: String): String =
    name.toLowerCase
      .replace(": ", "-")
      .replace(", ", "-")
      .replace(" ", "-")
      .replaceAll("[^a-z0-9-]", "")

  def fromPreset(preset: OpeningPreset): PoolOpening =
    PoolOpening(
      id = PoolOpeningId(nameToSlug(preset.name)),
      name = preset.name,
      fen = preset.fen,
      url = preset.url
    )

opaque type PoolOpeningId = String
object PoolOpeningId extends lila.core.OpaqueString[PoolOpeningId]
