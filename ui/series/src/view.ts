import { h } from 'snabbdom';
import type { VNode } from 'snabbdom';
import type SeriesPickCtrl from './ctrl';
import type { OpeningPreset, SeriesOpening, SeriesGame } from './interfaces';
import { getOpponentBans, getMyBans, getNeutralOpening } from './interfaces';
import { userLink } from 'lib/view/userLink';

export default function view(ctrl: SeriesPickCtrl): VNode {
  if (ctrl.isFinished) {
    return renderFinished(ctrl);
  }
  if (ctrl.isRandomSelecting) {
    return renderRandomSelecting(ctrl);
  }
  // Selecting phase: 승자는 대기 화면, 패자는 선택 화면
  if (ctrl.isSelecting && ctrl.isWaitingForOpponentSelect) {
    return renderWaitingForOpponentSelect(ctrl);
  }
  return h('div.series-pick', [
    renderHeader(ctrl),
    renderGrid(ctrl),
    renderFooter(ctrl),
  ]);
}

// Selecting phase: 승자가 패자의 선택을 기다리는 화면
function renderWaitingForOpponentSelect(ctrl: SeriesPickCtrl): VNode {
  return h('div.series-pick.selecting-waiting', [
    h('div.series-pick__header', [
      h('h1', 'Opponent Selecting'),
      h('div.series-pick__timer', { class: { hurry: ctrl.timeLeft <= 10 } }, [
        h('span.timer-display', String(ctrl.timeLeft)),
      ]),
    ]),
    h('div.series-pick__waiting-container', [
      h('div.series-pick__waiting-message', [
        h('span.ddloader'),
        h('span', 'Waiting for opponent to select opening...'),
      ]),
    ]),
    h('div.series-pick__footer', [
      h('div.series-pick__actions'),
      h('div.series-pick__chat.mchat', [
        h('div.mchat__tabs', [h('div.mchat__tab', '\u00a0')]),
        h('div.mchat__content'),
      ]),
    ]),
  ]);
}

function renderRandomSelecting(ctrl: SeriesPickCtrl): VNode {
  const povIndex = ctrl.series.povIndex ?? 0;
  const oppIndex = 1 - povIndex;

  const myBans = getMyBans(ctrl.series);
  const oppBans = getOpponentBans(ctrl.series);
  const neutral = getNeutralOpening(ctrl.series);

  const myName = ctrl.series.players[povIndex].user?.name || `Player ${povIndex + 1}`;
  const oppName = ctrl.series.players[oppIndex].user?.name || `Player ${oppIndex + 1}`;
  const gameNum = ctrl.series.round;

  return h('div.series-pick.random-selecting', [
    h('div.series-pick__header', [
      h('h1', `Game ${gameNum} Starting...`),
      h('div.series-pick__countdown', String(ctrl.randomSelectingCountdown)),
    ]),
    h('div.series-pick__random-selecting-boxes', [
      renderBanBox(ctrl, myName, myBans),
      neutral ? renderNeutralBox(ctrl, neutral) : null,
      renderBanBox(ctrl, oppName, oppBans),
    ]),
  ]);
}

function renderBanBox(ctrl: SeriesPickCtrl, playerName: string, bans: SeriesOpening[]): VNode {
  // Filter out THIS specific opening if it was used in a PREVIOUS round
  // (Check the opening's own usedInRound, not by name lookup)
  const currentRound = Number(ctrl.series.round);
  const visibleBans = bans.filter(b => {
    const usedIn = b.usedInRound;
    if (usedIn === undefined || usedIn === null) return true; // Not used yet
    return Number(usedIn) >= currentRound; // Show if used in current round or later
  });

  return h('div.series-pick__ban-box', [
    h('div.series-pick__ban-header', `${playerName}'s Bans`),
    h('div.series-pick__ban-openings', visibleBans.map(opening => renderRandomSelectingOpening(ctrl, opening))),
  ]);
}

