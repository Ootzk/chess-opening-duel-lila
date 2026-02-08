import { init, attributesModule, eventListenersModule, classModule, propsModule, styleModule } from 'snabbdom';
import menuHover from 'lib/menuHover';
import { initMiniBoards } from 'lib/view/miniBoard';
import { wsConnect, wsSend } from 'lib/socket';
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

  // Connect to series WebSocket
  wsConnect(`/series/${config.seriesId}/socket/v5`, config.socketVersion ?? 0, {
    events: {
      reload: () => ctrl.handleReload(),
      confirmed: (d: { player: number; phase: string }) => ctrl.handleConfirmed(d),
      phase: (d: { phase: number; gameId?: string }) => ctrl.handlePhase(d),
      gone: (d: { player: number; gone: boolean }) => ctrl.handleGone(d),
      aborted: () => ctrl.handleAborted(),
      rematchOffer: (d: { player: number }) => ctrl.handleRematchOffer(d),
      rematchTaken: (d: { newSeriesId: string }) => ctrl.handleRematchTaken(d),
    },
  });

  // Send ping every 3 seconds for lastSeen tracking
  const pingInterval = setInterval(() => {
    wsSend('seriesPing');
  }, 3000);

  // Start timers after vnode is initialized (prevents "vnode not initialized" error)
  ctrl.init();

  // Cleanup on page unload
  window.addEventListener('beforeunload', () => {
    ctrl.destroy();
    clearInterval(pingInterval);
  });
}