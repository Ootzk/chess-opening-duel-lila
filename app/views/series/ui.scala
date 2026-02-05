package views.series

import chess.Color

import lila.app.UiEnv.{ *, given }
import lila.series.{ Series, GameResult }
import lila.series.OpeningPreset
import lila.core.id.GameId
import lila.core.game.Game

object ui:

  // currentGameResult: 현재 게임이 끝났으면 그 결과 (winner의 playerIndex)
  def seriesScore(s: Series, currentGameId: Option[GameId], currentGameWinnerIndex: Option[Option[Int]] = None)(using ctx: Context) =
    // POV: 로그인한 유저 기준 playerIndex
    val povIndex = ctx.userId.flatMap(s.playerIndex).getOrElse(0)
    val oppIndex = 1 - povIndex

    // Sudden death 시 5경기 이상도 표시
    // 시리즈 종료 시에는 완료된 게임만, 진행 중에는 현재 라운드까지
    val finishedGames = s.games.count(_.result.isDefined)
    val displayRounds = if s.isFinished then math.max(Series.bestOf, finishedGames)
                        else math.max(Series.bestOf, s.currentRound)

    div(cls := "series-score")(
      table(cls := "series-score__table")(
        thead(
          tr(
            th(cls := "series-score__header-game")("Game"),
            th(cls := "series-score__header-me")(userIdLink(s.player(povIndex).userId.some, withOnline = true)),
            th(cls := "series-score__header-opp")(userIdLink(s.player(oppIndex).userId.some, withOnline = true)),
            th(cls := "series-score__header-opening")("Opening")
          )
        ),
        tbody(
          (1 to displayRounds).map: round =>
            val gameOpt = s.games.find(_.round == round)
            val gid = gameOpt.map(_.gameId)
            val openingOpt = gameOpt.flatMap(g => s.openings.find(_.id == g.openingId))

            // Series의 game.result를 먼저 확인, 없으면 현재 게임 결과 사용
            val winnerIndex: Option[Option[Int]] = gameOpt.flatMap(_.result) match
              case Some(result) =>
                val whiteIdx = gameOpt.map(_.whitePlayerIndex).getOrElse(0)
                result match
                  case GameResult.WhiteWins => Some(Some(whiteIdx))
                  case GameResult.BlackWins => Some(Some(1 - whiteIdx))
                  case GameResult.Draw => Some(None)
              case None =>
                // 현재 게임이면 currentGameWinnerIndex 사용
                gid.filter(currentGameId.contains).flatMap(_ => currentGameWinnerIndex)

            val isCurrent = gid.exists(currentGameId.contains)
            val isInProgress = winnerIndex.isEmpty && isCurrent
            val isCompleted = winnerIndex.isDefined

            tr(cls := List("series-score__row" -> true, "current" -> isCurrent))(
              td(cls := "series-score__game")(
                if isCompleted then
                  gid.fold(span(s"$round")): id =>
                    a(href := routes.Round.watcher(id, Color.white), cls := "game-link")(s"$round")
                else
                  span(s"$round")
              ),
              td(cls := "series-score__result")(
                resultCell(povIndex, winnerIndex, isInProgress, gid)
              ),
              td(cls := "series-score__result")(
                resultCell(oppIndex, winnerIndex, isInProgress, gid)
              ),
              td(cls := "series-score__opening")(
                openingOpt.fold(span("-")): op =>
                  a(href := op.url, target := "_blank", cls := "opening-link")(op.name)
              )
            )
        )
      ),
      // 현재 오프닝 라벨 (게임 번호 + 오프닝 이름)
      div(cls := "series-score__label")(
        s.openingForRound(s.currentRound).fold(
          s"Opening Duel - Game ${s.currentRound}"
        )(op => s"Opening Duel - Game ${s.currentRound}: ${op.name}")
      )
    )

  private def resultCell(
      playerIndex: Int,
      winnerIndex: Option[Option[Int]],
      isInProgress: Boolean,
      gid: Option[GameId]
  ) =
    if isInProgress then
      gid.fold(span(cls := "in-progress")("-")): id =>
        a(href := routes.Round.watcher(id, Color.white), cls := "in-progress")("-")
    else
      winnerIndex match
        case Some(winner) =>
          val (linkCls, text) = winner match
            case Some(w) if w == playerIndex => "win" -> "1"
            case None                         => "draw" -> "½"
            case Some(_)                      => "loss" -> "0"
          gid.fold(span(cls := linkCls)(text)): id =>
            a(href := routes.Round.watcher(id, Color.white), cls := linkCls)(text)
        case None =>
          span(cls := "pending")("-")

  // 외부에서 사용할 간단한 wrapper
  def option(seriesGame: Option[Series], currentGameId: Option[GameId])(using Context) =
    seriesGame.map(s => seriesScore(s, currentGameId))
