package lila.series

import chess.format.Fen

case class OpeningPreset(name: String, fen: Fen.Full, url: String, ownerColor: chess.Color)

object OpeningPresets:
  // 오프닝 이름을 lichess /opening URL로 변환 (공백→_, 콜론 제거)
  private def nameToUrl(name: String): String =
    s"https://lichess.org/opening/${name.replace(": ", "_").replace(" ", "_")}"

  // 10개 오프닝 프리셋 (Opening Explorer API에서 ECO 코드 확인됨)
  val all: Vector[OpeningPreset] = Vector(
    // C89 - Ruy Lopez: Marshall Attack
    // 1.e4 e5 2.Nf3 Nc6 3.Bb5 a6 4.Ba4 Nf6 5.O-O Be7 6.Re1 b5 7.Bb3 O-O 8.c3 d5
    OpeningPreset(
      "Ruy Lopez: Marshall Attack",
      Fen.Full("r1bq1rk1/2p1bppp/p1n2n2/1p1pp3/4P3/1BP2N2/PP1P1PPP/RNBQR1K1 w - - 0 9"),
      nameToUrl("Ruy Lopez: Marshall Attack"),
      chess.Color.Black
    ),
    // C54 - Italian Game: Classical Variation, Giuoco Pianissimo
    // 1.e4 e5 2.Nf3 Nc6 3.Bc4 Bc5 4.c3 Nf6 5.d3 d6 6.O-O O-O 7.Nbd2
    OpeningPreset(
      "Italian Game: Classical Variation, Giuoco Pianissimo",
      Fen.Full("r1bq1rk1/ppp2ppp/2np1n2/2b1p3/2B1P3/2PP1N2/PP1N1PPP/R1BQ1RK1 b - - 3 7"),
      nameToUrl("Italian Game: Classical Variation, Giuoco Pianissimo"),
      chess.Color.White
    ),
    // D35 - Queen's Gambit Declined: Normal Defense
    // 1.d4 d5 2.c4 e6 3.Nc3 Nf6
    OpeningPreset(
      "Queen's Gambit Declined: Normal Defense",
      Fen.Full("rnbqkb1r/ppp1pppp/4pn2/3p4/2PP4/2N5/PP2PPPP/R1BQKBNR w KQkq - 0 4"),
      nameToUrl("Queen's Gambit Declined: Normal Defense"),
      chess.Color.White
    ),
    // E05 - Catalan Opening: Open Defense, Classical Line
    // 1.d4 Nf6 2.c4 e6 3.g3 d5 4.Bg2 Be7 5.Nf3 O-O 6.O-O dxc4
    OpeningPreset(
      "Catalan Opening: Open Defense, Classical Line",
      Fen.Full("rnbq1rk1/ppp1bppp/4pn2/8/2pP4/5NP1/PP2PPBP/RNBQ1RK1 w - - 0 7"),
      nameToUrl("Catalan Opening: Open Defense, Classical Line"),
      chess.Color.White
    ),
    // A22 - English Opening: King's English Variation, Two Knights Variation
    // 1.c4 e5 2.Nc3 Nf6
    OpeningPreset(
      "English Opening: King's English Variation, Two Knights Variation",
      Fen.Full("rnbqkb1r/pppp1ppp/5n2/4p3/2P5/2N5/PP1PPPPP/R1BQKBNR w KQkq - 2 3"),
      nameToUrl("English Opening: King's English Variation, Two Knights Variation"),
      chess.Color.White
    ),
    // B90 - Sicilian Defense: Najdorf Variation
    // 1.e4 c5 2.Nf3 d6 3.d4 cxd4 4.Nxd4 Nf6 5.Nc3 a6
    OpeningPreset(
      "Sicilian Defense: Najdorf Variation",
      Fen.Full("rnbqkb1r/1p2pppp/p2p1n2/8/3NP3/2N5/PPP2PPP/R1BQKB1R w KQkq - 0 6"),
      nameToUrl("Sicilian Defense: Najdorf Variation"),
      chess.Color.Black
    ),
    // E20 - Nimzo-Indian Defense
    // 1.d4 Nf6 2.c4 e6 3.Nc3 Bb4
    OpeningPreset(
      "Nimzo-Indian Defense",
      Fen.Full("rnbqk2r/pppp1ppp/4pn2/8/1bPP4/2N5/PP2PPPP/R1BQKBNR w KQkq - 2 4"),
      nameToUrl("Nimzo-Indian Defense"),
      chess.Color.Black
    ),
    // A56 - Benoni Defense
    // 1.d4 Nf6 2.c4 c5
    OpeningPreset(
      "Benoni Defense",
      Fen.Full("rnbqkb1r/pp1ppppp/5n2/2p5/2PP4/8/PP2PPPP/RNBQKBNR w KQkq - 0 3"),
      nameToUrl("Benoni Defense"),
      chess.Color.Black
    ),
    // B19 - Caro-Kann Defense: Classical Variation
    // 1.e4 c6 2.d4 d5 3.Nc3 dxe4 4.Nxe4 Bf5 5.Ng3 Bg6 6.h4 h6 7.Nf3 Nd7
    OpeningPreset(
      "Caro-Kann Defense: Classical Variation",
      Fen.Full("r2qkbnr/pp1nppp1/2p3bp/8/3P3P/5NN1/PPP2PP1/R1BQKB1R w KQkq - 2 8"),
      nameToUrl("Caro-Kann Defense: Classical Variation"),
      chess.Color.Black
    ),
    // C18 - French Defense: Winawer Variation
    // 1.e4 e6 2.d4 d5 3.Nc3 Bb4 4.e5 c5 5.a3 Bxc3+ 6.bxc3
    OpeningPreset(
      "French Defense: Winawer Variation",
      Fen.Full("rnbqk1nr/pp3ppp/4p3/2ppP3/3P4/P1P5/2P2PPP/R1BQKBNR b KQkq - 0 6"),
      nameToUrl("French Defense: Winawer Variation"),
      chess.Color.Black
    )
  )

  def randomN(n: Int): List[OpeningPreset] =
    scala.util.Random.shuffle(all).take(n).toList
