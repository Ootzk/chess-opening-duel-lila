package lila.series
package ui

import play.api.libs.json.{ Json, JsObject }

import lila.ui.*

import ScalatagsTemplate.*

final class SeriesUi(helpers: Helpers):
  import helpers.*

  def pick(s: Series, seriesJson: JsObject, presets: Vector[OpeningPreset], displayOpenings: Vector[OpeningPreset])(using Context): Page =
    val phaseName = s.phase match
      case Series.Phase.Picking => "Pick Phase"
      case Series.Phase.Banning => "Ban Phase"
      case Series.Phase.RandomSelecting => "Game Starting"
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
              span(cls := "timer-display")(s.timeLeftInPhase.toString)
            )
          ),
          div(cls := "series-pick__grid")(
            displayOpenings.map: preset =>
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

  private def pageModule(s: Series, seriesJson: JsObject, presets: Vector[OpeningPreset])(using Context) =
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

  def randomSelecting(s: Series, seriesJson: JsObject)(using Context): Page =
    val gameNum = s.currentRound
    // Get the actual selected opening from the current game
    val selectedOpening = s.currentGame.flatMap(g => s.openings.find(_.id == g.openingId))
    Page(s"Opening Duel - Game $gameNum Starting")
      .css("series.pick")
      .js(randomSelectingModule(s, seriesJson))
      .csp(_.withWebAssembly)
      .flag(_.zoom)
      .body(
        main(cls := "series-pick random-selecting")(
          div(cls := "series-pick__header")(
            h1(s"Game $gameNum Starting..."),
            div(cls := "series-pick__countdown")(s.timeLeftInPhase.toString)
          ),
          div(cls := "series-pick__random-selecting-boxes")(
            renderBanBox(s, 0, selectedOpening),
            renderBanBox(s, 1, selectedOpening)
          )
        )
      )

  private def renderBanBox(s: Series, playerIndex: Int, selectedOpening: Option[SeriesOpening]) =
    val bans = s.bans(playerIndex)
    val usedOpenings = s.openings.filter(_.isUsed).map(_.name).toSet
    val playerName = s.player(playerIndex).userId.value
    div(cls := "series-pick__ban-box")(
      div(cls := "series-pick__ban-header")(s"$playerName's Bans"),
      div(cls := "series-pick__ban-openings")(
        bans.map: opening =>
          val isHighlighted = selectedOpening.exists(_.id == opening.id)
          val isUsed = usedOpenings.contains(opening.name) && !isHighlighted
          val boardFen = opening.fen.value.split(" ").headOption.getOrElse("")
          val openingCls = List(
            "series-pick__opening",
            if isHighlighted then "highlighted" else "",
            if isUsed then "used" else ""
          ).filter(_.nonEmpty).mkString(" ")
          div(
            cls := openingCls,
            attr("data-name") := opening.name,
            attr("data-fen") := opening.fen.value
          )(
            div(cls := "series-pick__board mini-board mini-board--init cg-wrap is2d",
                attr("data-state") := s"$boardFen,white,"
            )(cgWrapContent),
            div(cls := "series-pick__name")(
              span(cls := "opening-name")(opening.name)
            )
          )
      )
    )

  private def randomSelectingModule(s: Series, seriesJson: JsObject)(using Context) =
    PageModule(
      "series.randomSelecting",
      Json.obj(
        "seriesId" -> s.id.value,
        "phase" -> s.phase.id,
        "series" -> seriesJson
      )
    ).some
