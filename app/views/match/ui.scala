package views.`match`

import chess.Color

import lila.app.UiEnv.{ *, given }
import lila.`match`.Match
import lila.core.id.GameId
import lila.core.game.Game

object ui:

  // currentGameResult: 현재 게임이 끝났으면 그 결과 (race condition 해결용)
  def matchScore(m: Match, currentGameId: Option[GameId], currentGameResult: Option[Option[Color]] = None)(using ctx: Context) =
    // POV: 로그인한 유저가 첫 번째 row, 상대가 두 번째 row
    val povColor = ctx.userId.flatMap(m.colorOf).getOrElse(Color.White)
    val (firstPlayer, secondPlayer) = if povColor.white then (Color.White, Color.Black) else (Color.Black, Color.White)

    // 게임 결과 목록
    val gamesData = m.gameIds.zipWithIndex.map: (gid, idx) =>
      val isCurrent = currentGameId.contains(gid)
      // Match의 results를 먼저 확인, 없으면 현재 게임 결과 사용
      val result = m.results.lift(idx).orElse(
        if isCurrent then currentGameResult else None
      )
      val isInProgress = result.isEmpty && isCurrent
      (gid, result, isCurrent, isInProgress)

    div(cls := "match-score")(
      table(cls := "match-score__table")(
        tbody(
          // 첫 번째 플레이어 (POV 유저)
          playerRow(m, firstPlayer, gamesData, currentGameId),
          // 두 번째 플레이어 (상대)
          playerRow(m, secondPlayer, gamesData, currentGameId)
        )
      ),
      // 라운드 정보
      div(cls := "match-score__round")(
        s"Opening Duel - Game ${m.currentRound} of ${m.bestOf}"
      )
    )

  private def playerRow(
      m: Match,
      playerColor: Color,
      gamesData: List[(GameId, Option[Option[Color]], Boolean, Boolean)],
      currentGameId: Option[GameId]
  )(using Context) =
    val playerId = m.players(playerColor)
    val score = m.scores(playerColor)

    tr(cls := "match-score__row")(
      // 플레이어 이름 + 승수
      td(cls := "match-score__player")(
        userIdLink(playerId.some, withOnline = true),
        span(cls := "match-score__wins")(s"($score)")
      ),
      // 각 게임 결과
      gamesData.map: (gid, resultOpt, isCurrent, isInProgress) =>
        val cellCls = List(
          "match-score__cell" -> true,
          "current" -> isCurrent
        )
        td(cls := cellCls)(
          if isInProgress then
            // 진행중인 게임
            a(href := routes.Round.watcher(gid, Color.white), cls := "in-progress")("-")
          else
            resultOpt match
              case Some(winner) =>
                // 완료된 게임
                val (linkCls, text) = winner match
                  case Some(w) if w == playerColor => "win" -> "1"
                  case None => "draw" -> "½"
                  case Some(_) => "loss" -> "0"
                a(href := routes.Round.watcher(gid, Color.white), cls := linkCls)(text)
              case None =>
                // 아직 시작하지 않은 게임
                span(cls := "pending")("-")
        )
    )

  // 외부에서 사용할 간단한 wrapper
  def option(matchGame: Option[Match], currentGameId: Option[GameId])(using Context) =
    matchGame.map(m => matchScore(m, currentGameId))
