package lila.core
package series

import _root_.chess.variant.Variant
import _root_.chess.Clock

import lila.core.socket.Sri
import lila.core.id.SeriesId
import lila.core.userId.UserId

// lobby → series: hook match triggers Series creation
case class SeriesHookMatch(
    ownerSri: Sri,
    joinerSri: Sri,
    ownerId: UserId,
    joinerId: UserId,
    variant: Variant,
    clock: Clock.Config
)

// series → lobby: Series created, redirect both players
case class LobbySeriesRedirect(
    ownerSri: Sri,
    joinerSri: Sri,
    seriesId: SeriesId
)
