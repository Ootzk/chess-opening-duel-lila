package views.series

import play.api.libs.json.JsObject

import lila.app.UiEnv.{ *, given }
import lila.core.socket.SocketVersion
import lila.series.{ Series, OpeningPreset }

def pick(s: Series, seriesJson: JsObject, presets: Vector[OpeningPreset], displayOpenings: Vector[OpeningPreset], socketVersion: SocketVersion)(using Context) =
  views.seriesPick.pick(s, seriesJson, presets, displayOpenings, socketVersion)

def randomSelecting(s: Series, seriesJson: JsObject, socketVersion: SocketVersion)(using Context) =
  views.seriesPick.randomSelecting(s, seriesJson, socketVersion)
