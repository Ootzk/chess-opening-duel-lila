import { initMiniBoards } from 'lib/view/miniBoard';
import menuHover from 'lib/menuHover';

interface RandomSelectingConfig {
  seriesId: string;
  phase: number;
  series: {
    openings: { name: string; fen: string; url: string }[];
    povColor?: 'white' | 'black';
    currentGame?: string;
    timeLeft?: number;
  };
}

export function initModule(config: RandomSelectingConfig): void {
  // Use server-provided timeLeft to prevent refresh abuse
  let countdown = config.series.timeLeft ?? 5;
  const countdownEl = document.querySelector('.series-pick__countdown');
  const gameId = config.series.currentGame;
  const povColor = config.series.povColor || 'white';

  // Update display immediately with server value (minimum 0)
  if (countdownEl) countdownEl.textContent = String(Math.max(0, countdown));

  // Initialize mini boards
  requestAnimationFrame(() => initMiniBoards());

  menuHover();

  // If already expired, redirect immediately
  if (countdown <= 0) {
    if (gameId) window.location.href = `/${gameId}/${povColor}`;
    return;
  }

  // Start countdown
  const interval = setInterval(() => {
    countdown--;
    if (countdown <= 0) {
      clearInterval(interval);
      if (countdownEl) countdownEl.textContent = '0';
      if (gameId) window.location.href = `/${gameId}/${povColor}`;
    } else {
      if (countdownEl) countdownEl.textContent = String(countdown);
    }
  }, 1000);
}
