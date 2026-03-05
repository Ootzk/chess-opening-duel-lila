package lila.opening

import chess.IntRating
import chess.format.{ Fen, StandardFen }
import chess.format.pgn.SanStr
import reactivemongo.api.bson.*

import lila.db.dsl.{ *, given }
import lila.memo.CacheApi
import lila.memo.CacheApi.invalidate

final class OpeningExplorerCache(coll: Coll, cacheApi: CacheApi)(using Executor):

  import OpeningExplorer.*
  import OpeningExplorerCache.*

  private case class CachedEntry(position: Position, updatedAt: Instant)

  private val cache = cacheApi[StandardFen, Option[CachedEntry]](512, "opening.explorerCache"):
    _.maximumSize(1024).expireAfterWrite(1.hour).buildAsyncFuture(readFromDb)

  def getPosition(fen: StandardFen): Fu[Option[Position]] =
    cache.get(fen).map(_.map(_.position))

  def getFreshPosition(fen: StandardFen, maxAge: FiniteDuration): Fu[Option[Position]] =
    cache.get(fen).map:
      _.filter(_.updatedAt.isAfter(nowInstant.minusSeconds(maxAge.toSeconds))).map(_.position)

  def putPosition(fen: StandardFen, pos: Position): Funit =
    coll.update
      .one(
        $id(fen.toString),
        positionToDoc(fen, pos),
        upsert = true
      )
      .void
      .andDo(cache.put(fen, fuccess(CachedEntry(pos, nowInstant).some)))

  def getStats(fen: Fen.Full): Fu[Option[Stats]] =
    getPosition(fen.opening).map(_.map(p => Stats(p.white, p.draws, p.black)))

  def putStats(fen: Fen.Full, stats: Stats): Funit =
    val key = fen.opening
    coll.update
      .one(
        $id(key.toString),
        $set("w" -> stats.white, "d" -> stats.draws, "b" -> stats.black, "ua" -> nowInstant),
        upsert = true
      )
      .void
      .andDo(cache.invalidate(key))

  def isEmpty: Fu[Boolean] =
    coll.countAll.map(_ == 0)

  def refreshStale(explorer: OpeningExplorer): Funit =
    coll
      .find($doc("ua".$lt(nowInstant.minusDays(7))))
      .sort($sort.asc("ua"))
      .cursor[Bdoc]()
      .list(50)
      .flatMap: docs =>
        docs
          .flatMap(_.getAsOpt[String]("_id"))
          .traverse_ : fenStr =>
            val fen = Fen.Full(s"$fenStr w KQkq - 0 1")
            explorer
              .statsByFen(fen)
              .flatMap:
                case Some(stats) => putStats(fen, stats)
                case None        => funit

  def seedPresets(explorer: OpeningExplorer): Funit =
    presetFens.traverse_ : fen =>
      explorer
        .statsByFen(fen)
        .flatMap:
          case Some(stats) => putStats(fen, stats)
          case None        => funit

  private def readFromDb(fen: StandardFen): Fu[Option[CachedEntry]] =
    coll.one[Bdoc]($id(fen.toString)).map:
      _.flatMap: doc =>
        docToPosition(doc).map: pos =>
          CachedEntry(pos, doc.getAsOpt[Instant]("ua").getOrElse(nowInstant))

private object OpeningExplorerCache:

  import OpeningExplorer.*

  // 10 preset opening FENs (from series/OpeningPresets.scala)
  val presetFens: Vector[Fen.Full] = Vector(
    "r1bq1rk1/ppp2ppp/2np1n2/2b1p3/2B1P3/2PP1N2/PP1N1PPP/R1BQ1RK1 b - - 3 7",
    "r1bq1rk1/2p1bppp/p1n2n2/1p1pp3/4P3/1BP2N2/PP1P1PPP/RNBQR1K1 w - - 0 9",
    "rnbqkb1r/ppp2ppp/4pn2/3p4/2PP4/2N5/PP2PPPP/R1BQKBNR w KQkq - 2 4",
    "rnbqkb1r/1p2pppp/p2p1n2/8/3NP3/2N5/PPP2PPP/R1BQKB1R w KQkq - 0 6",
    "rnbq1rk1/ppp1bppp/4pn2/8/2pP4/5NP1/PP2PPBP/RNBQ1RK1 w - - 0 7",
    "rnbqk2r/pppp1ppp/4pn2/8/1bPP4/2N5/PP2PPPP/R1BQKBNR w KQkq - 2 4",
    "r1bqkbnr/pppp1ppp/2n5/8/3NP3/8/PPP2PPP/RNBQKB1R b KQkq - 0 4",
    "r2qkbnr/pp1nppp1/2p3bp/8/3P3P/5NN1/PPP2PP1/R1BQKB1R w KQkq - 2 8",
    "rnbqkb1r/pppp1ppp/5n2/4p3/2P5/2N5/PP1PPPPP/R1BQKBNR w KQkq - 2 3",
    "rnbqk1nr/pp3ppp/4p3/2ppP3/3P4/P1P5/2P2PPP/R1BQKBNR b KQkq - 0 6"
  ).map(Fen.Full(_))

  def positionToDoc(fen: StandardFen, pos: Position): Bdoc = $doc(
    "_id" -> fen.toString,
    "w"   -> pos.white,
    "d"   -> pos.draws,
    "b"   -> pos.black,
    "moves" -> pos.moves.map: m =>
      $doc("u" -> m.uci, "s" -> m.san.toString, "r" -> m.averageRating.value, "w" -> m.white, "d" -> m.draws, "b" -> m.black),
    "history" -> pos.history.map: s =>
      $doc("w" -> s.white, "d" -> s.draws, "b" -> s.black),
    "ua" -> nowInstant
  )

  def docToPosition(doc: Bdoc): Option[Position] = for
    w <- doc.getAsOpt[Long]("w")
    d <- doc.getAsOpt[Long]("d")
    b <- doc.getAsOpt[Long]("b")
  yield Position(
    white = w,
    draws = d,
    black = b,
    moves = ~doc.getAsOpt[List[Bdoc]]("moves").map(_.flatMap(docToMove)),
    topGames = Nil,
    recentGames = Nil,
    history = ~doc.getAsOpt[List[Bdoc]]("history").map(_.flatMap(docToStats))
  )

  private def docToMove(doc: Bdoc): Option[Move] = for
    uci <- doc.getAsOpt[String]("u")
    san <- doc.getAsOpt[String]("s")
    r   <- doc.getAsOpt[Int]("r")
    w   <- doc.getAsOpt[Long]("w")
    d   <- doc.getAsOpt[Long]("d")
    b   <- doc.getAsOpt[Long]("b")
  yield Move(uci, SanStr(san), IntRating(r), w, d, b)

  private def docToStats(doc: Bdoc): Option[Stats] = for
    w <- doc.getAsOpt[Long]("w")
    d <- doc.getAsOpt[Long]("d")
    b <- doc.getAsOpt[Long]("b")
  yield Stats(w, d, b)
