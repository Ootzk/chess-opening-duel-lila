package lila.series

import lila.core.userId.UserId

/** 시리즈 참가 플레이어 */
case class SeriesPlayer(
    userId: UserId,
    index: Int,
    score: Int = 0, // Internal: win=2, draw=1, loss=0
    confirmedPicks: Boolean = false,
    confirmedBans: Boolean = false,
    confirmedSelecting: Boolean = false,
    selectingPick: Option[String] = None, // 선택한 오프닝 이름
    lastSeenAt: Option[Instant] = None
):
  def addWin: SeriesPlayer                 = copy(score = score + 2)
  def addDraw: SeriesPlayer                = copy(score = score + 1)
  def displayScore: Double                 = score / 2.0 // For display: win=1, draw=0.5
  def confirmPicks: SeriesPlayer           = copy(confirmedPicks = true)
  def cancelConfirmPicks: SeriesPlayer     = copy(confirmedPicks = false)
  def confirmBans: SeriesPlayer            = copy(confirmedBans = true)
  def cancelConfirmBans: SeriesPlayer      = copy(confirmedBans = false)
  def confirmSelecting(pick: String): SeriesPlayer = copy(confirmedSelecting = true, selectingPick = Some(pick))
  def cancelConfirmSelecting: SeriesPlayer = copy(confirmedSelecting = false)
  def clearSelecting: SeriesPlayer         = copy(confirmedSelecting = false, selectingPick = None)
  def updateLastSeen: SeriesPlayer         = copy(lastSeenAt = Some(nowInstant))
  // None = 아직 접속 여부를 모름 → online으로 가정
  // Some(time) = time이 5초 이내면 online, 아니면 offline
  // (WS ping이 3초 간격으로 개별 플레이어별 갱신)
  def isOnline: Boolean                    = lastSeenAt.forall(_.isAfter(nowInstant.minusSeconds(5)))
  def isDisconnected: Boolean              = lastSeenAt.exists(_.isBefore(nowInstant.minusSeconds(5)))
