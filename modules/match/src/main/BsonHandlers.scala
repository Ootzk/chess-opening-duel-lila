package lila.`match`

import chess.format.Fen
import chess.{ ByColor, Color }
import reactivemongo.api.bson.*

import lila.db.BSON
import lila.db.dsl.{ *, given }
import lila.core.id.{ GameId, MatchId }

object BsonHandlers:

  given BSONHandler[chess.variant.Variant] = variantByKeyHandler
  given BSONHandler[chess.Clock.Config] = clockConfigHandler

  given BSONHandler[OpeningPreset] = new BSONHandler[OpeningPreset]:
    def readTry(bson: BSONValue) = bson match
      case doc: BSONDocument =>
        for
          name <- doc.getAsTry[String]("n")
          fen <- doc.getAsTry[String]("f")
        yield OpeningPreset(name, Fen.Full(fen))
      case _ => scala.util.Failure(new Exception("Expected BSONDocument for OpeningPreset"))

    def writeTry(op: OpeningPreset) = scala.util.Success(BSONDocument(
      "n" -> op.name,
      "f" -> op.fen.value
    ))

  given BSONHandler[Match.Status] = lila.db.dsl.quickHandler(
    { case BSONInteger(id) => Match.Status(id).getOrElse(Match.Status.Created) },
    { s => BSONInteger(s.id) }
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

  given BSON[Match] with
    def reads(r: BSON.Reader) =
      val playersList = r.get[List[UserId]]("p")
      val scoresList = r.get[List[Int]]("sc")
      val resultsList = r.getO[List[BSONValue]]("rs").getOrElse(Nil)
      Match(
        id = r.get[MatchId]("_id"),
        players = ByColor(playersList.head, playersList.last),
        scores = ByColor(scoresList.headOption.getOrElse(0), scoresList.lastOption.getOrElse(0)),
        gameIds = r.get[List[GameId]]("g"),
        results = readResults(resultsList),
        currentRound = r.get[Int]("r"),
        status = r.get[Match.Status]("s"),
        winner = r.getO[Boolean]("w").map(Color.fromWhite),
        variant = r.get[chess.variant.Variant]("v"),
        clock = r.get[chess.Clock.Config]("c"),
        openings = r.getO[List[OpeningPreset]]("op").getOrElse(Nil),
        createdAt = r.get[Instant]("ca"),
        finishedAt = r.getO[Instant]("fa")
      )
    def writes(w: BSON.Writer, o: Match) =
      $doc(
        "_id" -> o.id,
        "p" -> List(o.players.white, o.players.black),
        "sc" -> List(o.scores.white, o.scores.black),
        "g" -> o.gameIds,
        "rs" -> writeResults(o.results),
        "r" -> o.currentRound,
        "s" -> o.status,
        "w" -> o.winner.map(_.white),
        "v" -> o.variant,
        "c" -> o.clock,
        "op" -> o.openings,
        "ca" -> o.createdAt,
        "fa" -> o.finishedAt
      )