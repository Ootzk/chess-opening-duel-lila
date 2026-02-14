import { h } from 'snabbdom';
import type { VNode } from 'snabbdom';
import type SeriesPickCtrl from './ctrl';
import type { OpeningPreset, SeriesOpening, SeriesGame, SeriesPlayer } from './interfaces';
import { userLink } from 'lib/view/userLink';

export default function view(ctrl: SeriesPickCtrl): VNode {
  if (ctrl.showcasePhase === 'selecting-showcase') {
    return renderSelectingShowcase(ctrl);
  }
  if (ctrl.isFinished) {
    return renderFinished(ctrl);
  }
  if (ctrl.isRandomSelecting) {
    return renderRandomSelecting(ctrl);
  }
  // Unified view: both players see the same grid (including Selecting phase)
  return h('div.series-pick', [
    renderHeader(ctrl),
    renderGrid(ctrl),
    renderFooter(ctrl),
  ]);
}

function renderRandomSelecting(ctrl: SeriesPickCtrl): VNode {
  const gameNum = ctrl.series.round;

  if (ctrl.roulettePhase === 'result' && ctrl.selectedOpening) {
    // Result view: showcase the selected opening
    const ownerIdx = ctrl.selectedOpening.owner;
    const ownerPlayer = ctrl.series.players[ownerIdx];
    return renderOpeningShowcase(ctrl.selectedOpening, ownerPlayer, ctrl.randomSelectingCountdown, gameNum);
  }

  // Spinning view: two player boxes (POV player first)
  const cards = ctrl.rouletteCards;
  const povIdx = ctrl.series.povIndex ?? 0;
  const oppIdx = 1 - povIdx;
  const myCards = cards.filter(c => c.owner === povIdx);
  const oppCards = cards.filter(c => c.owner === oppIdx);

  return h('div.series-pick.random-selecting', [
    h('div.series-pick__header', [h('h1', `Game ${gameNum} Starting...`)]),
    h('div.series-pick__player-boxes', [
      renderRoulettePlayerBox(ctrl, ctrl.series.players[povIdx], myCards),
      renderRoulettePlayerBox(ctrl, ctrl.series.players[oppIdx], oppCards),
    ]),
  ]);
}

function renderRoulettePlayerBox(ctrl: SeriesPickCtrl, player: SeriesPlayer, cards: SeriesOpening[]): VNode {
  const user = player.user;
  return h('div.series-pick__player-box', [
    h(
      'div.series-pick__player-header',
      user
        ? userLink({ name: user.name, title: user.title, flair: user.flair, online: player.isOnline, line: false })
        : h('span', `Player ${player.index + 1}`),
    ),
    h(
      'div.series-pick__player-cards',
      cards.map(opening => {
        const globalIdx = ctrl.rouletteCards.indexOf(opening);
        const ownerColorClass = `owner-${opening.ownerColor || 'white'}`;
        return h(
          `div.series-pick__opening.series-pick__roulette-card.${ownerColorClass}`,
          {
            key: opening.id,
            class: { 'roulette-active': globalIdx === ctrl.rouletteHighlightIndex },
          },
          [
            h(
              'div.series-pick__board.mini-board.mini-board--init.cg-wrap.is2d',
              { attrs: { 'data-state': `${opening.fen},${opening.ownerColor || 'white'},` } },
              [h('cg-container', [h('cg-board')])],
            ),
            h('div.series-pick__name', [h('span.opening-name', { attrs: { title: opening.name } }, opening.name)]),
          ],
        );
      }),
    ),
  ]);
}

function renderSelectingShowcase(ctrl: SeriesPickCtrl): VNode {
  const gameNum = ctrl.series.round; // round = finished games + 1 (already the next game number)
  const selectingIdx = ctrl.series.selectingPlayer ?? 0;
  const selectingPlayer = ctrl.series.players[selectingIdx];

  if (ctrl.showcaseOpening) {
    return renderOpeningShowcase(ctrl.showcaseOpening, selectingPlayer, ctrl.showcaseCountdown, gameNum);
  }

  // Fallback: no opening data available
  return h('div.series-pick.random-selecting', [
    h('div.series-pick__header', [
      h('h1', `Game ${gameNum} Starting...`),
      h('div.series-pick__countdown', String(ctrl.showcaseCountdown)),
    ]),
  ]);
}

