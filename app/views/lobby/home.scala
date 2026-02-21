package views.lobby

import play.api.libs.json.Json

import lila.app.UiEnv.{ *, given }
import lila.app.mashup.Preload.Homepage
import lila.core.perf.UserWithPerfs

object home:

  def apply(homepage: Homepage)(using ctx: Context) =
    import homepage.*
    Page("")
      .copy(fullTitle = s"$siteName • ${trans.site.freeOnlineChess.txt()}".some)
      .i18n(_.variant)
      .js(
        PageModule(
          "lobby",
          Json
            .obj(
              "data" -> data,
              "showRatings" -> ctx.pref.showRatings
            )
            .add("hasUnreadLichessMessage", hasUnreadLichessMessage)
            .add("bots", Granter.opt(_.Beta))
            .add("playban", playban.map(lila.playban.TempBan.lobbyJson))
        )
      )
      .css("lobby")
      .graph(
        OpenGraph(
          image = staticAssetUrl("logo/lichess-tile-wide.png").some,
          title = "The best free, adless Chess server",
          url = netBaseUrl.into(Url),
          description = trans.site.siteDescription.txt()
        )
      )
      .hrefLangs(lila.ui.LangPath("/")):
        given Option[UserWithPerfs] = homepage.me
        main(
          cls := List(
            "lobby" -> true,
            "lobby-nope" -> (playban.isDefined || currentGame.isDefined || currentSeries.isDefined || homepage.hasUnreadLichessMessage)
          )
        )(
          div(cls := "lobby__table")(
            div(cls := "lobby__start")(
              button(cls := "button button-metal lobby__start__button lobby__start__button--openingDuel")(
                "Opening Duel"
              ),
              button(cls := "button button-metal lobby__start__button lobby__start__button--openingDuelAi")(
                "Opening Duel with Computer"
              )
            )
          ),
          currentSeries
            .map(bits.currentSeriesInfo)
            .orElse:
              currentGame.map(bits.currentGameInfo)
            .orElse:
              hasUnreadLichessMessage.option(bits.showUnreadLichessMessage)
            .orElse:
              playban.map(bits.playbanInfo)
            .getOrElse:
              if ctx.blind then blindLobby(blindGames) else bits.lobbyApp
        )
