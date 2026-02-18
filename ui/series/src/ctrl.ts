import type { PickConfig, SeriesData, OpeningPreset, SeriesOpening } from './interfaces';
import {
  Phase as PhaseId,
  Status as StatusId,
  getMyPicks,
  getOpponentPicks,
  getRemainingPicks,
  openingKey,
  keyToName,
} from './interfaces';

export default class SeriesPickCtrl {
  seriesId: string;
  phase: number;
  presets: OpeningPreset[];
  series: SeriesData;
  selectedPicks: Set<string> = new Set();
  selectedBans: Set<string> = new Set();
  selectedSelectingPick: Set<string> = new Set();
  timeLeft: number;
  timerInterval?: number;
  myConfirmed: boolean = false;
  opponentConfirmed: boolean = false;
  opponentOnline: boolean = true;

  // Selecting phase: opponent's real-time pick (via WS)
  opponentSelectingPick: string | null = null;

  // 3-second countdown after both confirm (pick/ban) or selecting confirm
  countdownActive: boolean = false;
  countdownSeconds: number = 3;
  countdownInterval?: number;

  // RandomSelecting phase state
  selectedOpening: SeriesOpening | null = null;
  randomSelectingCountdown: number = 5;
  gameId: string | null = null;

  // Roulette animation state
  roulettePhase: 'spinning' | 'result' = 'spinning';
  rouletteHighlightIndex: number = -1;
  rouletteCards: SeriesOpening[] = [];

  // Showcase state (shared by RandomSelecting result + Selecting showcase)
  showcasePhase: 'none' | 'selecting-showcase' = 'none';
  showcaseOpening: SeriesOpening | null = null;
  showcaseGameId: string | null = null;
  showcaseCountdown: number = 5;

