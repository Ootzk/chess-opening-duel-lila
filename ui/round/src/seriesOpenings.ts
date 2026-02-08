import { h, type VNode } from 'snabbdom';
import { bind } from 'lib/view';
import { initMiniBoards } from 'lib/view/miniBoard';
import type { ChatPlugin } from 'lib/chat/interfaces';
import type { Series, SeriesOpeningInfo, Player } from 'lib/game';

export interface SeriesOpeningsCtrl extends ChatPlugin {
  set(series: Series): void;
}

export const seriesOpeningsCtrl = (series: Series, player: Player, opponent: Player): SeriesOpeningsCtrl => {
  let activeSubTab = 0; // 0=me, 1=opponent, 2=neutral

  const povIndex = series.povIndex ?? 0;
  const oppIndex = 1 - povIndex;

  const remainingPicks = (playerIndex: number): SeriesOpeningInfo[] => {
    const picks = series.openings.filter(o => o.owner === playerIndex && o.source === 'pick');
    const oppBanNames = new Set(
      series.openings.filter(o => o.owner === (1 - playerIndex) && o.source === 'ban').map(o => o.name),
    );
    return picks.filter(p => !oppBanNames.has(p.name) && !p.usedInRound);
  };

  const neutralPool = (): SeriesOpeningInfo[] =>
    series.openings.filter(o => (o.source === 'ban' || o.source === 'neutral') && !o.usedInRound);

  const openingsForTab = (tab: number): SeriesOpeningInfo[] => {
    if (tab === 0) return remainingPicks(povIndex);
    if (tab === 1) return remainingPicks(oppIndex);
    return neutralPool();
  };

  const tabLabel = (tab: number): VNode[] => {
    if (tab === 2) return [h('span', 'Neutral')];
    const user = tab === 0 ? player.user : opponent.user;
    if (!user) return [h('span', tab === 0 ? 'Player 1' : 'Player 2')];
    const nodes: VNode[] = [];
    if (user.title) nodes.push(h('span.utitle', user.title + '\xa0'));
    nodes.push(h('span', user.username));
    if (user.flair) nodes.push(h('img.uflair', { attrs: { src: site.asset.flairSrc(user.flair) } }));
    return nodes;
  };

  const renderCard = (opening: SeriesOpeningInfo): VNode =>
    h('div.series-openings__card', [
      h(
        'a.series-openings__board.mini-board.mini-board--init.cg-wrap.is2d',
        {
          attrs: {
            'data-state': `${opening.fen},white,`,
            ...(opening.url ? { href: opening.url, target: '_blank' } : {}),
          },
        },
        [h('cg-container', [h('cg-board')])],
      ),
      h('div.series-openings__name', opening.name),
    ]);

  const initBoards = (_: VNode | undefined, vnode: VNode) =>
    requestAnimationFrame(() => initMiniBoards(vnode.elm as HTMLElement));

  const renderGrid = (tab: number, openings: SeriesOpeningInfo[]): VNode =>
    openings.length
      ? h('div.series-openings__grid', { key: `grid-${tab}`, hook: { insert: v => initBoards(undefined, v) } }, openings.map(renderCard))
      : h('div.series-openings__empty', { key: `empty-${tab}` }, 'No openings remaining');

  const ctrl: SeriesOpeningsCtrl = {
    set(s: Series) {
      series = s;
    },
    key: 'seriesOpenings',
    name: 'Openings',
    redraw: undefined,
    view(): VNode {
      return h('div.series-openings', {
        hook: {
          insert: () => site.asset.loadCssPath('round.series-openings'),
        },
      }, [
        h(
          'div.series-openings__tabs',
          [0, 1, 2].map(i =>
            h(
              'div.series-openings__tab',
              {
                class: { active: activeSubTab === i },
                hook: bind('click', () => {
                  activeSubTab = i;
                  ctrl.redraw?.();
                }),
              },
              tabLabel(i),
            ),
          ),
        ),
        renderGrid(activeSubTab, openingsForTab(activeSubTab)),
      ]);
    },
  };

  return ctrl;
};
