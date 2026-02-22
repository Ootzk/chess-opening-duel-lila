import { h } from 'snabbdom';
import { bind, type MaybeVNodes } from 'lib/view';
import type LobbyController from '../ctrl';
import type { Tab } from '../interfaces';

function tab(ctrl: LobbyController, key: Tab, active: Tab, content: MaybeVNodes) {
  return h(
    'span',
    {
      attrs: { role: 'tab' },
      class: { active: key === active },
      hook: bind('mousedown', _ => ctrl.setTab(key), ctrl.redraw),
    },
    content,
  );
}

export default function (ctrl: LobbyController) {
  const active = ctrl.tab;
  return [
    tab(ctrl, 'how_to_play', active, ['How to Play']),
    tab(ctrl, 'real_time', active, ['Lobby']),
  ];
}
