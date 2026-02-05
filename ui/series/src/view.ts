import { h } from 'snabbdom';
import type { VNode } from 'snabbdom';
import type SeriesPickCtrl from './ctrl';
import type { OpeningPreset, SeriesOpening } from './interfaces';
import { getOpponentBans, getMyBans, getNeutralOpening } from './interfaces';

export default function view(ctrl: SeriesPickCtrl): VNode {
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
      h('div.series-pick__neutral-empty', 'Used'),
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
      h('div.series-pick__name', [
        h('span.opening-name', preset.name),
        isSelected ? h('span.opening-check', { class: { green: ctrl.isPicking, red: ctrl.isBanning } }) : null,
      ]),
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
  const buttons: VNode[] = [];

  if (ctrl.isWaiting) {
    // Waiting for opponent - show waiting message and cancel button
    buttons.push(
      h('div.series-pick__waiting', [
        h('span.ddloader'),
        h('span', 'Waiting for opponent...'),
      ]),
    );
    buttons.push(
      h(
        'button.button.button-red',
        {
          on: {
            click: () => ctrl.cancelConfirm(),
          },
        },
        'Cancel',
      ),
    );
  } else {
    // Normal confirm button
    buttons.push(
      h(
        'button.button.button-green',
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

  // Show opponent status
  if (ctrl.opponentConfirmed && !ctrl.myConfirmed) {
    buttons.push(h('div.series-pick__opponent-ready', 'Opponent is ready!'));
  }

  return h('div.series-pick__actions', buttons);
}