  // Finished phase state
  offeringRematch: boolean = false;
  opponentOfferingRematch: boolean = false;

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
    if (this.isFinished) {
      // No timers needed for finished page
      return;
    } else if (this.isRandomSelecting) {
      this.startRandomSelecting();
    } else {
      // Start timer for pick/ban/selecting phases
      // If already timed out (timeLeft <= 0), trigger timeout immediately
      if (this.timeLeft <= 0) {
        this.onTimeout();
      } else {
        this.startTimer();
      }
      // If page loaded with both confirmed (e.g. refresh during 3s window), start countdown
      if (this.isBothConfirmed || (this.isSelecting && this.myConfirmed)) {
        this.startCountdown();
      }
    }
  }

  private initFromSeries(): void {
    const povIndex = this.series.povIndex;
    if (povIndex === undefined) return;

    const oppIndex = 1 - povIndex;

    // Load existing picks (use composite key: name::ownerColor)
    const myPicks = getMyPicks(this.series);
    myPicks.forEach(p => this.selectedPicks.add(openingKey(p)));

    // Load existing bans (use composite key: name::ownerColor)
    const myBans = this.series.openings.filter(o => o.owner === povIndex && o.source === 'ban');
    myBans.forEach(b => this.selectedBans.add(openingKey(b)));

    // Load confirmed state
    if (this.isPicking) {
      this.myConfirmed = this.series.players[povIndex].confirmedPicks;
      this.opponentConfirmed = this.series.players[oppIndex].confirmedPicks;
    } else if (this.isBanning) {
      this.myConfirmed = this.series.players[povIndex].confirmedBans;
      this.opponentConfirmed = this.series.players[oppIndex].confirmedBans;
    } else if (this.isSelecting && this.isMyTurnToSelect) {
      this.myConfirmed = this.series.players[povIndex].confirmedSelecting;
      // Load saved selectingPick if any (server stores name, resolve to composite key)
      const savedPick = this.series.players[povIndex].selectingPick;
      if (savedPick) {
        this.selectedSelectingPick.clear();
        const match = this.availableOpenings.find(o => o.name === savedPick);
        this.selectedSelectingPick.add(match ? openingKey(match) : openingKey({ name: savedPick }));
      }
    }

    // Load opponent online status
    this.opponentOnline = this.series.players[oppIndex].isOnline;

    // Load rematch state (for finished page refresh)
    if (this.isFinished && this.series.rematchOfferedBy !== undefined) {
      if (this.series.rematchOfferedBy === povIndex) {
        this.offeringRematch = true;
      } else {
        this.opponentOfferingRematch = true;
      }
    }
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
    // Selecting phase: 타임아웃 시 랜덤 선택 (패자만)
    if (this.isSelecting) {
      if (this.isMyTurnToSelect && !this.myConfirmed) {
        this.selectRandomOpening();
      }
      // 패자는 서버 타임아웃이 처리 (forfeit/random)
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
      const selections = Array.from(this.selectedPicks).map(keyToName);
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
      const selections = Array.from(this.selectedBans).map(keyToName);
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

  get isFinished(): boolean {
    return this.phase === PhaseId.Finished;
  }

  get isWaiting(): boolean {
    return this.myConfirmed;
  }

  get isBothConfirmed(): boolean {
    return (this.isPicking || this.isBanning) && this.myConfirmed && this.opponentConfirmed;
  }

  private startCountdown(): void {
    if (this.countdownActive) return;
    this.countdownActive = true;
    this.countdownSeconds = 3;
    this.countdownInterval = window.setInterval(() => {
      this.countdownSeconds--;
      if (this.countdownSeconds <= 0) {
        this.stopCountdown();
      }
      this.redraw();
    }, 1000);
    this.redraw();
  }

  private stopCountdown(): void {
    if (this.countdownInterval) {
      clearInterval(this.countdownInterval);
      this.countdownInterval = undefined;
    }
    this.countdownActive = false;
    this.countdownSeconds = 3;
  }

  // Selecting phase: 상대가 선택 중인지 (내가 패자인지)
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
    return this.isPicking ? this.selectedPicks : this.isSelecting ? this.selectedSelectingPick : this.selectedBans;
  }

  get maxSelections(): number {
    return this.isPicking ? this.maxPicks : this.isSelecting ? 1 : this.maxBans;
  }

  // Get openings that can be selected in current phase
  get availableOpenings(): OpeningPreset[] {
    if (this.isPicking) {
      return this.presets;
    } else if (this.isBanning) {
      // Can only ban from opponent's picks
      const oppPicks = getOpponentPicks(this.series);
      return oppPicks.map(o => ({ name: o.name, fen: o.fen, url: o.url || '', ownerColor: o.ownerColor }));
    } else if (this.isSelecting) {
      // Both players see the loser's remaining picks
      const selectingIdx = this.series.selectingPlayer ?? 0;
      const remaining = getRemainingPicks(this.series, selectingIdx);
      return remaining.map(o => ({ name: o.name, fen: o.fen, url: o.url || '', ownerColor: o.ownerColor }));
    }
    return [];
  }

  isSelected(name: string): boolean {
    return this.currentSelections.has(name);
  }

  isOpponentPick(key: string): boolean {
    if (!this.isBanning) return false;
    const oppPicks = getOpponentPicks(this.series);
    return oppPicks.some(p => openingKey(p) === key);
  }

  canSelect(key: string): boolean {
    if (this.myConfirmed) return false;
    if (this.isPicking) {
      return !this.isSelected(key) && this.selectedPicks.size < this.maxPicks;
    } else if (this.isBanning) {
      return this.isOpponentPick(key) && !this.isSelected(key) && this.selectedBans.size < this.maxBans;
    } else if (this.isSelecting) {
      // Only the loser can select
      if (!this.isMyTurnToSelect) return false;
      return this.availableOpenings.some(o => openingKey(o) === key);
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
    if (this.isSelecting) {
      // Real-time sync: send current pick to server for WS broadcast (server expects name)
      const selectedKey = Array.from(this.selectedSelectingPick)[0] ?? null;
      const selected = selectedKey ? keyToName(selectedKey) : null;
      try {
        await fetch(`/series/${this.seriesId}/setSelectingPick`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify(selected),
        });
      } catch (e) {
        console.error('Error sending selecting pick:', e);
      }
      return;
    }

    const endpoint = this.isPicking
      ? `/series/${this.seriesId}/picks`
      : `/series/${this.seriesId}/bans`;

    // Convert composite keys back to names for server API
    const selections = Array.from(this.currentSelections).map(keyToName);

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

    if (this.isSelecting) {
      // Selecting phase: confirm with 3s cancel window (server expects name)
      const selectedKey = Array.from(this.selectedSelectingPick)[0];
      if (!selectedKey) return;
      const selected = keyToName(selectedKey);
      try {
        const response = await fetch(`/series/${this.seriesId}/confirmSelecting`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify(selected),
        });
        if (response.ok) {
          const data = await response.json();
          this.myConfirmed = data.confirmedSelecting ?? true;
          // Start 3s countdown — WS "phase" event triggers redirect when server creates game
          if (this.myConfirmed) {
            this.startCountdown();
          }
        }
      } catch (e) {
        console.error('Error confirming selecting:', e);
      }
      this.redraw();
      return;
    }

    const endpoint = this.isPicking
      ? `/series/${this.seriesId}/confirmPicks`
      : this.isBanning
        ? `/series/${this.seriesId}/confirmBans`
        : null;

    if (!endpoint) return;

    try {
      const response = await fetch(endpoint, {
        method: 'POST',
      });

      if (response.ok) {
        const data = await response.json();

        this.myConfirmed = data.myConfirmed ?? true;
        this.opponentConfirmed = data.opponentConfirmed ?? false;

        const newPhase = Number(data.phase);

        // Phase changed, redirect to appropriate page
        if (newPhase !== this.phase) {
          if (newPhase === PhaseId.RandomSelecting) {
            window.location.href = `/series/${this.seriesId}/random-selecting`;
            return;
          } else {
            window.location.reload();
            return;
          }
        }
        // Both confirmed → start 3s countdown
        if (this.isBothConfirmed && !this.countdownActive) {
          this.startCountdown();
        }
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

  /** Handle confirmed event - opponent confirmed or cancelled their picks/bans/selecting */
  handleConfirmed(data: { player: number; phase: string; confirmed?: boolean }): void {
    const povIndex = this.series.povIndex ?? 0;
    if (data.player !== povIndex) {
      this.opponentConfirmed = data.confirmed !== false;
      // Start or stop countdown based on both-confirmed state
      if (this.isBothConfirmed && !this.countdownActive) {
        this.startCountdown();
      } else if (!this.isBothConfirmed && this.countdownActive) {
        this.stopCountdown();
      }
      // Selecting: loser sees countdown when winner confirms
      if (this.isSelecting && this.isWaitingForOpponentSelect) {
        if (this.opponentConfirmed && !this.countdownActive) {
          this.startCountdown();
        } else if (!this.opponentConfirmed && this.countdownActive) {
          this.stopCountdown();
        }
      }
      this.redraw();
    } else if (data.phase === 'selecting') {
      // My own confirm/cancel echoed back
      this.myConfirmed = data.confirmed !== false;
      this.redraw();
    }
  }

  /** Handle selectingPick event - opponent's real-time pick during Selecting phase */
  handleSelectingPick(data: { name: string | null }): void {
    this.opponentSelectingPick = data.name;
    this.redraw();
  }

  /** Handle phase event - phase changed */
  handlePhase(data: { phase: number; gameId?: string }): void {
    console.log('[series] WS phase event:', data);
    // Selecting → Playing: show showcase before redirecting
    if (this.isSelecting && data.phase === PhaseId.Playing && data.gameId) {
      this.showSelectingShowcase(data.gameId);
      return;
    }
    if (data.phase === PhaseId.Finished) {
      window.location.href = `/series/${this.seriesId}/finished`;
    } else if (data.phase === PhaseId.Playing && data.gameId) {
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

  /** Handle rematch offer from opponent */
  handleRematchOffer(data: { player: number }): void {
    const povIndex = this.series.povIndex ?? 0;
    if (data.player !== povIndex) {
      this.opponentOfferingRematch = true;
    } else {
      this.offeringRematch = true;
    }
    this.redraw();
  }

  /** Handle rematch taken - both players redirected to new series */
  handleRematchTaken(data: { newSeriesId: string }): void {
    window.location.href = `/series/${data.newSeriesId}/pick`;
  }

  /** Request rematch */
  async requestRematch(): Promise<void> {
    try {
      const response = await fetch(`/series/${this.seriesId}/rematch`, { method: 'POST' });
      if (response.ok) {
        const data = await response.json();
        if (data.newSeriesId) {
          window.location.href = `/series/${data.newSeriesId}/pick`;
        } else {
          this.offeringRematch = true;
          this.redraw();
        }
      }
    } catch (e) {
      console.error('Error requesting rematch:', e);
    }
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
                : this.isSelecting
                  ? oppPlayer.confirmedSelecting
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
    this.stopCountdown();

    if (this.isSelecting) {
      try {
        const response = await fetch(`/series/${this.seriesId}/cancelConfirmSelecting`, {
          method: 'POST',
        });
        if (response.ok) {
          const data = await response.json();
          this.myConfirmed = data.confirmedSelecting ?? false;
        }
      } catch (e) {
        console.error('Error canceling selecting confirm:', e);
      }
      this.redraw();
      return;
    }

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
    if (this.countdownActive) {
      if (this.isPicking) {
        return `Ban phase starting in ${this.countdownSeconds}...`;
      }
      const round = this.series.games.length + 1;
      return `Game ${round} starting in ${this.countdownSeconds}...`;
    }
    if (this.isWaiting && !this.isSelecting) {
      return 'Waiting for opponent...';
    }
    const count = this.currentSelections.size;
    const max = this.maxSelections;
    if (this.isPicking) {
      return `Confirm (${count}/${max})`;
    } else if (this.isBanning) {
      return `Confirm (${count}/${max})`;
    } else if (this.isSelecting) {
      return count > 0 ? 'Confirm' : 'Select an Opening';
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
      // Only the winner can confirm in Selecting phase
      return this.isMyTurnToSelect && this.currentSelections.size === 1;
    }
    return false;
  }

  get canCancel(): boolean {
    return this.myConfirmed;
  }

  destroy(): void {
    if (this.timerInterval) {
      clearInterval(this.timerInterval);
    }
    this.stopCountdown();
  }

  // RandomSelecting phase methods
  private startRandomSelecting(): void {
    // Get the actual selected opening from the current game
    const currentGame = this.series.games.find(g => g.gameId === this.series.currentGame);
    if (currentGame) {
      this.selectedOpening = this.series.openings.find(o => o.id === currentGame.openingId) ?? null;
    }

    // Build roulette cards: all remaining picks from both players (including current round's opening)
    // Use per-player ban filtering (opponent's bans only affect that player's picks)
    const gameNum = this.series.round;
    const povIdx = this.series.povIndex ?? 0;
    const remainingPicks = [0, 1].flatMap(playerIdx => {
      const playerPicks = this.series.openings.filter(o => o.owner === playerIdx && o.source === 'pick');
      const oppBanNames = new Set(
        this.series.openings.filter(o => o.owner === (1 - playerIdx) && o.source === 'ban').map(o => o.name),
      );
      return playerPicks.filter(
        p => !oppBanNames.has(p.name) && (!p.usedInRound || p.usedInRound === gameNum),
      );
    });
    this.rouletteCards = remainingPicks.sort((a, b) => {
      // POV player's cards first, then opponent's
      const aIsMine = a.owner === povIdx ? 0 : 1;
      const bIsMine = b.owner === povIdx ? 0 : 1;
      return aIsMine - bIsMine;
    });

    // If already expired, redirect immediately
    if (this.randomSelectingCountdown <= 0) {
      this.randomSelectingCountdown = 0;
      this.roulettePhase = 'result';
      this.redraw();
      setTimeout(() => this.startGame(), 100);
      return;
    }

    // Available time for roulette = total time - 5s countdown
    const availableForRoulette = this.randomSelectingCountdown - 5;
    if (availableForRoulette >= 3) {
      this.roulettePhase = 'spinning';
      this.redraw();
      this.startRoulette(availableForRoulette);
    } else {
      // Not enough time for roulette, show result directly + countdown
      this.roulettePhase = 'result';
      this.redraw();
      this.startRandomSelectingCountdown();
    }
  }

  private startRoulette(availableSeconds: number): void {
    const cards = this.rouletteCards;
    if (cards.length === 0) {
      this.roulettePhase = 'result';
      this.redraw();
      this.startRandomSelectingCountdown();
      return;
    }

    const selectedIdx = cards.findIndex(c => c.id === this.selectedOpening?.id);
    const targetIdx = selectedIdx >= 0 ? selectedIdx : 0;

    // Constant speed roulette: duration scales with card count (3-8s)
    const STEP_DELAY = 150; // ms, constant between each card transition
    const targetDuration = Math.min(8, 2 + cards.length); // 1 card→3s, 6 cards→8s
    const rouletteDurationMs = Math.min(targetDuration, availableSeconds) * 1000;
    const totalStepsTarget = Math.floor(rouletteDurationMs / STEP_DELAY);

    // Build sequence: full cycles + landing on target
    const landingSteps = targetIdx + 1;
    const cycles = Math.max(1, Math.floor((totalStepsTarget - landingSteps) / cards.length));
    const sequence: number[] = [];
    for (let cycle = 0; cycle < cycles; cycle++)
      for (let i = 0; i < cards.length; i++) sequence.push(i);
    for (let i = 0; i <= targetIdx; i++) sequence.push(i);

    let step = 0;

    const animate = () => {
      if (step >= sequence.length) {
        // Roulette done → pause 3s on highlighted card, then switch to result view
        setTimeout(() => {
          this.roulettePhase = 'result';
          this.randomSelectingCountdown = 5;
          this.redraw();
          this.startRandomSelectingCountdown();
        }, 3000);
        return;
      }
      // Direct DOM manipulation for performance (no full Snabbdom redraw)
      this.rouletteHighlightIndex = sequence[step];
      updateRouletteHighlight(this.rouletteHighlightIndex);
      step++;
      setTimeout(animate, STEP_DELAY);
    };
    animate();
  }

  private startRandomSelectingCountdown(): void {
    this.timerInterval = window.setInterval(() => {
      this.randomSelectingCountdown--;
      if (this.randomSelectingCountdown <= 0) {
        if (this.timerInterval) {
          clearInterval(this.timerInterval);
        }
        this.randomSelectingCountdown = 0;
        this.redraw();
        this.startGame();
      } else {
        this.redraw();
      }
    }, 1000);
  }

  // Selecting phase showcase
  private showSelectingShowcase(gameId: string): void {
    this.showcaseGameId = gameId;
    this.showcasePhase = 'selecting-showcase';

    // Find the selected opening
    if (this.isMyTurnToSelect) {
      const pickKey = Array.from(this.selectedSelectingPick)[0];
      if (pickKey) {
        const name = keyToName(pickKey);
        this.showcaseOpening = this.series.openings.find(o => o.name === name && o.source === 'pick') ?? null;
      }
      // Fallback: timeout 시 서버가 랜덤 선택한 오프닝 (selectingPick WS로 수신)
      if (!this.showcaseOpening && this.opponentSelectingPick) {
        this.showcaseOpening =
          this.series.openings.find(o => o.name === this.opponentSelectingPick && o.source === 'pick') ?? null;
      }
    } else {
      const pickName = this.opponentSelectingPick;
      this.showcaseOpening = this.series.openings.find(o => o.name === pickName && o.source === 'pick') ?? null;
    }

    // 5-second countdown
    this.showcaseCountdown = 5;
    this.redraw();
    const interval = window.setInterval(() => {
      this.showcaseCountdown--;
      if (this.showcaseCountdown <= 0) {
        clearInterval(interval);
        this.startGameWithId(gameId);
      }
      this.redraw();
    }, 1000);
  }

  private startGame(): void {
    const gameId = this.series.currentGame;
    if (gameId) {
      this.startGameWithId(gameId);
    } else {
      console.warn('[series] No gameId yet, waiting for WebSocket event...');
    }
  }

  private startGameWithId(gameId: string): void {
    const currentGameData = this.series.games.find(g => g.gameId === gameId);
    const whitePlayer = currentGameData?.whitePlayer ?? 0;
    const povColor = this.series.povIndex === whitePlayer ? 'white' : 'black';
    window.location.href = `/${gameId}/${povColor}`;
  }
}

function updateRouletteHighlight(activeIndex: number): void {
  const cards = document.querySelectorAll('.series-pick__roulette-card');
  cards.forEach((card, i) => {
    card.classList.toggle('roulette-active', i === activeIndex);
  });
}
