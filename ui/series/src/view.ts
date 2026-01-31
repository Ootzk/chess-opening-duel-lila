import { h } from 'snabbdom';
import type { VNode } from 'snabbdom';
import type SeriesPickCtrl from './ctrl';
import type { OpeningPreset } from './interfaces';

export default function view(ctrl: SeriesPickCtrl): VNode {
  if (ctrl.isShuffling) {
    return renderShuffling(ctrl);
  }
  return h('div.series-pick', [
    renderHeader(ctrl),
    renderGrid(ctrl),
    renderFooter(ctrl),
  ]);
}

function renderShuffling(ctrl: SeriesPickCtrl): VNode {
  const whiteBans = ctrl.series.bans.white;
  const blackBans = ctrl.series.bans.black;
  const whiteName = ctrl.series.players.white.user?.name || 'White';
  const blackName = ctrl.series.players.black.user?.name || 'Black';

  return h('div.series-pick.shuffling', [
    h('div.series-pick__header', [
      h('h1', 'Game 1 Starting...'),
      h('div.series-pick__countdown', String(ctrl.shufflingCountdown)),
    ]),
    h('div.series-pick__shuffling-boxes', [
      renderBanBox(ctrl, whiteName, whiteBans),
      renderBanBox(ctrl, blackName, blackBans),
    ]),
  ]);
}

function renderBanBox(ctrl: SeriesPickCtrl, playerName: string, bans: OpeningPreset[]): VNode {
  return h('div.series-pick__ban-box', [
    h('div.series-pick__ban-header', `${playerName}'s Bans`),
    h('div.series-pick__ban-openings', bans.map(opening => renderShufflingOpening(ctrl, opening))),
  ]);
}

function renderShufflingOpening(ctrl: SeriesPickCtrl, opening: OpeningPreset): VNode {
  const isHighlighted = ctrl.selectedOpening?.name === opening.name;

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