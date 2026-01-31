package lila.series

import chess.format.Fen
import chess.{ ByColor, Color }
import reactivemongo.api.bson.*

import lila.db.BSON
import lila.db.dsl.{ *, given }
import lila.core.id.{ GameId, SeriesId }

object BsonHandlers:

  given BSONHandler[chess.variant.Variant] = variantByKeyHandler
  given BSONHandler[chess.Clock.Config] = clockConfigHandler

  given BSONHandler[OpeningPreset] = new BSONHandler[OpeningPreset]:
    def readTry(bson: BSONValue) = bson match
      case doc: BSONDocument =>
        for
          name <- doc.getAsTry[String]("n")
          fen <- doc.getAsTry[String]("f")
          url <- doc.getAsTry[String]("u")
        yield OpeningPreset(name, Fen.Full(fen), url)
      case _ => scala.util.Failure(new Exception("Expected BSONDocument for OpeningPreset"))

    def writeTry(op: OpeningPreset) = scala.util.Success(BSONDocument(
      "n" -> op.name,
      "f" -> op.fen.value,
      "u" -> op.url
    ))

  given BSONHandler[Series.Status] = lila.db.dsl.quickHandler(
    { case BSONInteger(id) => Series.Status(id).getOrElse(Series.Status.Created) },
    { s => BSONInteger(s.id) }
  )

  given BSONHandler[Series.Phase] = lila.db.dsl.quickHandler(
    { case BSONInteger(id) => Series.Phase(id).getOrElse(Series.Phase.Picking) },
    { p => BSONInteger(p.id) }
  )

  // results 필드용: Option[Color] -> Option[Boolean] (white=true, black=false, draw=None)
  private def readResults(list: List[BSONValue]): List[Option[Color]] =
    list.map:
      case BSONBoolean(true) => Some(Color.White)
      case BSONBoolean(false) => Some(Color.Black)
      case _ => None

  private def writeResults(results: List[Option[Color]]): List[BSONValue] =
    results.map:
      case Some(Color.White) => BSONBoolean(true)
      case Some(Color.Black) => BSONBoolean(false)
      case None => BSONNull

  given BSON[Series] with
    def reads(r: BSON.Reader) =
      val playersList = r.get[List[UserId]]("p")
      val scoresList = r.get[List[Int]]("sc")
      val resultsList = r.getO[List[BSONValue]]("rs").getOrElse(Nil)
      val picksList = r.getO[List[List[OpeningPreset]]]("pk").getOrElse(List(Nil, Nil))
      val bansList = r.getO[List[List[OpeningPreset]]]("bn").getOrElse(List(Nil, Nil))
      Series(
        id = r.get[SeriesId]("_id"),
        players = ByColor(playersList.head, playersList.last),
        scores = ByColor(scoresList.headOption.getOrElse(0), scoresList.lastOption.getOrElse(0)),
        gameIds = r.get[List[GameId]]("g"),
        results = readResults(resultsList),
        currentRound = r.get[Int]("r"),
        status = r.get[Series.Status]("s"),
        phase = r.getO[Series.Phase]("ph").getOrElse(Series.Phase.Picking),
        picks = ByColor(picksList.headOption.getOrElse(Nil), picksList.lastOption.getOrElse(Nil)),
        bans = ByColor(bansList.headOption.getOrElse(Nil), bansList.lastOption.getOrElse(Nil)),
        winner = r.getO[Boolean]("w").map(Color.fromWhite),
        variant = r.get[chess.variant.Variant]("v"),
        clock = r.get[chess.Clock.Config]("c"),
        openings = r.getO[List[OpeningPreset]]("op").getOrElse(Nil),
        createdAt = r.get[Instant]("ca"),
        finishedAt = r.getO[Instant]("fa")
      )
    def writes(w: BSON.Writer, o: Series) =
      $doc(
        "_id" -> o.id,
        "p" -> List(o.players.white, o.players.black),
        "sc" -> List(o.scores.white, o.scores.black),
        "g" -> o.gameIds,
        "rs" -> writeResults(o.results),
        "r" -> o.currentRound,
        "s" -> o.status,
        "ph" -> o.phase,
        "pk" -> List(o.picks.white, o.picks.black),
        "bn" -> List(o.bans.white, o.bans.black),
        "w" -> o.winner.map(_.white),
        "v" -> o.variant,
        "c" -> o.clock,
        "op" -> o.openings,
        "ca" -> o.createdAt,
        "fa" -> o.finishedAt
      )
