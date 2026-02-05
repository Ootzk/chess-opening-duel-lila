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
  def isNeutral: Boolean = source == OpeningSource.Neutral

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

  /** 중립 오프닝 (일반 게임) 생성 - ownerIndex는 -1 */
  def makeNeutral(): SeriesOpening =
    SeriesOpening(
      id = SeriesOpeningId(UUID.randomUUID().toString.take(12)),
      name = "Standard Game",
      fen = Fen.Full("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"),
      url = None,
      source = OpeningSource.Neutral,
      ownerIndex = -1,
      usedInRound = None,
      selectedBy = None
    )

enum OpeningSource:
  case Pick
  case Ban
  case Neutral

enum SelectionMethod:
  case LoserChoice
  case SystemRandom
  case Timeout

opaque type SeriesOpeningId = String
object SeriesOpeningId extends lila.core.OpaqueString[SeriesOpeningId]
