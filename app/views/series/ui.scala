package views.series

import chess.Color

import lila.app.UiEnv.{ *, given }
import lila.series.Series
import lila.series.OpeningPreset
import lila.core.id.GameId
import lila.core.game.Game

object ui:

  // currentGameResult: 현재 게임이 끝났으면 그 결과 (race condition 해결용)
  def seriesScore(s: Series, currentGameId: Option[GameId], currentGameResult: Option[Option[Color]] = None)(using ctx: Context) =
    // POV: 로그인한 유저 기준
    val povColor = ctx.userId.flatMap(s.colorOf).getOrElse(Color.White)

    div(cls := "series-score")(  // Keep CSS class for compatibility
      table(cls := "series-score__table")(
        thead(
          tr(
            th(cls := "series-score__header-game")("Game"),
            th(cls := "series-score__header-me")(userIdLink(s.players(povColor).some, withOnline = true)),
            th(cls := "series-score__header-opp")(userIdLink(s.players(!povColor).some, withOnline = true)),
            th(cls := "series-score__header-opening")("Opening")
          )
        ),
        tbody(
          s.openings.zipWithIndex.map: (opening, idx) =>
            val round = idx + 1
            val gid = s.gameIds.lift(idx)
            // Series의 results를 먼저 확인, 없으면 현재 게임 결과 사용
            val result = s.results.lift(idx).orElse(
              gid.filter(currentGameId.contains).flatMap(_ => currentGameResult)
            )
            val isCurrent = gid.exists(currentGameId.contains)
            val isInProgress = result.isEmpty && isCurrent
            val isCompleted = result.isDefined

            tr(cls := List("series-score__row" -> true, "current" -> isCurrent))(
              td(cls := "series-score__game")(
                // 완료된 게임은 링크로 표시
                if isCompleted then
                  gid.fold(span(s"$round")): id =>
                    a(href := routes.Round.watcher(id, Color.white), cls := "game-link")(s"$round")
                else
                  span(s"$round")
              ),
              td(cls := "series-score__result")(
                resultCell(povColor, result, isInProgress, gid)
              ),
              td(cls := "series-score__result")(
                resultCell(!povColor, result, isInProgress, gid)
              ),
              td(cls := "series-score__opening")(
                // 오프닝 이름은 lichess 오프닝 페이지로 링크
                a(href := opening.url, target := "_blank", cls := "opening-link")(opening.name)
              )
            )
        )
      ),
      // 현재 오프닝 라벨
      div(cls := "series-score__label")(
        s.openingForRound(s.currentRound).fold(
          s"Opening Duel - Game ${s.currentRound} of ${s.bestOf}"
        )(op => s"Opening Duel - ${op.name}")
      )
    )

  private def resultCell(
      color: Color,
      result: Option[Option[Color]],
      isInProgress: Boolean,
      gid: Option[GameId]
  ) =
    if isInProgress then
      gid.fold(span(cls := "in-progress")("-")): id =>
        a(href := routes.Round.watcher(id, Color.white), cls := "in-progress")("-")
    else
      result match
        case Some(winner) =>
          val (linkCls, text) = winner match
            case Some(w) if w == color => "win" -> "1"
            case None                  => "draw" -> "½"
            case Some(_)               => "loss" -> "0"
          gid.fold(span(cls := linkCls)(text)): id =>
            a(href := routes.Round.watcher(id, Color.white), cls := linkCls)(text)
        case None =>
          span(cls := "pending")("-")

  // 외부에서 사용할 간단한 wrapper
  def option(seriesGame: Option[Series], currentGameId: Option[GameId])(using Context) =
    seriesGame.map(s => seriesScore(s, currentGameId))
