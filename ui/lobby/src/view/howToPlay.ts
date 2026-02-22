import { h } from 'snabbdom';
import type { VNode } from 'snabbdom';

export default function renderHowToPlay(): VNode {
  return h('div.lobby__how-to-play', [renderIntro(), renderScoring(), renderBanPick(), renderPhases()]);
}

function renderIntro(): VNode {
  return h('section.how-to-play__section', [
    h('h2', 'What is Opening Duel?'),
    h('p', [
      'Opening Duel is a ',
      h('strong', 'best-of-5 series'),
      ' where you can only win by playing your chosen chess openings. Beat your opponent to ',
      h('strong', '2.5 points'),
      ' to win the series.',
    ]),
  ]);
}

function renderScoring(): VNode {
  return h('section.how-to-play__section', [
    h('h2', 'Scoring'),
    h('table.how-to-play__table', [
      h('tbody', [
        h('tr', [h('td.result.win', 'Win'), h('td', '1 point')]),
        h('tr', [h('td.result.draw', 'Draw'), h('td', '0.5 points')]),
        h('tr', [h('td.result.loss', 'Loss'), h('td', '0 points')]),
      ]),
    ]),
    h('p', [
      'First to ',
      h('strong', '2.5+ points'),
      ' and ahead in score wins the series. If tied at 2.5\u20132.5 after 5 games, a ',
      h('strong', 'Sudden Death'),
      ' game decides the winner.',
    ]),
  ]);
}

function renderBanPick(): VNode {
  return h('section.how-to-play__section', [
    h('h2', 'Ban / Pick Phase'),
    h('ol.how-to-play__steps', [
      h('li', [
        h('strong', 'Pick Phase (30s): '),
        'Select exactly 5 openings from a pool of 5\u201310. These become your arsenal for the series.',
      ]),
      h('li', [
        h('strong', 'Ban Phase (30s): '),
        "Ban exactly 2 of your opponent's picks. Banned openings are removed from the series entirely.",
      ]),
      h('li', [
        h('strong', 'Game 1: '),
        "A random opening is chosen from both players' remaining picks.",
      ]),
      h('li', [
        h('strong', 'Game 2+: '),
        'The loser of the previous game picks the next opening. After a draw, one is chosen randomly.',
      ]),
    ]),
    h('p.how-to-play__note', [
      h('em', 'If time runs out, your current selections are kept and the rest are filled in randomly.'),
    ]),
  ]);
}

function renderPhases(): VNode {
  return h('section.how-to-play__section', [
    h('h2', 'Series Phases'),
    h('div.how-to-play__phases', [
      phase('Picking', 'Both players choose 5 openings.'),
      phase('Banning', "Both players ban 2 of the opponent's picks."),
      phase('Random Selecting', 'A random opening is selected with a countdown.'),
      phase('Playing', 'A game of chess is in progress.'),
      phase('Resting', 'Short break between games (30s).'),
      phase('Selecting', 'The loser picks the next opening.'),
      phase('Finished', 'The series is over. The winner is declared.'),
    ]),
  ]);
}

function phase(name: string, description: string): VNode {
  return h('div.how-to-play__phase', [
    h('span.phase-name', name),
    h('span.phase-desc', description),
  ]);
}
