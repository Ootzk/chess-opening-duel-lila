package lila.series

import lila.core.userId.UserId

/** 시리즈 참가 플레이어 */
case class SeriesPlayer(
    userId: UserId,
    index: Int,
    score: Int = 0,
    confirmedPicks: Boolean = false,
    confirmedBans: Boolean = false
):
  def addWin: SeriesPlayer = copy(score = score + 1)
  def confirmPicks: SeriesPlayer = copy(confirmedPicks = true)
  def cancelConfirmPicks: SeriesPlayer = copy(confirmedPicks = false)
  def confirmBans: SeriesPlayer = copy(confirmedBans = true)
  def cancelConfirmBans: SeriesPlayer = copy(confirmedBans = false)