function renderNeutralBox(ctrl: SeriesPickCtrl, neutral: SeriesOpening): VNode {
  // Check if neutral was used in a previous round
  const currentRound = Number(ctrl.series.round);
  const usedIn = neutral.usedInRound;
  const isVisible = usedIn === undefined || usedIn === null || Number(usedIn) >= currentRound;

  if (!isVisible) {
    return h('div.series-pick__neutral-box.used', [
      h('div.series-pick__neutral-header', 'Neutral'),
    ]);
  }

  return h('div.series-pick__neutral-box', [
    h('div.series-pick__neutral-header', 'Neutral'),
    h('div.series-pick__neutral-openings', [renderRandomSelectingOpening(ctrl, neutral)]),
  ]);
}

function renderRandomSelectingOpening(ctrl: SeriesPickCtrl, opening: SeriesOpening): VNode {
  // Highlight the opening selected for current round (use Number() for type safety)
  const currentRound = Number(ctrl.series.round);
  const isHighlighted = Number(opening.usedInRound) === currentRound;

  return h(
    'div.series-pick__opening',
    {
      class: { highlighted: isHighlighted },
      attrs: { 'data-name': opening.name, 'data-fen': opening.fen },
    },
    [
      h(
        'div.series-pick__board.mini-board.mini-board--init.cg-wrap.is2d',
        {
          attrs: { 'data-state': `${opening.fen},white,` },
        },
        [h('cg-container', [h('cg-board')])],
      ),
      h('div.series-pick__name', [h('span.opening-name', opening.name)]),
    ],
  );
}

function renderHeader(ctrl: SeriesPickCtrl): VNode {
  const phaseName = ctrl.isPicking
    ? 'Pick Phase'
    : ctrl.isBanning
      ? 'Ban Phase'
      : ctrl.isSelecting
        ? 'Select Opening'
        : 'Opening Duel';

  const hurry = ctrl.timeLeft <= 10;

  return h('div.series-pick__header', [
    h('h1', phaseName),
    h('div.series-pick__timer', { class: { hurry } }, [h('span.timer-display', String(ctrl.timeLeft))]),
  ]);
}

function renderGrid(ctrl: SeriesPickCtrl): VNode {
  const openings = ctrl.isPicking ? ctrl.presets : ctrl.availableOpenings;

  return h(
    'div.series-pick__grid',
    openings.map(preset => renderOpening(ctrl, preset)),
  );
}

function renderOpening(ctrl: SeriesPickCtrl, preset: OpeningPreset): VNode {
  const isSelected = ctrl.isSelected(preset.name);
  const canSelect = ctrl.canSelect(preset.name);
  const isDisabled = ctrl.myConfirmed || (!isSelected && !canSelect);

  return h(
    'div.series-pick__opening',
    {
      class: {
        selected: isSelected,
        disabled: isDisabled,
        'my-pick': isSelected && ctrl.isPicking,
        'my-ban': isSelected && ctrl.isBanning,
      },
      attrs: {
        'data-name': preset.name,
        'data-fen': preset.fen,
      },
      on: {
        click: () => {
          if (!isDisabled || isSelected) {
            ctrl.toggleSelection(preset.name);
          }
        },
      },
    },
    [
      h(
        'div.series-pick__board.mini-board.mini-board--init.cg-wrap.is2d',
        {
          attrs: {
            'data-state': `${preset.fen},white,`,
          },
        },
        [h('cg-container', [h('cg-board')])],
      ),
      h('div.series-pick__name', [h('span.opening-name', preset.name)]),
    ],
  );
}

function renderFooter(ctrl: SeriesPickCtrl): VNode {
  return h('div.series-pick__footer', [
    renderActions(ctrl),
    // Chat placeholder - to be integrated
    h('div.series-pick__chat.mchat', [
      h('div.mchat__tabs', [h('div.mchat__tab', '\u00a0')]),
      h('div.mchat__content'),
    ]),
  ]);
}

