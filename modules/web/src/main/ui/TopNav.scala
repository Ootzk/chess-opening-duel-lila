package lila.web
package ui

import scalalib.model.Days
import lila.ui.*
import ScalatagsTemplate.{ *, given }

final class TopNav(helpers: Helpers):
  import helpers.{ *, given }

  private def linkTitle(url: String, name: Frag)(using ctx: Context) =
    if ctx.blind then h3(name) else a(href := url)(name)

  def apply(hasClas: Boolean, hasDgt: Boolean)(using ctx: Context) =
    st.nav(id := "topnav", cls := "hover")()
