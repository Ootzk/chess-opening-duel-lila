import { init, attributesModule, eventListenersModule, classModule, propsModule, styleModule } from 'snabbdom';
import menuHover from 'lib/menuHover';
import { initMiniBoards } from 'lib/view/miniBoard';
import view from './view';
import type { PickConfig } from './interfaces';
import SeriesPickCtrl from './ctrl';

const patch = init([classModule, attributesModule, propsModule, eventListenersModule, styleModule]);

export function initModule(config: PickConfig): void {
  const ctrl = new SeriesPickCtrl(config, redraw);
  const element = document.querySelector('main.series-pick')!;
  element.innerHTML = '';
  const inner = document.createElement('div');
  element.appendChild(inner);
  let vnode = patch(inner, view(ctrl));

  function redraw(): void {
    vnode = patch(vnode, view(ctrl));
    // Re-initialize mini boards after each redraw
    requestAnimationFrame(() => initMiniBoards());
  }

  // Initial mini board initialization
  requestAnimationFrame(() => initMiniBoards());

  menuHover();

  // Start timers after vnode is initialized (prevents "vnode not initialized" error)
  ctrl.init();

  // Cleanup on page unload
  window.addEventListener('beforeunload', () => ctrl.destroy());
}