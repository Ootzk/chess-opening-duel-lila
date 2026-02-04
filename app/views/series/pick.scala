package views.series

import play.api.libs.json.JsObject

import lila.app.UiEnv.{ *, given }
import lila.series.{ Series, OpeningPreset }

def pick(s: Series, seriesJson: JsObject, presets: Vector[OpeningPreset], displayOpenings: Vector[OpeningPreset])(using Context) =
  views.seriesPick.pick(s, seriesJson, presets, displayOpenings)

def shuffling(s: Series, seriesJson: JsObject)(using Context) =
  views.seriesPick.shuffling(s, seriesJson)