function renderActions(ctrl: SeriesPickCtrl): VNode {
  const leftSide: VNode[] = [];
  const rightSide: VNode[] = [];

  // Left side: Confirm or Cancel button
  const buttonClass = ctrl.isBanning ? 'button.button.button-red' : 'button.button.button-green';
  if (ctrl.isWaiting) {
    leftSide.push(
      h(
        'button.button.button-metal.series-pick__action-btn',
        {
          on: {
            click: () => ctrl.cancelConfirm(),
          },
        },
        'Cancel',
      ),
    );
  } else {
    leftSide.push(
      h(
        `${buttonClass}.series-pick__action-btn`,
        {
          attrs: {
            disabled: !ctrl.canConfirm,
          },
          on: {
            click: () => ctrl.confirm(),
          },
        },
        ctrl.confirmButtonText,
      ),
    );
  }

  // Right side: Opponent status (only in pick/ban phases)
  if (ctrl.isPicking || ctrl.isBanning) {
    const povIndex = ctrl.series.povIndex ?? 0;
    const oppIndex = 1 - povIndex;
    const opponent = ctrl.series.players[oppIndex];
    const oppUser = opponent?.user;
    const statusClass = ctrl.opponentConfirmed ? 'ready' : 'waiting';

    rightSide.push(
      h(`div.series-pick__opponent-status.${statusClass}`, [
        oppUser
          ? userLink({ name: oppUser.name, title: oppUser.title, online: ctrl.opponentOnline, line: true })
          : h('span', `Player ${oppIndex + 1}`),
        ctrl.opponentConfirmed
          ? h('span.status-text', ' is Ready!')
          : h('span.status-text', ' is selecting...'),
      ]),
    );
  }

  return h('div.series-pick__actions', [
    h('div.series-pick__actions-left', leftSide),
    h('div.series-pick__actions-right', rightSide),
  ]);
}

// ===== Finished Page =====

function renderFinished(ctrl: SeriesPickCtrl): VNode {
  return h('div.series-pick.series-finished', [
    renderFinishedHeader(ctrl),
    renderFinishedScoreTable(ctrl),
    renderFinishedActions(ctrl),
  ]);
}

function renderFinishedHeader(ctrl: SeriesPickCtrl): VNode {
  const povIndex = ctrl.series.povIndex ?? 0;
  const oppIndex = 1 - povIndex;
  const myPlayer = ctrl.series.players[povIndex];
  const oppPlayer = ctrl.series.players[oppIndex];
  const winnerIdx = ctrl.series.winner;
  const iWon = winnerIdx === povIndex;

  // Determine winner/loser for display order (winner on left)
  const winner = winnerIdx !== undefined ? ctrl.series.players[winnerIdx] : myPlayer;
  const loser = winnerIdx !== undefined ? ctrl.series.players[1 - winnerIdx] : oppPlayer;
  const winnerOnline = winnerIdx === povIndex ? true : ctrl.opponentOnline;
  const loserOnline = winnerIdx === povIndex ? ctrl.opponentOnline : true;

  const bannerClass = iWon ? 'victory' : 'defeat';
  const bannerText = iWon ? 'Victory!' : 'Defeat';

  return h('div.series-finished__header', [
    h(`div.series-finished__result-banner.${bannerClass}`, bannerText),
    h('div.series-finished__players', [
      renderPlayerScore(winner, winnerOnline),
      h('div.series-finished__vs', 'vs'),
      renderPlayerScore(loser, loserOnline),
    ]),
  ]);
}

function renderPlayerScore(player: { user?: { name: string; title?: string; id: string }; score: number; isOnline: boolean }, online: boolean): VNode {
  const user = player.user;
  return h('div.series-finished__player', [
    user
      ? userLink({ name: user.name, title: user.title, online, line: true })
      : h('span', 'Anonymous'),
    h('div.series-finished__score', String(player.score)),
  ]);
}

