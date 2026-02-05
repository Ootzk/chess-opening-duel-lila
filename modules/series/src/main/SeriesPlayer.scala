package lila.series

import lila.core.userId.UserId

/** 시리즈 참가 플레이어 */
case class SeriesPlayer(
    userId: UserId,
    index: Int,
    score: Int = 0,  // Internal: win=2, draw=1, loss=0
    confirmedPicks: Boolean = false,
    confirmedBans: Boolean = false
):
  def addWin: SeriesPlayer = copy(score = score + 2)
  def addDraw: SeriesPlayer = copy(score = score + 1)
  def displayScore: Double = score / 2.0  // For display: win=1, draw=0.5
  def confirmPicks: SeriesPlayer = copy(confirmedPicks = true)
  def cancelConfirmPicks: SeriesPlayer = copy(confirmedPicks = false)
  def confirmBans: SeriesPlayer = copy(confirmedBans = true)
  def cancelConfirmBans: SeriesPlayer = copy(confirmedBans = false)
