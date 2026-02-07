package lila.series

import lila.core.userId.UserId

/** 시리즈 참가 플레이어 */
case class SeriesPlayer(
    userId: UserId,
    index: Int,
    score: Int = 0, // Internal: win=2, draw=1, loss=0
    confirmedPicks: Boolean = false,
    confirmedBans: Boolean = false,
    lastSeenAt: Option[Instant] = None
):
  def addWin: SeriesPlayer                 = copy(score = score + 2)
  def addDraw: SeriesPlayer                = copy(score = score + 1)
  def displayScore: Double                 = score / 2.0 // For display: win=1, draw=0.5
  def confirmPicks: SeriesPlayer           = copy(confirmedPicks = true)
  def cancelConfirmPicks: SeriesPlayer     = copy(confirmedPicks = false)
  def confirmBans: SeriesPlayer            = copy(confirmedBans = true)
  def cancelConfirmBans: SeriesPlayer      = copy(confirmedBans = false)
  def updateLastSeen: SeriesPlayer         = copy(lastSeenAt = Some(nowInstant))
  // None = 아직 접속 여부를 모름 → online으로 가정
  // Some(time) = time이 10초 이내면 online, 아니면 offline
  def isOnline: Boolean                    = lastSeenAt.forall(_.isAfter(nowInstant.minusSeconds(10)))
  def isDisconnected: Boolean              = lastSeenAt.exists(_.isBefore(nowInstant.minusSeconds(10)))
