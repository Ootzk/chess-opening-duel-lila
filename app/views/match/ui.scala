package views.`match`

import chess.Color

import lila.app.UiEnv.{ *, given }
import lila.`match`.Match
import lila.`match`.OpeningPreset
import lila.core.id.GameId
import lila.core.game.Game

object ui:

  // currentGameResult: 현재 게임이 끝났으면 그 결과 (race condition 해결용)
  def matchScore(m: Match, currentGameId: Option[GameId], currentGameResult: Option[Option[Color]] = None)(using ctx: Context) =
    // POV: 로그인한 유저 기준
    val povColor = ctx.userId.flatMap(m.colorOf).getOrElse(Color.White)

    div(cls := "match-score")(
      table(cls := "match-score__table")(
        thead(
          tr(
            th(cls := "match-score__header-game")("Game"),
            th(cls := "match-score__header-me")(userIdLink(m.players(povColor).some, withOnline = true)),
            th(cls := "match-score__header-opp")(userIdLink(m.players(!povColor).some, withOnline = true)),
            th(cls := "match-score__header-opening")("Opening")
          )
        ),
        tbody(
          m.openings.zipWithIndex.map: (opening, idx) =>
            val round = idx + 1
            val gid = m.gameIds.lift(idx)
            // Match의 results를 먼저 확인, 없으면 현재 게임 결과 사용
            val result = m.results.lift(idx).orElse(
              gid.filter(currentGameId.contains).flatMap(_ => currentGameResult)
            )
            val isCurrent = gid.exists(currentGameId.contains)
            val isInProgress = result.isEmpty && isCurrent

            tr(cls := List("match-score__row" -> true, "current" -> isCurrent))(
              td(cls := "match-score__game")(s"$round"),
              td(cls := "match-score__result")(
                resultCell(povColor, result, isInProgress, gid)
              ),
              td(cls := "match-score__result")(
                resultCell(!povColor, result, isInProgress, gid)
              ),
              td(cls := "match-score__opening")(
                gid.fold(span(opening.name)): id =>
                  a(href := routes.Round.watcher(id, Color.white))(opening.name)
              )
            )
        )
      ),
      // 현재 오프닝 라벨
      div(cls := "match-score__label")(
        m.openingForRound(m.currentRound).fold(
          s"Opening Duel - Game ${m.currentRound} of ${m.bestOf}"
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
  def option(matchGame: Option[Match], currentGameId: Option[GameId])(using Context) =
    matchGame.map(m => matchScore(m, currentGameId))
