import { h } from 'snabbdom';
import type { VNode } from 'snabbdom';
import type SeriesPickCtrl from './ctrl';
import type { OpeningPreset, SeriesOpening, SeriesGame, SeriesPlayer } from './interfaces';
import { userLink } from 'lib/view/userLink';

export default function view(ctrl: SeriesPickCtrl): VNode {
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
  const povIndex = ctrl.series.povIndex ?? 0;
  const oppIndex = 1 - povIndex;
  const myPlayer = ctrl.series.players[povIndex];
  const oppPlayer = ctrl.series.players[oppIndex];
  const gameNum = ctrl.series.round;

  // Find which opening was selected for the current round's game
  const currentRoundGame = ctrl.series.games.find(g => g.round === gameNum);
  const highlightedId = currentRoundGame?.openingId;

  // Include the current round's opening (getRemainingPicks excludes it via usedInRound filter)
  const picksForDisplay = (playerIndex: number): SeriesOpening[] => {
    const picks = ctrl.series.openings.filter(o => o.owner === playerIndex && o.source === 'pick');
    const oppBanNames = new Set(
      ctrl.series.openings.filter(o => o.owner === (1 - playerIndex) && o.source === 'ban').map(o => o.name),
    );
    return picks.filter(p => !oppBanNames.has(p.name) && (!p.usedInRound || p.usedInRound === gameNum));
  };
  const myPicks = picksForDisplay(povIndex);
  const oppPicks = picksForDisplay(oppIndex);

  return h('div.series-pick.random-selecting', [
    h('div.series-pick__header', [
      h('h1', `Game ${gameNum} Starting...`),
      h('div.series-pick__countdown', String(ctrl.randomSelectingCountdown)),
    ]),
    h('div.series-pick__pick-boxes', [
      renderPickBox(myPicks, myPlayer, true, highlightedId),
      renderPickBox(oppPicks, oppPlayer, false, highlightedId),
    ]),
  ]);
}

function renderPickBox(
  picks: SeriesOpening[],
  player: SeriesPlayer,
  isMe: boolean,
  highlightedId?: string,
): VNode {
  const user = player.user;
  return h('div.series-pick__pick-box', [
    h('div.series-pick__pick-header', [
      user
        ? userLink({ name: user.name, title: user.title, flair: user.flair, online: true, line: false })
        : h('span', isMe ? 'My Picks' : 'Opponent Picks'),
    ]),
    h(
      'div.series-pick__pick-openings',
      picks.map(opening => renderRandomSelectingOpening(opening, highlightedId)),
    ),
  ]);
}

function renderRandomSelectingOpening(
  opening: SeriesOpening,
  highlightedId?: string,
): VNode {
  const isHighlighted = highlightedId === opening.id;
  const ownerColorClass = `owner-${opening.ownerColor || 'white'}`;

  return h(
    `div.series-pick__opening.${ownerColorClass}`,
    {
      class: { highlighted: isHighlighted },
      attrs: { 'data-name': opening.name, 'data-fen': opening.fen },
    },
    [
      h(
        'div.series-pick__board.mini-board.mini-board--init.cg-wrap.is2d',
        {
          attrs: { 'data-state': `${opening.fen},${opening.ownerColor || 'white'},` },
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
  const bannerClass = iWon ? 'victory' : 'defeat';
  const bannerText = iWon
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
