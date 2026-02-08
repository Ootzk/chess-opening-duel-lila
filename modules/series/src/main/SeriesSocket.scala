package lila.series

import play.api.libs.json.*

import lila.core.socket.{ protocol as P, * }
import lila.room.RoomSocket.{ Protocol as RP, * }
import lila.core.id.SeriesId

final class SeriesSocket(
    api: SeriesApi,
    jsonView: SeriesJson,
    socketKit: SocketKit
)(using Executor):

  import SeriesSocket.*

  lazy val rooms: RoomsMap = makeRoomMap(send)

  /** Notify all clients to reload series state */
  def reload(seriesId: SeriesId): Unit =
    rooms.tell(seriesId.into(RoomId), NotifyVersion("reload", JsNull))

  /** Notify when a player confirms or cancels their picks/bans */
  def notifyConfirmed(seriesId: SeriesId, playerIndex: Int, phase: String, confirmed: Boolean = true): Unit =
    rooms.tell(
      seriesId.into(RoomId),
      NotifyVersion(
        "confirmed",
        Json.obj(
          "player"    -> playerIndex,
          "phase"     -> phase,
          "confirmed" -> confirmed
        )
      )
    )

  /** Notify when phase changes */
  def notifyPhase(seriesId: SeriesId, phaseId: Int, gameId: Option[lila.core.id.GameId]): Unit =
    rooms.tell(
      seriesId.into(RoomId),
      NotifyVersion(
        "phase",
        Json.obj(
          "phase"  -> phaseId,
          "gameId" -> gameId.map(_.value)
        )
      )
    )

  /** Notify when series is aborted */
  def notifyAborted(seriesId: SeriesId): Unit =
    rooms.tell(seriesId.into(RoomId), NotifyVersion("aborted", JsNull))

  /** Notify rematch offer to the room */
  def notifyRematchOffer(seriesId: SeriesId, playerIndex: Int): Unit =
    rooms.tell(
      seriesId.into(RoomId),
      NotifyVersion(
        "rematchOffer",
        Json.obj("player" -> playerIndex)
      )
    )

  /** Notify rematch taken (new series created) */
  def notifyRematchTaken(seriesId: SeriesId, newSeriesId: SeriesId): Unit =
    rooms.tell(
      seriesId.into(RoomId),
      NotifyVersion(
        "rematchTaken",
        Json.obj("newSeriesId" -> newSeriesId.value)
      )
    )

  /** Notify player gone/back status to the room */
  def notifyGone(seriesId: SeriesId, playerIndex: Int, gone: Boolean): Unit =
    rooms.tell(
      seriesId.into(RoomId),
      NotifyVersion(
        "gone",
        Json.obj(
          "player" -> playerIndex,
          "gone"   -> gone
        )
      )
    )

  private lazy val send: SocketSend = socketKit.send("series-out")

  private lazy val seriesHandler: SocketHandler =
    case Protocol.In.Pings(pairs) =>
      pairs.foreach((id, idx) => api.ping(id, idx))
    case Protocol.In.PlayerGone(seriesId, playerIndex, gone) =>
      api.setPlayerGone(seriesId, playerIndex, gone)

  socketKit.subscribe("series-in", Protocol.In.reader.orElse(RP.In.reader))(
    seriesHandler
      .orElse(minRoomHandler(rooms, logger))
      .orElse(socketKit.baseHandler)
  )

  api.registerSocket(this)

object SeriesSocket:

  given Conversion[SeriesId, RoomId] = _.into(RoomId)

  object Protocol:

    object In:

      case class Pings(pairs: Iterable[(SeriesId, Int)]) extends P.In

      case class PlayerGone(seriesId: SeriesId, playerIndex: Int, gone: Boolean) extends P.In

      val reader: P.In.Reader =
        case P.RawMsg("series/ping", raw) =>
          Pings(
            P.In.commas(raw.args).flatMap: s =>
              s.split(':') match
                case Array(id, idx) => idx.toIntOption.map(i => (SeriesId(id), i))
                case _              => None
          ).some
        case P.RawMsg("series/gone", raw) =>
          raw.args.split(' ') match
            case Array(id, idx, gone) =>
              idx.toIntOption.map: i =>
                PlayerGone(SeriesId(id), i, gone == "+")
            case _ => None
