package lila.series

import chess.format.Fen
import java.util.UUID

/** 시리즈 내 오프닝 인스턴스 */
case class SeriesOpening(
    id: SeriesOpeningId,
    name: String,
    fen: Fen.Full,
    url: Option[String],
    source: OpeningSource,
    ownerIndex: Int,
    usedInRound: Option[Int],
    selectedBy: Option[SelectionMethod]
):
  def isUsed: Boolean = usedInRound.isDefined
  def isPick: Boolean = source == OpeningSource.Pick
  def isBan: Boolean = source == OpeningSource.Ban

  def markUsed(round: Int, method: SelectionMethod): SeriesOpening =
    copy(usedInRound = Some(round), selectedBy = Some(method))

  def toPreset: OpeningPreset = OpeningPreset(name, fen, url.getOrElse(""))

object SeriesOpening:
  def fromPreset(preset: OpeningPreset, source: OpeningSource, ownerIndex: Int): SeriesOpening =
    SeriesOpening(
      id = SeriesOpeningId(UUID.randomUUID().toString.take(12)),
      name = preset.name,
      fen = preset.fen,
      url = Some(preset.url),
      source = source,
      ownerIndex = ownerIndex,
      usedInRound = None,
      selectedBy = None
    )

  def makePick(preset: OpeningPreset, ownerIndex: Int): SeriesOpening =
    fromPreset(preset, OpeningSource.Pick, ownerIndex)

  def makeBan(preset: OpeningPreset, ownerIndex: Int): SeriesOpening =
    fromPreset(preset, OpeningSource.Ban, ownerIndex)

enum OpeningSource:
  case Pick
  case Ban

enum SelectionMethod:
  case LoserChoice
  case SystemRandom
  case Timeout

opaque type SeriesOpeningId = String
object SeriesOpeningId extends lila.core.OpaqueString[SeriesOpeningId]
