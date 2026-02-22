import { h, type VNodeData } from 'snabbdom';
import { spinnerVdom as spinner } from 'lib/view';
import renderRealTime from './realTime/main';
import renderPlaying from './playing';
import renderTabs from './tabs';
import renderHowToPlay from './howToPlay';
import type LobbyController from '../ctrl';

export default function (ctrl: LobbyController) {
  let body;
  const data: VNodeData = {};
  if (ctrl.redirecting) body = spinner();
  else if (ctrl.tab === 'now_playing') body = renderPlaying(ctrl);
  else if (ctrl.tab === 'how_to_play') body = renderHowToPlay();
  else body = renderRealTime(ctrl);
  const contentKey = ctrl.tab === 'real_time' ? `${ctrl.tab}-${ctrl.mode}` : ctrl.tab;
  return h(`div.lobby__app.lobby__app-${ctrl.tab}.lck-${contentKey}`, [
    h('div.tabs-horiz', { attrs: { role: 'tablist' } }, renderTabs(ctrl)),
    h(`div.lobby__app__content.l${ctrl.redirecting ? 'redir' : ctrl.tab}`, data, body),
  ]);
}
