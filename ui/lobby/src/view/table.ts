import { bind, onInsert, hl, thunk } from 'lib/view';
import * as licon from 'lib/licon';
import type LobbyController from '../ctrl';
import type { GameType } from '../interfaces';
import renderSetupModal from './setup/modal';
import { numberFormat } from 'lib/i18n';

type ButtonInfo = { gameType: GameType | 'dev' | 'bots'; label: string; icon?: string; disabled?: boolean; title?: string };

export default function table(ctrl: LobbyController) {
  const { data, opts } = ctrl;
  const hasOngoingRealTimeGame = ctrl.hasOngoingRealTimeGame();
  const hookDisabled =
    opts.playban || opts.hasUnreadLichessMessage || ctrl.me?.isBot || hasOngoingRealTimeGame;
  const { members, rounds } = data.counters;
  const lobbyButtons: ButtonInfo[] = [
    {
      gameType: 'openingDuelAnyone',
      label: 'Opening Duel with Anyone',
      icon: licon.Group,
      disabled: hookDisabled,
      title: 'Find a random opponent for a best-of-5 opening duel.',
    },
    {
      gameType: 'openingDuel',
      label: 'Opening Duel with Friend',
      icon: licon.User,
      disabled: hasOngoingRealTimeGame,
      title: $trim`
        Create a custom game and choose your opponent - but best of 5 match.

        You will receive a challenge link to share via email or text, as well as a QR code
        that someone nearby can scan.`,
    },
    {
      gameType: 'openingDuelAi',
      label: 'Opening Duel with Computer',
      icon: licon.Cpu,
      disabled: hasOngoingRealTimeGame,
      title: 'Play a best-of-5 opening duel against Stockfish.',
    },
  ];

  return hl('div.lobby__table', [
    hl('div.lobby__start', [
      site.blindMode && hl('h2', i18n.site.play),
      lobbyButtons.map(makeLobbyButton),
      hl('a.button.button-metal.lobby__start__button', { attrs: { href: '/opening-pool', 'data-icon': licon.Book } }, 'Manage my opening pools'),
    ]),
    renderSetupModal(ctrl),
    // Use a thunk here so that snabbdom does not rerender; we will do so manually after insert
    site.blindMode
      ? undefined
      : thunk(
          'div.lobby__counters',
          () =>
            hl('div.lobby__counters', [
              hl(
                'a',
                { attrs: site.blindMode ? {} : { href: '/player' } },
                i18n.site.nbPlayers.asArray(
                  members,
                  hl(
                    'strong',
                    {
                      attrs: { 'data-count': members },
                      hook: onInsert<HTMLAnchorElement>(elm => {
                        ctrl.spreadPlayersNumber = ctrl.initNumberSpreader(elm, 10, members);
                      }),
                    },
                    numberFormat(members),
                  ),
                ),
              ),
              hl(
                'a',
                site.blindMode ? {} : { attrs: { href: '/games' } },
                i18n.site.nbGamesInPlay.asArray(
                  rounds,
                  hl(
                    'strong',
                    {
                      attrs: { 'data-count': rounds },
                      hook: onInsert<HTMLAnchorElement>(elm => {
                        ctrl.spreadGamesNumber = ctrl.initNumberSpreader(elm, 8, rounds);
                      }),
                    },
                    numberFormat(rounds),
                  ),
                ),
              ),
            ]),
          [],
        ),
  ]);

  function makeLobbyButton({ gameType, label, icon, disabled, title }: ButtonInfo) {
    return hl(
      `button.button.button-metal.lobby__start__button.lobby__start__button--${gameType}`,
      {
        class: { active: ctrl.setupCtrl.gameType === gameType, disabled: !!disabled },
        attrs: { type: 'button', title: title ?? '', 'aria-disabled': disabled ? 'true' : 'false', ...(icon ? { 'data-icon': icon } : {}) },
        hook: disabled
          ? {}
          : bind(
              'click',
              () => {
                if (gameType === 'bots') location.href = '/bots';
                else if (gameType === 'dev') location.href = '/bots/dev';
                else ctrl.setupCtrl.openModal(gameType);
              },
              ctrl.redraw,
            ),
      },
      label,
    );
  }
}
