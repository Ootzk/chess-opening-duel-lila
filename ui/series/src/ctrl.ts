import type { PickConfig, SeriesData, OpeningPreset, SeriesOpening } from './interfaces';
import {
  Phase as PhaseId,
  Status as StatusId,
  getMyPicks,
  getOpponentPicks,
  getMyBans,
  getRemainingPicks,
  getAllBans,
} from './interfaces';

export default class SeriesPickCtrl {
  seriesId: string;
  phase: number;
  presets: OpeningPreset[];
  series: SeriesData;
  selectedPicks: Set<string> = new Set();
  selectedBans: Set<string> = new Set();
  timeLeft: number;
  timerInterval?: number;
  myConfirmed: boolean = false;
  opponentConfirmed: boolean = false;
  opponentOnline: boolean = true;

  // RandomSelecting phase state
  selectedOpening: SeriesOpening | null = null;
  randomSelectingCountdown: number = 5;
  gameId: string | null = null;

  constructor(
    readonly config: PickConfig,
    readonly redraw: () => void,
  ) {
    this.seriesId = config.seriesId;
    this.phase = config.phase;
    this.presets = config.presets;
    this.series = config.series;

    // Use server-calculated timeLeft to prevent refresh abuse
    this.timeLeft = config.series.timeLeft ?? 30;
    this.randomSelectingCountdown = config.series.timeLeft ?? 5;

    // Initialize selections and confirmed state from series data
    this.initFromSeries();

    // NOTE: Don't start timers here - call init() after vnode is set up
  }

  // Call this after vnode initialization in series.pick.ts
  init(): void {
    if (this.isRandomSelecting) {
      this.startRandomSelecting();
    } else if (this.isSelecting && this.isWaitingForOpponentSelect) {
      // Winner waiting for loser to select - WebSocket will notify us
      this.startTimer();
    } else {
      // Start timer for pick/ban phases and selecting
      // If already timed out (timeLeft <= 0), trigger timeout immediately
      if (this.timeLeft <= 0) {
        this.onTimeout();
      } else {
        this.startTimer();
        // WebSocket handles opponent status updates (pick/ban phases)
      }
    }
  }

  private initFromSeries(): void {
    const povIndex = this.series.povIndex;
    if (povIndex === undefined) return;

    const oppIndex = 1 - povIndex;

    // Load existing picks
    const myPicks = getMyPicks(this.series);
    myPicks.forEach(p => this.selectedPicks.add(p.name));

    // Load existing bans
    const myBans = getMyBans(this.series);
    myBans.forEach(b => this.selectedBans.add(b.name));

    // Load confirmed state
    if (this.isPicking) {
      this.myConfirmed = this.series.players[povIndex].confirmedPicks;
      this.opponentConfirmed = this.series.players[oppIndex].confirmedPicks;
    } else if (this.isBanning) {
      this.myConfirmed = this.series.players[povIndex].confirmedBans;
      this.opponentConfirmed = this.series.players[oppIndex].confirmedBans;
    }

    // Load opponent online status
    this.opponentOnline = this.series.players[oppIndex].isOnline;
  }

  private startTimer(): void {
    this.timerInterval = window.setInterval(() => {
      if (this.timeLeft > 0) {
        this.timeLeft--;
        this.redraw();
      } else {
        this.onTimeout();
      }
    }, 1000);
  }

  private onTimeout(): void {
    if (this.timerInterval) {
      clearInterval(this.timerInterval);
    }
    // Selecting phase: 타임아웃 시 랜덤 선택
    if (this.isSelecting && this.isMyTurnToSelect) {
      this.selectRandomOpening();
      return;
    }
    // Pick/Ban phase: 타임아웃 시 현재 선택 + 랜덤 채우기 + 자동 확정
    if (!this.myConfirmed) {
      if (this.isPicking) {
        this.timeoutPicks();
      } else if (this.isBanning) {
        this.timeoutBans();
      }
    }
  }

