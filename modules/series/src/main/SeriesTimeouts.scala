package lila.series

import akka.actor.{ Cancellable, Scheduler }
import scala.concurrent.duration.*

import lila.core.id.SeriesId

final class SeriesTimeouts(
    scheduler: Scheduler,
    api: () => SeriesApi // lazy to avoid circular dependency
)(using Executor):

  private val timeouts = scalalib.ConcurrentMap[SeriesId, Cancellable](256)

  def schedule(seriesId: SeriesId): Unit =
    scheduleWithDuration(seriesId, Series.phaseTimeout)

  def scheduleWithDuration(seriesId: SeriesId, seconds: Int): Unit =
    if seconds > 0 then
      timeouts.compute(seriesId): canc =>
        canc.foreach(_.cancel())
        scheduler
          .scheduleOnce(seconds.seconds):
            timeouts.remove(seriesId)
            handleTimeout(seriesId)
          .some

  def cancel(seriesId: SeriesId): Unit =
    timeouts.remove(seriesId).foreach(_.cancel())

  private def handleTimeout(seriesId: SeriesId): Unit =
    api().byId(seriesId).foreach:
      case None => ()
      case Some(series) =>
        val remaining = series.timeLeftInPhase
        if remaining > 1 then
          // 아직 타임아웃 아님 - 남은 시간만큼 재스케줄
          scheduleWithDuration(seriesId, remaining)
        else
          // 실제 타임아웃 - 처리 진행
          api().serverTimeoutPhase(seriesId).foreach:
            case Some(s) if s.phase == Series.Phase.Banning =>
              schedule(seriesId) // Banning 페이즈 진입 시 새 타임아웃 스케줄
            case _ => ()