function renderFinishedScoreTable(ctrl: SeriesPickCtrl): VNode {
  const povIndex = ctrl.series.povIndex ?? 0;
  const oppIndex = 1 - povIndex;
  const myPlayer = ctrl.series.players[povIndex];
  const oppPlayer = ctrl.series.players[oppIndex];
  const myUser = myPlayer.user;
  const oppUser = oppPlayer.user;

  const bestOf = ctrl.series.bestOf;
  const finishedGames = ctrl.series.games.filter(g => g.result !== undefined).length;
  const displayRounds = Math.max(bestOf, finishedGames);

  return h('div.series-finished__score-table', [
    h('table.series-score__table', [
      h('thead', [
        h('tr', [
          h('th.series-score__header-game', 'Game'),
          h('th.series-score__header-me', myUser ? myUser.name : `Player ${povIndex + 1}`),
          h('th.series-score__header-opp', oppUser ? oppUser.name : `Player ${oppIndex + 1}`),
          h('th.series-score__header-opening', 'Opening'),
        ]),
      ]),
      h(
        'tbody',
        Array.from({ length: displayRounds }, (_, i) => {
          const round = i + 1;
          return renderScoreRow(ctrl, round, povIndex, oppIndex);
        }),
      ),
    ]),
    h('div.series-score__label', 'Opening Duel - Series Finished'),
  ]);
}

function renderScoreRow(ctrl: SeriesPickCtrl, round: number, povIndex: number, oppIndex: number): VNode {
  const game: SeriesGame | undefined = ctrl.series.games.find(g => g.round === round);
  const opening = game ? ctrl.series.openings.find(o => o.id === game.openingId) : undefined;

  let myResult = '-';
  let oppResult = '-';
  let myClass = 'pending';
  let oppClass = 'pending';

  if (game?.result) {
    const whiteIdx = game.whitePlayer;
    const blackIdx = 1 - whiteIdx;
    let winnerIndex: number | undefined;
    if (game.result === 'white') winnerIndex = whiteIdx;
    else if (game.result === 'black') winnerIndex = blackIdx;

    if (winnerIndex !== undefined) {
      myResult = winnerIndex === povIndex ? '1' : '0';
      myClass = winnerIndex === povIndex ? 'win' : 'loss';
      oppResult = winnerIndex === oppIndex ? '1' : '0';
      oppClass = winnerIndex === oppIndex ? 'win' : 'loss';
    } else {
      myResult = '\u00bd';
      myClass = 'draw';
      oppResult = '\u00bd';
      oppClass = 'draw';
    }
  }

  const gameLink = game ? `/${game.gameId}` : undefined;

  return h('tr.series-score__row', [
    h(
      'td.series-score__game',
      gameLink ? h('a', { attrs: { href: gameLink } }, String(round)) : h('span', String(round)),
    ),
    h('td.series-score__result', h(`span.${myClass}`, myResult)),
    h('td.series-score__result', h(`span.${oppClass}`, oppResult)),
    h(
      'td.series-score__opening',
      opening
        ? h('a.opening-link', { attrs: { href: opening.url || '#', target: '_blank' } }, opening.name)
        : h('span', '-'),
    ),
  ]);
}

function renderFinishedActions(ctrl: SeriesPickCtrl): VNode {
  const rematchNodes: VNode[] = [];

  if (ctrl.offeringRematch) {
    // I'm offering - show spinner
    rematchNodes.push(
      h('button.button.button-green.series-finished__rematch', { attrs: { disabled: true } }, [
        h('span.ddloader'),
        ' Rematch Offer Sent',
      ]),
    );
  } else if (ctrl.opponentOfferingRematch) {
    // Opponent is offering - show glowing button
    rematchNodes.push(
      h(
        'button.button.button-green.series-finished__rematch.glowing',
        {
          attrs: { title: 'Your opponent wants to play again!' },
          on: { click: () => ctrl.requestRematch() },
        },
        'Accept Rematch',
      ),
    );
  } else {
    // No offer yet
    const disabled = !ctrl.opponentOnline;
    rematchNodes.push(
      h(
        'button.button.button-green.series-finished__rematch',
        {
          attrs: {
            disabled,
            title: disabled ? 'Waiting for opponent to reconnect' : 'Offer a rematch',
          },
          on: { click: () => ctrl.requestRematch() },
        },
        'Rematch',
      ),
    );
  }

  rematchNodes.push(
    h(
      'a.button.button-metal.series-finished__home',
      { attrs: { href: '/' } },
      'Home',
    ),
  );

  return h('div.series-finished__actions', rematchNodes);
}
