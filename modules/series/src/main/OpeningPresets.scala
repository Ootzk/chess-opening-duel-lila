package lila.series

import chess.format.Fen

case class OpeningPreset(name: String, fen: Fen.Full)

object OpeningPresets:
  // 10개 오프닝 프리셋
  val all: Vector[OpeningPreset] = Vector(
    // 1. Ruy Lopez, Marshall Attack
    // 1.e4 e5 2.Nf3 Nc6 3.Bb5 a6 4.Ba4 Nf6 5.O-O Be7 6.Re1 b5 7.Bb3 O-O 8.c3 d5
    // Black sacrifices a pawn for dynamic counterplay
    OpeningPreset(
      "Ruy Lopez, Marshall Attack",
      Fen.Full("r1bq1rk1/2p1bppp/p1n2n2/1p1pp3/4P3/1BP2N2/PP1P1PPP/RNBQR1K1 w - - 0 9")
    ),
    // 2. Italian Game, Giuoco Pianissimo
    // 1.e4 e5 2.Nf3 Nc6 3.Bc4 Bc5 4.c3 Nf6 5.d3 d6 6.O-O O-O 7.Nbd2
    // A slow, solid buildup aiming for long-term positional pressure
    OpeningPreset(
      "Italian Game, Giuoco Pianissimo",
      Fen.Full("r1bq1rk1/ppp2ppp/2np1n2/2b1p3/2B1P3/2PP1N2/PP1N1PPP/R1BQ1RK1 b - - 3 7")
    ),
    // 3. Queen's Gambit, Carlsbad Structure
    // 1.d4 d5 2.c4 e6 3.Nc3 Nf6 4.Bg5 Be7 5.e3 O-O 6.Nf3 Nbd7 7.cxd5 exd5
    // Famous for the minority attack strategy
    OpeningPreset(
      "Queen's Gambit, Carlsbad",
      Fen.Full("r1bq1rk1/pppnbppp/5n2/3p2B1/3P4/2N1PN2/PP3PPP/R2QKB1R w KQ - 0 8")
    ),
    // 4. Catalan Opening, Main Line
    // 1.d4 Nf6 2.c4 e6 3.g3 d5 4.Bg2 Be7 5.Nf3 O-O 6.O-O dxc4
    // Utilizes the fianchettoed bishop's long diagonal pressure
    OpeningPreset(
      "Catalan Opening",
      Fen.Full("rnbq1rk1/ppp1bppp/4pn2/8/2pP4/5NP1/PP2PPBP/RNBQ1RK1 w - - 0 7")
    ),
    // 5. English Opening, Botvinnik System
    // 1.c4 e5 2.Nc3 Nc6 3.g3 g6 4.Bg2 Bg7 5.e4 d6 6.Nge2
    // Hypermodern structure with flexible development
    OpeningPreset(
      "English, Botvinnik",
      Fen.Full("r1bqk1nr/ppp2pbp/2np2p1/4p3/2P1P3/2N3P1/PP1PNPBP/R1BQK2R b KQkq - 1 6")
    ),
    // 6. Sicilian Najdorf
    // 1.e4 c5 2.Nf3 d6 3.d4 cxd4 4.Nxd4 Nf6 5.Nc3 a6
    // Most heavily analyzed; Black prepares active counterplay
    OpeningPreset(
      "Sicilian Najdorf",
      Fen.Full("rnbqkb1r/1p2pppp/p2p1n2/8/3NP3/2N5/PPP2PPP/R1BQKB1R w KQkq - 0 6")
    ),
    // 7. King's Indian, Mar del Plata
    // 1.d4 Nf6 2.c4 g6 3.Nc3 Bg7 4.e4 d6 5.Nf3 O-O 6.Be2 e5 7.O-O Nc6 8.d5 Ne7
    // Sharp middlegame with opposite-side attacks
    OpeningPreset(
      "King's Indian, Mar del Plata",
      Fen.Full("r1bq1rk1/ppp1npbp/3p1np1/3Pp3/2P1P3/2N2N2/PP2BPPP/R1BQ1RK1 w - - 1 9")
    ),
    // 8. Grünfeld, Exchange Variation
    // 1.d4 Nf6 2.c4 g6 3.Nc3 d5 4.cxd5 Nxd5 5.e4 Nxc3 6.bxc3 Bg7
    // Black pressures the center with fianchettoed bishop
    OpeningPreset(
      "Grünfeld Exchange",
      Fen.Full("rnbqk2r/ppp1ppbp/6p1/8/3PP3/2P5/P4PPP/R1BQKBNR w KQkq - 1 7")
    ),
    // 9. Caro-Kann, Classical Variation
    // 1.e4 c6 2.d4 d5 3.Nc3 dxe4 4.Nxe4 Bf5 5.Ng3 Bg6 6.h4 h6 7.Nf3 Nd7
    // Solid positional defense with counterattacking potential
    OpeningPreset(
      "Caro-Kann Classical",
      Fen.Full("r2qkbnr/pp1nppp1/2p3bp/8/3P3P/5NN1/PPP2PP1/R1BQKB1R w KQkq - 2 8")
    ),
    // 10. French, Winawer Variation
    // 1.e4 e6 2.d4 d5 3.Nc3 Bb4 4.e5 c5 5.a3 Bxc3+ 6.bxc3
    // Creates imbalanced pawn structures, complex middlegames
    OpeningPreset(
      "French Winawer",
      Fen.Full("rnbqk1nr/pp3ppp/4p3/2ppP3/3P4/P1P5/2P2PPP/R1BQKBNR b KQkq - 0 6")
    )
  )

  def randomN(n: Int): List[OpeningPreset] =
    scala.util.Random.shuffle(all).take(n).toList
