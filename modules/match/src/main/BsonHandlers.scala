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

  given BSONHandler[Match.Status] = lila.db.dsl.quickHandler(
    { case BSONInteger(id) => Match.Status(id).getOrElse(Match.Status.Created) },
    { s => BSONInteger(s.id) }
  )

  given BSON[Match] with
    def reads(r: BSON.Reader) =
      val playersList = r.get[List[UserId]]("p")
      val scoresList = r.get[List[Int]]("sc")
      Match(
        id = r.get[MatchId]("_id"),
        players = ByColor(playersList.head, playersList.last),
        scores = ByColor(scoresList.headOption.getOrElse(0), scoresList.lastOption.getOrElse(0)),
        gameIds = r.get[List[GameId]]("g"),
        currentRound = r.get[Int]("r"),
        status = r.get[Match.Status]("s"),
        winner = r.getO[Boolean]("w").map(Color.fromWhite),
        variant = r.get[chess.variant.Variant]("v"),
        clock = r.get[chess.Clock.Config]("c"),
        initialFen = r.getO[Fen.Full]("f"),
        createdAt = r.get[Instant]("ca"),
        finishedAt = r.getO[Instant]("fa")
      )
    def writes(w: BSON.Writer, o: Match) =
      $doc(
        "_id" -> o.id,
        "p" -> List(o.players.white, o.players.black),
        "sc" -> List(o.scores.white, o.scores.black),
        "g" -> o.gameIds,
        "r" -> o.currentRound,
        "s" -> o.status,
        "w" -> o.winner.map(_.isWhite),
        "v" -> o.variant,
        "c" -> o.clock,
        "f" -> o.initialFen,
        "ca" -> o.createdAt,
        "fa" -> o.finishedAt
      )