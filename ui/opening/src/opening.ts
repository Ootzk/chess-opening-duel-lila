import Lpv from '@lichess-org/pgn-viewer';
import type { Opts } from '@lichess-org/pgn-viewer/interfaces';
import { initMiniBoards } from 'lib/view';
import { requestIdleCallback } from 'lib';
import { text as xhrText } from 'lib/xhr';
import type { OpeningPage } from './interfaces';
import { renderHistoryChart } from './chart';
import { init as searchEngine } from './search';
import panels from './panels';
import renderPlaceholderWiki from './wiki';

export function initModule(data?: OpeningPage): void {
  initPoolHandlers();
  data ? page(data) : searchEngine();
}

function page(data: OpeningPage) {
  $('.opening__intro .lpv').each(function (this: HTMLElement) {
    Lpv(this, {
      pgn: this.dataset['pgn']!,
      initialPly: 'last',
      showMoves: 'bottom',
      showClocks: false,
      showPlayers: false,
      chessground: cgConfig,
      menu: {
        getPgn: {
          enabled: true,
          fileName: (this.dataset['title'] || this.dataset['pgn'] || 'opening').replace(' ', '_') + '.pgn',
        },
      },
    });
  });
  initMiniBoards();
  highlightNextPieces();
  panels($('.opening__panels'), id => {
    if (id === 'opening-panel-games') loadExampleGames();
  });
  searchEngine();
  requestIdleCallback(() => {
    renderHistoryChart(data);
    renderPlaceholderWiki(data);
  });
}

const cgConfig: Opts['chessground'] = {
  coordinates: false,
};

const loadExampleGames = () =>
  $('.opening__games .lpv--todo')
    .removeClass('lpv--todo')
    .each(function (this: HTMLElement) {
      Lpv(this, {
        pgn: this.dataset['pgn']!,
        initialPly: parseInt(this.dataset['ply'] || '99'),
        showMoves: 'bottom',
        showClocks: false,
        showPlayers: true,
        chessground: cgConfig,
        menu: {
          getPgn: {
            enabled: true,
            fileName: (this.dataset['title'] || 'game').replace(' ', '_') + '.pgn',
          },
        },
      });
    });

const highlightNextPieces = () => {
  $('.opening__next cg-board').each(function (this: HTMLElement) {
    Array.from($(this).find('.last-move'))
      .map(el => el!.style.transform)
      .forEach(transform => {
        $(this).find(`piece[style="transform: ${transform};"]`).addClass('highlight');
      });
  });
};

function initPoolHandlers(): void {
  // Remove button (event delegation)
  $(document).on('click', '.opening__pool__remove:not([disabled])', function (this: HTMLButtonElement) {
    const id = this.dataset['id'];
    const color = this.dataset['color'];
    if (!id || !color) return;
    xhrText(`/opening-pool/remove/${id}/${color}`, {
      method: 'POST',
    }).then(html => {
      $('.opening__pool').replaceWith(html);
      updateAddButtonStates();
    });
  });

  // Add button
  $(document).on('click', '.opening__pool-add__btn:not([disabled])', function (this: HTMLButtonElement) {
    const btn = this;
    const { openingName, openingFen, openingUrl, color } = btn.dataset;
    xhrText('/opening-pool/add', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ name: openingName, fen: openingFen, url: openingUrl, color }),
    }).then(
      html => {
        const pool = $('.opening__pool');
        if (pool.length) pool.replaceWith(html);
        else $('.opening__search-config').before(html);
        updateAddButtonStates();
      },
      () => {
        btn.disabled = true;
        btn.classList.add('disabled');
      },
    );
  });
}

function updateAddButtonStates(): void {
  const poolEntries = new Set<string>();
  let poolSize = 0;
  $('.opening__pool__row').each(function (this: HTMLTableRowElement) {
    const name = $(this).find('.opening__pool__opening a').text().trim();
    const color = this.classList.contains('opening__pool__row--white') ? 'white' : 'black';
    if (name) {
      poolEntries.add(`${name}:${color}`);
      poolSize++;
    }
  });
  const isFull = poolSize >= 10;
  $('.opening__pool-add__btn').each(function (this: HTMLButtonElement) {
    const name = this.dataset['openingName'] || '';
    const color = this.dataset['color'] || '';
    const inPool = poolEntries.has(`${name}:${color}`);
    const isImbalanced = this.dataset['imbalanced'] === 'true';
    const dis = inPool || isFull || isImbalanced;
    this.disabled = dis;
    this.classList.toggle('disabled', dis);
    if (isImbalanced) return; // server-rendered tooltip preserved
    if (inPool) this.title = 'Already in your pool';
    else if (isFull) this.title = 'Pool is full (10/10)';
    else this.removeAttribute('title');
  });
}
