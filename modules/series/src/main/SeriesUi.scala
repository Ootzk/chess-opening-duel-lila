package lila.series
package ui

import chess.format.Fen
import play.api.libs.json.{ Json, JsObject }

import lila.ui.*

import ScalatagsTemplate.*

final class SeriesUi(helpers: Helpers):
  import helpers.{ *, given }

  def pick(s: Series, seriesJson: JsObject, presets: Vector[OpeningPreset])(using Context): Page =
    val phaseName = s.phase match
      case Series.Phase.Picking => "Pick Phase"
      case Series.Phase.Banning => "Ban Phase"
      case Series.Phase.Game1Shuffling => "Game 1 Starting"
      case Series.Phase.Selecting => "Select Opening"
      case _ => "Opening Duel"

    Page(s"Opening Duel - $phaseName")
      .css("series.pick")
      .js(pageModule(s, seriesJson, presets))
      .csp(_.withWebAssembly)
      .flag(_.zoom)
      .body(
        main(cls := "series-pick")(
          div(cls := "series-pick__header")(
            h1(phaseName),
            div(cls := "series-pick__timer")(
              span(cls := "timer-display")("30")
            )
          ),
          div(cls := "series-pick__grid")(
            presets.map: preset =>
              // Extract board FEN from full FEN (first part before space)
              val boardFen = preset.fen.value.split(" ").headOption.getOrElse("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR")
              div(
                cls := "series-pick__opening",
                attr("data-name") := preset.name,
                attr("data-fen") := preset.fen.value
              )(
                div(cls := "series-pick__board mini-board mini-board--init cg-wrap is2d",
                    attr("data-state") := s"$boardFen,white,"
                )(cgWrapContent),
                div(cls := "series-pick__name")(
                  span(cls := "opening-name")(preset.name),
                  span(cls := "opening-check")
                )
              )
          ),
          div(cls := "series-pick__footer")(
            div(cls := "series-pick__actions")(
              button(cls := "button button-green", disabled := true)("Confirm (0/5)")
            ),
            div(cls := "series-pick__chat mchat")(
              div(cls := "mchat__tabs")(div(cls := "mchat__tab")(nbsp)),
              div(cls := "mchat__content")
            )
          )
        )
      )

  private def pageModule(s: Series, seriesJson: JsObject, presets: Vector[OpeningPreset])(using ctx: Context) =
    PageModule(
      "series.pick",
      Json.obj(
        "seriesId" -> s.id.value,
        "phase" -> s.phase.id,
        "presets" -> presets.map(p => Json.obj(
          "name" -> p.name,
          "fen" -> p.fen.value,
          "url" -> p.url
        )),
        "series" -> seriesJson,
        "i18n" -> Json.obj()
      )
    ).some