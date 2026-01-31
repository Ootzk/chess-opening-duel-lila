import type { PickConfig, SeriesData, OpeningPreset } from './interfaces';
import { Phase as PhaseId } from './interfaces';

export default class SeriesPickCtrl {
  seriesId: string;
  phase: number;
  presets: OpeningPreset[];
  series: SeriesData;
  selectedPicks: Set<string> = new Set();
  selectedBans: Set<string> = new Set();
  timeLeft: number = 30;
  timerInterval?: number;
  myConfirmed: boolean = false;
  opponentConfirmed: boolean = false;

  constructor(
    readonly config: PickConfig,
    readonly redraw: () => void,
  ) {
    this.seriesId = config.seriesId;
    this.phase = config.phase;
    this.presets = config.presets;
    this.series = config.series;

    // Initialize selections and confirmed state from series data
    this.initFromSeries();

    // Start timer
    this.startTimer();
  }

  private initFromSeries(): void {
    const povColor = this.series.povColor;
    if (!povColor) return;

    const oppColor = povColor === 'white' ? 'black' : 'white';

    // Load existing picks
    const myPicks = this.series.picks[povColor] || [];
    myPicks.forEach(p => this.selectedPicks.add(p.name));

    // Load existing bans
    const myBans = this.series.bans[povColor] || [];
    myBans.forEach(b => this.selectedBans.add(b.name));

    // Load confirmed state
    if (this.isPicking) {
      this.myConfirmed = this.series.confirmedPicks[povColor];
      this.opponentConfirmed = this.series.confirmedPicks[oppColor];
    } else if (this.isBanning) {
      this.myConfirmed = this.series.confirmedBans[povColor];
      this.opponentConfirmed = this.series.confirmedBans[oppColor];
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
    // Auto-confirm on timeout
    if (!this.myConfirmed) {
      this.confirm();
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

  get isWaiting(): boolean {
    return this.myConfirmed && !this.opponentConfirmed;
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
      const oppColor = this.series.povColor === 'white' ? 'black' : 'white';
      return this.series.picks[oppColor] || [];
    } else if (this.isSelecting) {
      // Can only select from own remaining picks
      const myColor = this.series.povColor || 'white';
      return this.series.remainingPicks[myColor] || [];
    }
    return [];
  }

  isSelected(name: string): boolean {
    return this.currentSelections.has(name);
  }

  isOpponentPick(name: string): boolean {
    if (!this.isBanning) return false;
    const oppColor = this.series.povColor === 'white' ? 'black' : 'white';
    return (this.series.picks[oppColor] || []).some(p => p.name === name);
  }

  canSelect(name: string): boolean {
    if (this.myConfirmed) return false;
    if (this.isPicking) {
      return !this.isSelected(name) && this.selectedPicks.size < this.maxPicks;
    } else if (this.isBanning) {
      return this.isOpponentPick(name) && !this.isSelected(name) && this.selectedBans.size < this.maxBans;
    } else if (this.isSelecting) {
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
        response = await fetch(endpoint, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify(selected || ''),
        });
      } else {
        response = await fetch(endpoint, {
          method: 'POST',
        });
      }

      if (response.ok) {
        const data = await response.json();
        this.myConfirmed = data.myConfirmed ?? true;
        this.opponentConfirmed = data.opponentConfirmed ?? false;

        // Phase changed, reload page
        if (data.phase !== this.phase) {
          window.location.reload();
        } else if (this.isWaiting) {
          // Start polling while waiting for opponent
          this.startPolling();
        }
      }
    } catch (e) {
      console.error('Error confirming:', e);
    }

    this.redraw();
  }

  private pollingInterval?: number;

  private startPolling(): void {
    if (this.pollingInterval) return;
    this.pollingInterval = window.setInterval(() => this.pollState(), 100);
  }

  private stopPolling(): void {
    if (this.pollingInterval) {
      clearInterval(this.pollingInterval);
      this.pollingInterval = undefined;
    }
  }

  private async pollState(): Promise<void> {
    try {
      const response = await fetch(`/api/series/${this.seriesId}`);
      if (response.ok) {
        const data = await response.json();
        // Phase changed, reload page
        if (data.phase !== this.phase) {
          this.stopPolling();
          window.location.reload();
        }
      }
    } catch (e) {
      console.error('Error polling state:', e);
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
      return `Confirm Bans (${count}/${max})`;
    } else if (this.isSelecting) {
      return count > 0 ? 'Select Opening' : 'Select an Opening';
    }
    return 'Confirm';
  }

  get canConfirm(): boolean {
    if (this.myConfirmed) return false;
    if (this.isPicking) {
      return this.selectedPicks.size > 0;
    } else if (this.isBanning) {
      return this.selectedBans.size > 0;
    } else if (this.isSelecting) {
      return this.currentSelections.size === 1;
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
    this.stopPolling();
  }
}