import { initMiniBoards } from 'lib/view/miniBoard';
import menuHover from 'lib/menuHover';

interface RandomSelectingConfig {
  seriesId: string;
  phase: number;
  series: {
    openings: { name: string; fen: string; url: string }[];
    povColor?: 'white' | 'black';
    currentGame?: string;
  };
}

export function initModule(config: RandomSelectingConfig): void {
  let countdown = 3;
  const countdownEl = document.querySelector('.series-pick__countdown');
  const gameId = config.series.currentGame;
  const povColor = config.series.povColor || 'white';

  console.log('[randomSelecting] init, povColor:', povColor, 'currentGame:', gameId);

  // Initialize mini boards
  requestAnimationFrame(() => initMiniBoards());

  menuHover();

  // Start countdown
  const interval = setInterval(() => {
    countdown--;
    if (countdownEl) countdownEl.textContent = String(countdown);

    if (countdown <= 0) {
      clearInterval(interval);
      // Game is already created in confirmBans, just redirect
      if (gameId) {
        console.log('[randomSelecting] Redirecting to game:', gameId);
        window.location.href = `/${gameId}/${povColor}`;
      } else {
        console.error('[randomSelecting] No game ID found!');
      }
    }
  }, 1000);
}
