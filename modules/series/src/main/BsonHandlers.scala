package lila.series

import chess.format.Fen
import reactivemongo.api.bson.*

import lila.db.BSON
import lila.db.dsl.{ *, given }
import lila.core.id.{ GameId, SeriesId }

object BsonHandlers:

  given BSONHandler[chess.variant.Variant] = variantByKeyHandler
  given BSONHandler[chess.Clock.Config] = clockConfigHandler

  // OpeningPreset
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

  // OpeningSource
  given BSONHandler[OpeningSource] = quickHandler[OpeningSource](
    { case BSONString("pick") => OpeningSource.Pick
      case BSONString("neutral") => OpeningSource.Neutral
      case _ => OpeningSource.Ban },
    { case OpeningSource.Pick => BSONString("pick")
      case OpeningSource.Ban => BSONString("ban")
      case OpeningSource.Neutral => BSONString("neutral") }
  )

  // SelectionMethod
  given BSONHandler[SelectionMethod] = quickHandler[SelectionMethod](
    { case BSONString("loser") => SelectionMethod.LoserChoice
      case BSONString("random") => SelectionMethod.SystemRandom
      case _ => SelectionMethod.Timeout },
    { case SelectionMethod.LoserChoice => BSONString("loser")
      case SelectionMethod.SystemRandom => BSONString("random")
      case SelectionMethod.Timeout => BSONString("timeout") }
  )

  // SeriesOpeningId
  given BSONHandler[SeriesOpeningId] = stringAnyValHandler(_.value, SeriesOpeningId(_))

  // SeriesOpening
  given BSONDocumentHandler[SeriesOpening] = new BSONDocumentHandler[SeriesOpening]:
    def readDocument(doc: BSONDocument) =
      for
        id <- doc.getAsTry[SeriesOpeningId]("i")
        name <- doc.getAsTry[String]("n")
        fen <- doc.getAsTry[String]("f")
        url = doc.getAsOpt[String]("u")
        source <- doc.getAsTry[OpeningSource]("s")
        ownerIndex <- doc.getAsTry[Int]("o")
        usedInRound = doc.getAsOpt[Int]("r")
        selectedBy = doc.getAsOpt[SelectionMethod]("m")
      yield SeriesOpening(id, name, Fen.Full(fen), url, source, ownerIndex, usedInRound, selectedBy)

    def writeTry(o: SeriesOpening) = scala.util.Success($doc(
      "i" -> o.id,
      "n" -> o.name,
      "f" -> o.fen.value,
      "u" -> o.url,
      "s" -> o.source,
      "o" -> o.ownerIndex,
      "r" -> o.usedInRound,
      "m" -> o.selectedBy
    ))

  // SeriesPlayer
  given BSONDocumentHandler[SeriesPlayer] = new BSONDocumentHandler[SeriesPlayer]:
    def readDocument(doc: BSONDocument) =
      for
        userId <- doc.getAsTry[UserId]("u")
        index <- doc.getAsTry[Int]("i")
        score = doc.getAsOpt[Int]("s").getOrElse(0)
        confirmedPicks = doc.getAsOpt[Boolean]("cp").getOrElse(false)
        confirmedBans = doc.getAsOpt[Boolean]("cb").getOrElse(false)
        confirmedSelecting = doc.getAsOpt[Boolean]("cs").getOrElse(false)
        selectingPick = doc.getAsOpt[String]("sp")
        lastSeenAt = doc.getAsOpt[Instant]("ls")
      yield SeriesPlayer(userId, index, score, confirmedPicks, confirmedBans, confirmedSelecting, selectingPick, lastSeenAt)

    def writeTry(p: SeriesPlayer) = scala.util.Success($doc(
      "u"  -> p.userId,
      "i"  -> p.index,
      "s"  -> p.score,
      "cp" -> p.confirmedPicks,
      "cb" -> p.confirmedBans,
      "cs" -> p.confirmedSelecting,
      "sp" -> p.selectingPick,
      "ls" -> p.lastSeenAt
    ))

  // GameResult
  given BSONHandler[GameResult] = quickHandler[GameResult](
    { case BSONInteger(1) => GameResult.WhiteWins
      case BSONInteger(-1) => GameResult.BlackWins
      case _ => GameResult.Draw },
    { case GameResult.WhiteWins => BSONInteger(1)
      case GameResult.BlackWins => BSONInteger(-1)
      case GameResult.Draw => BSONInteger(0) }
  )

  // SeriesGame
  given BSONDocumentHandler[SeriesGame] = new BSONDocumentHandler[SeriesGame]:
    def readDocument(doc: BSONDocument) =
      for
        gameId <- doc.getAsTry[GameId]("g")
        round <- doc.getAsTry[Int]("r")
        openingId <- doc.getAsTry[SeriesOpeningId]("o")
        whitePlayerIndex <- doc.getAsTry[Int]("w")
        result = doc.getAsOpt[GameResult]("rs")
      yield SeriesGame(gameId, round, openingId, whitePlayerIndex, result)

    def writeTry(g: SeriesGame) = scala.util.Success($doc(
      "g" -> g.gameId,
      "r" -> g.round,
      "o" -> g.openingId,
      "w" -> g.whitePlayerIndex,
      "rs" -> g.result
    ))

  // Series.Status
  given BSONHandler[Series.Status] = quickHandler(
    { case BSONInteger(id) => Series.Status(id).getOrElse(Series.Status.Created) },
    { s => BSONInteger(s.id) }
  )

  // Series.Phase
  given BSONHandler[Series.Phase] = quickHandler(
    { case BSONInteger(id) => Series.Phase(id).getOrElse(Series.Phase.Picking) },
    { p => BSONInteger(p.id) }
  )

  // Series
  given BSON[Series] with
    def reads(r: BSON.Reader) =
      val player0 = r.get[SeriesPlayer]("p0")
      val player1 = r.get[SeriesPlayer]("p1")
      Series(
        id = r.get[SeriesId]("_id"),
        players = (player0, player1),
        openings = r.getO[List[SeriesOpening]]("op").getOrElse(Nil),
        games = r.getO[List[SeriesGame]]("gm").getOrElse(Nil),
        phase = r.get[Series.Phase]("ph"),
        phaseStartedAt = r.getO[Instant]("psa"),
        status = r.get[Series.Status]("st"),
        variant = r.get[chess.variant.Variant]("va"),
        clock = r.get[chess.Clock.Config]("cl"),
        createdAt = r.get[Instant]("ca"),
        finishedAt = r.getO[Instant]("fa"),
        forfeitBy = r.getO[Int]("fb"),
        rematchOfferedBy = r.getO[Int]("ro")
      )

    def writes(w: BSON.Writer, s: Series) = $doc(
      "_id" -> s.id,
      "p0" -> s.players._1,
      "p1" -> s.players._2,
      "op" -> s.openings,
      "gm" -> s.games,
      "ph" -> s.phase,
      "psa" -> s.phaseStartedAt,
      "st" -> s.status,
      "va" -> s.variant,
      "cl" -> s.clock,
      "ca" -> s.createdAt,
      "fa" -> s.finishedAt,
      "fb" -> s.forfeitBy,
      "ro" -> s.rematchOfferedBy
    )