  // Pick 타임아웃: 현재 선택 + 랜덤 채우기 + 자동 확정
  private async timeoutPicks(): Promise<void> {
    try {
      const selections = Array.from(this.selectedPicks);
      const response = await fetch(`/series/${this.seriesId}/timeoutPicks`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(selections),
      });
      if (response.ok) {
        const data = await response.json();
        this.myConfirmed = data.myConfirmed ?? true;
        this.opponentConfirmed = data.opponentConfirmed ?? false;
        const newPhase = Number(data.phase);
        if (newPhase !== this.phase) {
          window.location.reload();
        }
        // WebSocket will notify us when opponent confirms
      }
    } catch (e) {
      console.error('Error timeout picks:', e);
    }
    this.redraw();
  }

  // Ban 타임아웃: 현재 선택 + 랜덤 채우기 + 자동 확정
  private async timeoutBans(): Promise<void> {
    try {
      const selections = Array.from(this.selectedBans);
      const response = await fetch(`/series/${this.seriesId}/timeoutBans`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(selections),
      });
      if (response.ok) {
        const data = await response.json();
        this.myConfirmed = data.myConfirmed ?? true;
        this.opponentConfirmed = data.opponentConfirmed ?? false;
        const newPhase = Number(data.phase);
        if (newPhase !== this.phase) {
          if (newPhase === PhaseId.RandomSelecting) {
            window.location.href = `/series/${this.seriesId}/random-selecting`;
          } else {
            window.location.reload();
          }
        }
        // WebSocket will notify us when opponent confirms
      }
    } catch (e) {
      console.error('Error timeout bans:', e);
    }
    this.redraw();
  }

  // Selecting 타임아웃 시 랜덤 선택
  private async selectRandomOpening(): Promise<void> {
    try {
      const response = await fetch(`/series/${this.seriesId}/selectRandom`, {
        method: 'POST',
      });
      if (response.ok) {
        const data = await response.json();
        if (data.redirect) {
          window.location.href = data.redirect;
        }
      }
    } catch (e) {
      console.error('Error selecting random opening:', e);
    }
  }

  get isPicking(): boolean {
    return this.phase === PhaseId.Picking;
  }

  get isBanning(): boolean {
    return this.phase === PhaseId.Banning;
  }

  get isSelecting(): boolean {
    return this.phase === PhaseId.Selecting;
  }

  get isRandomSelecting(): boolean {
    return this.phase === PhaseId.RandomSelecting;
  }

  get isWaiting(): boolean {
    // 본인이 확정했으면 Cancel 가능 (양측 모두 확정 후 3초 대기 중에도)
    return this.myConfirmed;
  }

  // Selecting phase: 상대가 선택 중인지 (내가 승자인지)
  get isWaitingForOpponentSelect(): boolean {
    if (!this.isSelecting) return false;
    const povIndex = this.series.povIndex;
    const selectingPlayer = this.series.selectingPlayer;
    return selectingPlayer !== undefined && selectingPlayer !== povIndex;
  }

  // Selecting phase: 내가 선택해야 하는지 (내가 패자인지)
  get isMyTurnToSelect(): boolean {
    if (!this.isSelecting) return false;
    const povIndex = this.series.povIndex;
    const selectingPlayer = this.series.selectingPlayer;
    return selectingPlayer === povIndex;
  }

  get maxPicks(): number {
    return 5;
  }

  get maxBans(): number {
    return 2;
  }

  get currentSelections(): Set<string> {
    return this.isPicking ? this.selectedPicks : this.selectedBans;
  }

  get maxSelections(): number {
    return this.isPicking ? this.maxPicks : this.maxBans;
  }

  // Get openings that can be selected in current phase
  get availableOpenings(): OpeningPreset[] {
    if (this.isPicking) {
      return this.presets;
    } else if (this.isBanning) {
      // Can only ban from opponent's picks
      const oppPicks = getOpponentPicks(this.series);
      return oppPicks.map(o => ({ name: o.name, fen: o.fen, url: o.url || '' }));
    } else if (this.isSelecting) {
      // Can only select from own remaining picks
      const povIndex = this.series.povIndex ?? 0;
      const remaining = getRemainingPicks(this.series, povIndex);
      return remaining.map(o => ({ name: o.name, fen: o.fen, url: o.url || '' }));
    }
    return [];
  }

  isSelected(name: string): boolean {
    return this.currentSelections.has(name);
  }

  isOpponentPick(name: string): boolean {
    if (!this.isBanning) return false;
    const oppPicks = getOpponentPicks(this.series);
    return oppPicks.some(p => p.name === name);
  }

  canSelect(name: string): boolean {
    if (this.myConfirmed) return false;
    if (this.isPicking) {
      return !this.isSelected(name) && this.selectedPicks.size < this.maxPicks;
    } else if (this.isBanning) {
      return this.isOpponentPick(name) && !this.isSelected(name) && this.selectedBans.size < this.maxBans;
    } else if (this.isSelecting) {
      // Only the loser can select
      if (!this.isMyTurnToSelect) return false;
      return this.availableOpenings.some(o => o.name === name);
    }
    return false;
  }

  toggleSelection(name: string): void {
    if (this.myConfirmed) return;

    const selections = this.currentSelections;
    if (selections.has(name)) {
      selections.delete(name);
    } else if (this.canSelect(name) || this.isSelected(name)) {
      if (this.isSelecting) {
        // In selecting phase, only one can be selected
        selections.clear();
      }
      selections.add(name);
    }

    this.sendSelections();
    this.redraw();
  }

  private async sendSelections(): Promise<void> {
    const endpoint = this.isPicking
      ? `/series/${this.seriesId}/picks`
      : `/series/${this.seriesId}/bans`;

    const selections = Array.from(this.currentSelections);

    try {
      const response = await fetch(endpoint, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
        },
        body: JSON.stringify(selections),
      });

      if (!response.ok) {
        console.error('Failed to send selections');
      }
    } catch (e) {
      console.error('Error sending selections:', e);
    }
  }

  async confirm(): Promise<void> {
    if (this.myConfirmed) return;

    const endpoint = this.isPicking
      ? `/series/${this.seriesId}/confirmPicks`
      : this.isBanning
        ? `/series/${this.seriesId}/confirmBans`
        : this.isSelecting
          ? `/series/${this.seriesId}/selectOpening`
          : null;

    if (!endpoint) return;

    try {
      let response: Response;

      if (this.isSelecting) {
        // For selecting, send the chosen opening name
        const selected = Array.from(this.currentSelections)[0];
        if (!selected) return; // No selection yet
        response = await fetch(endpoint, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify(selected),
        });
      } else {
        response = await fetch(endpoint, {
          method: 'POST',
        });
      }

      if (response.ok) {
        const data = await response.json();

        // Selecting phase returns redirect URL directly
        if (this.isSelecting && data.redirect) {
          console.log('[series] Selecting: Redirecting to game:', data.redirect);
          window.location.href = data.redirect;
          return;
        }

        this.myConfirmed = data.myConfirmed ?? true;
        this.opponentConfirmed = data.opponentConfirmed ?? false;

        const newPhase = Number(data.phase);
        console.log('[series] confirm response:', { newPhase, currentPhase: this.phase, RandomSelecting: PhaseId.RandomSelecting });

        // Phase changed, redirect to appropriate page
        if (newPhase !== this.phase) {
          if (newPhase === PhaseId.RandomSelecting) {
            console.log('[series] Redirecting to random-selecting page');
            window.location.href = `/series/${this.seriesId}/random-selecting`;
            return;
          } else {
            console.log('[series] Reloading page for phase:', newPhase);
            window.location.reload();
            return;
          }
        }
        // WebSocket will notify us when opponent confirms
      }
    } catch (e) {
      console.error('Error confirming:', e);
    }

    this.redraw();
  }

  // ===== WebSocket Event Handlers =====

  /** Handle reload event - fetch fresh state from server */
  handleReload(): void {
    this.fetchState();
  }

  /** Handle confirmed event - opponent confirmed or cancelled their picks/bans */
  handleConfirmed(data: { player: number; phase: string; confirmed?: boolean }): void {
    const povIndex = this.series.povIndex ?? 0;
    if (data.player !== povIndex) {
      this.opponentConfirmed = data.confirmed !== false;
      this.redraw();
    }
  }

  /** Handle phase event - phase changed */
  handlePhase(data: { phase: number; gameId?: string }): void {
    console.log('[series] WS phase event:', data);
    if (data.phase === PhaseId.Playing && data.gameId) {
      window.location.href = `/${data.gameId}`;
    } else if (data.phase === PhaseId.RandomSelecting) {
      window.location.href = `/series/${this.seriesId}/random-selecting`;
    } else if (data.phase !== this.phase) {
      window.location.reload();
    }
  }

  /** Handle gone event - player connected/disconnected */
  handleGone(data: { player: number; gone: boolean }): void {
    const povIndex = this.series.povIndex ?? 0;
    if (data.player !== povIndex) {
      this.opponentOnline = !data.gone;
      this.redraw();
    }
  }

  /** Handle aborted event - series was aborted */
  handleAborted(): void {
    alert('Series was aborted due to opponent disconnect.');
    window.location.href = '/';
  }

  /** Fetch fresh series state from server */
  private async fetchState(): Promise<void> {
    try {
      const response = await fetch(`/api/series/${this.seriesId}`);
      if (response.ok) {
        const data = await response.json();

        // Check if series was aborted
        if (data.status === StatusId.Aborted) {
          this.handleAborted();
          return;
        }

        const newPhase = Number(data.phase);
        // Phase changed, redirect to appropriate page
        if (newPhase !== this.phase) {
          this.handlePhase({ phase: newPhase, gameId: data.currentGame });
          return;
        }

        // Update opponent confirmed status and online status
        const povIndex = data.povIndex;
        if (povIndex !== undefined && data.players) {
          const oppIndex = 1 - povIndex;
          const oppPlayer = data.players[oppIndex];
          if (oppPlayer) {
            const oppConfirmed = this.isPicking
              ? oppPlayer.confirmedPicks
              : this.isBanning
                ? oppPlayer.confirmedBans
                : false;
            const oppOnline = oppPlayer.isOnline ?? true;
            if (oppConfirmed !== this.opponentConfirmed || oppOnline !== this.opponentOnline) {
              this.opponentConfirmed = oppConfirmed;
              this.opponentOnline = oppOnline;
              this.redraw();
            }
          }
        }
      }
    } catch (e) {
      console.error('Error fetching state:', e);
    }
  }

  async cancelConfirm(): Promise<void> {
    if (!this.myConfirmed) return;

    const endpoint = this.isPicking
      ? `/series/${this.seriesId}/cancelConfirmPicks`
      : this.isBanning
        ? `/series/${this.seriesId}/cancelConfirmBans`
        : null;

    if (!endpoint) return;

    try {
      const response = await fetch(endpoint, {
        method: 'POST',
      });

      if (response.ok) {
        const data = await response.json();
        this.myConfirmed = data.myConfirmed ?? false;
        this.opponentConfirmed = data.opponentConfirmed ?? false;
      }
    } catch (e) {
      console.error('Error canceling confirm:', e);
    }

    this.redraw();
  }

  get confirmButtonText(): string {
    if (this.isWaiting) {
      return 'Waiting for opponent...';
    }
    const count = this.currentSelections.size;
    const max = this.maxSelections;
    if (this.isPicking) {
      return `Confirm (${count}/${max})`;
    } else if (this.isBanning) {
      return `Confirm (${count}/${max})`;
    } else if (this.isSelecting) {
      return count > 0 ? 'Select Opening' : 'Select an Opening';
    }
    return 'Confirm';
  }

  get canConfirm(): boolean {
    if (this.myConfirmed) return false;
    if (this.isPicking) {
      return this.selectedPicks.size === this.maxPicks; // 정확히 5개
    } else if (this.isBanning) {
      return this.selectedBans.size === this.maxBans; // 정확히 2개
    } else if (this.isSelecting) {
      // Only the loser can confirm in Selecting phase
      return this.isMyTurnToSelect && this.currentSelections.size === 1;
    }
    return false;
  }

  get canCancel(): boolean {
    return this.myConfirmed && !this.opponentConfirmed;
  }

  destroy(): void {
    if (this.timerInterval) {
      clearInterval(this.timerInterval);
    }
  }

  // RandomSelecting phase methods
  private startRandomSelecting(): void {
    // Get the actual selected opening from the current game
    const currentGame = this.series.games.find(g => g.gameId === this.series.currentGame);
    if (currentGame) {
      this.selectedOpening = this.series.openings.find(o => o.id === currentGame.openingId) ?? null;
    }

    // Always redraw first to show the UI
    this.redraw();

    // If already expired, redirect after a brief delay to ensure UI is visible
    if (this.randomSelectingCountdown <= 0) {
      this.randomSelectingCountdown = 0;
      setTimeout(() => this.startGame(), 100);
      return;
    }

    this.startRandomSelectingCountdown();
  }

  private startRandomSelectingCountdown(): void {
    this.timerInterval = window.setInterval(() => {
      this.randomSelectingCountdown--;
      if (this.randomSelectingCountdown <= 0) {
        if (this.timerInterval) {
          clearInterval(this.timerInterval);
        }
        this.randomSelectingCountdown = 0; // Prevent negative display
        this.redraw();
        this.startGame();
      } else {
        this.redraw();
      }
    }, 1000);
  }

  private startGame(): void {
    // Game is already created during confirmBans, just redirect
    const gameId = this.series.currentGame;
    const povColor = this.series.povIndex === 0 ? 'white' : 'black';
    if (gameId) {
      window.location.href = `/${gameId}/${povColor}`;
    } else {
      // Fallback: WebSocket should notify us when game is ready
      console.warn('[series] No gameId yet, waiting for WebSocket event...');
    }
  }
}