/** Shared showcase component: enlarged card + "{player}'s {opening} selected!" + countdown */
function renderOpeningShowcase(
  opening: SeriesOpening,
  player: SeriesPlayer,
  countdown: number,
  gameNum: number,
): VNode {
  const user = player.user;
  const ownerColorClass = `owner-${opening.ownerColor || 'white'}`;

  return h('div.series-pick.random-selecting', [
    h('div.series-pick__header', [h('h1', `Game ${gameNum} Starting...`)]),
    h(`div.series-pick__selected-showcase.${ownerColorClass}`, [
      h(
        'div.series-pick__board.mini-board.mini-board--init.cg-wrap.is2d',
        { attrs: { 'data-state': `${opening.fen},${opening.ownerColor || 'white'},` } },
        [h('cg-container', [h('cg-board')])],
      ),
      h('div.series-pick__name', [h('span.opening-name', { attrs: { title: opening.name } }, opening.name)]),
    ]),
    h('div.series-pick__selected-text', [
      user
        ? userLink({ name: user.name, title: user.title, flair: user.flair, online: true, line: false })
        : h('span', `Player ${player.index + 1}`),
      h('span', "'s "),
      h(`span.opening-name-highlight.${ownerColorClass}`, opening.name),
      h('span', ' selected!'),
    ]),
    h('div.series-pick__countdown', String(countdown)),
  ]);
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
  const isOpponentSelecting = ctrl.isSelecting && !ctrl.isMyTurnToSelect && preset.name === ctrl.opponentSelectingPick;
  const isDisabled = ctrl.isSelecting
    ? !ctrl.isMyTurnToSelect || ctrl.myConfirmed  // Loser can't click; confirmed winner can't click
    : ctrl.myConfirmed || (!isSelected && !canSelect);
  const ownerColorClass = preset.ownerColor ? `owner-${preset.ownerColor}` : '';

  return h(
    `div.series-pick__opening${ownerColorClass ? '.' + ownerColorClass : ''}`,
    {
      class: {
        selected: isSelected,
        disabled: isDisabled,
        'my-pick': isSelected && ctrl.isPicking,
        'my-ban': isSelected && ctrl.isBanning,
        'opponent-selecting': isOpponentSelecting,
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
            'data-state': `${preset.fen},${preset.ownerColor || 'white'},`,
          },
        },
        [h('cg-container', [h('cg-board')])],
      ),
      h('div.series-pick__name', [h('span.opening-name', { attrs: { title: preset.name } }, preset.name)]),
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

  if (ctrl.isSelecting && !ctrl.isMyTurnToSelect) {
    // Winner: no button (just watches)
  } else if (ctrl.isWaiting) {
    // Confirmed (with or without countdown): show Cancel button
    leftSide.push(
      h(
        'button.button.button-metal.series-pick__action-btn',
        { on: { click: () => ctrl.cancelConfirm() } },
        'Cancel',
      ),
    );
  } else {
    // Not confirmed: show Confirm button
    leftSide.push(
      h(
        `${buttonClass}.series-pick__action-btn`,
        {
          attrs: { disabled: !ctrl.canConfirm },
          on: { click: () => ctrl.confirm() },
        },
        ctrl.confirmButtonText,
      ),
    );
  }

  // Right side: Opponent status
  if (ctrl.isPicking || ctrl.isBanning || ctrl.isSelecting) {
    const povIndex = ctrl.series.povIndex ?? 0;
    const oppIndex = 1 - povIndex;
    const opponent = ctrl.series.players[oppIndex];
    const oppUser = opponent?.user;

    let statusText: string;
    let statusClass: string;

    if (ctrl.isSelecting) {
      if (ctrl.isMyTurnToSelect) {
        // I'm the loser selecting — show opponent (winner) status
        statusClass = 'waiting';
        statusText = ' is watching...';
      } else {
        // I'm the loser — show opponent (winner) status
        statusClass = ctrl.opponentConfirmed ? 'ready' : 'waiting';
        statusText = ctrl.opponentConfirmed ? ' confirmed!' : ' is selecting...';
      }
    } else {
      statusClass = ctrl.opponentConfirmed ? 'ready' : 'waiting';
      statusText = ctrl.opponentConfirmed ? ' is Ready!' : ' is selecting...';
    }

    const statusChildren: VNode[] = [
      oppUser
        ? userLink({ name: oppUser.name, title: oppUser.title, online: ctrl.opponentOnline, line: true })
        : h('span', `Player ${oppIndex + 1}`),
      h('span.status-text', statusText),
    ];

    // Second line: countdown text when both confirmed
    if (ctrl.countdownActive) {
      statusChildren.push(h('div.series-pick__countdown-text', ctrl.confirmButtonText));
    }

    rightSide.push(h(`div.series-pick__opponent-status.${statusClass}`, statusChildren));
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

  const isForfeit = ctrl.series.forfeitBy !== undefined;
  const isDraw = winnerIdx == null;
  const bannerClass = isDraw ? 'draw' : iWon ? 'victory' : 'defeat';
  const bannerText = isDraw
    ? 'Draw'
    : iWon
      ? isForfeit ? 'Victory! (forfeit)' : 'Victory!'
      : isForfeit ? 'Defeat (forfeit)' : 'Defeat';

  return h('div.series-finished__header', [
    h(`div.series-finished__result-banner.${bannerClass}`, bannerText),
    h('div.series-finished__players', [
      renderPlayerScore(myPlayer, true),
      h('div.series-finished__vs', 'vs'),
      renderPlayerScore(oppPlayer, ctrl.opponentOnline),
    ]),
  ]);
}

function renderPlayerScore(player: SeriesPlayer, online: boolean): VNode {
  const user = player.user;
  return h('div.series-finished__player', [
    user
      ? userLink({ name: user.name, title: user.title, flair: user.flair, online, line: true })
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
          h(
            'th.series-score__header-me',
            myUser
              ? userLink({ name: myUser.name, title: myUser.title, flair: myUser.flair, online: false, line: false })
              : `Player ${povIndex + 1}`,
          ),
          h(
            'th.series-score__header-opp',
            oppUser
              ? userLink({ name: oppUser.name, title: oppUser.title, flair: oppUser.flair, online: false, line: false })
              : `Player ${oppIndex + 1}`,
          ),
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
