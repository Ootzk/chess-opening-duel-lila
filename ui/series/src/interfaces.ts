export interface OpeningPreset {
  name: string;
  fen: string;
  url: string;
}

export interface SeriesOpening {
  id: string;
  name: string;
  fen: string;
  url?: string;
  source: 'pick' | 'ban' | 'neutral';
  owner: number; // 0, 1 (player index), or -1 (neutral)
  usedInRound?: number;
  selectedBy?: 'loserchoice' | 'systemrandom' | 'timeout';
}

export interface SeriesGame {
  gameId: string;
  round: number;
  openingId: string;
  whitePlayer: number; // 0 or 1
  result?: 'white' | 'black' | 'draw';
}

export interface SeriesPlayer {
  index: number;
  score: number;
  confirmedPicks: boolean;
  confirmedBans: boolean;
  isOnline: boolean;
  user?: {
    id: string;
    name: string;
    title?: string;
  };
}

export interface SeriesData {
  id: string;
  phase: number;
  phaseName: string;
  bestOf: number;
  round: number;
  status: number;
  players: [SeriesPlayer, SeriesPlayer];
  openings: SeriesOpening[];
  games: SeriesGame[];
  povIndex?: number; // 0 or 1 (current user's player index)
  currentGame?: string;
  timeLeft?: number; // seconds remaining in current phase (server-calculated)
  selectingPlayer?: number; // 0 or 1
  finished: boolean;
  winner?: number; // 0 or 1
  rematchOfferedBy?: number; // 0 or 1
}

export interface PickConfig {
  seriesId: string;
  phase: number;
  presets: OpeningPreset[];
  series: SeriesData;
  i18n: Record<string, string>;
  socketVersion?: number;
}

// Phase IDs matching Series.Phase in Scala
export const Phase = {
  Picking: 10,
  Banning: 20,
  RandomSelecting: 25,
  Playing: 30,
  Selecting: 40,
  Finished: 50,
} as const;

// Status IDs matching Series.Status in Scala
export const Status = {
  Created: 10,
  Started: 20,
  Finished: 30,
  Aborted: 40,
} as const;

// Helper functions
export function getMyPicks(data: SeriesData): SeriesOpening[] {
  if (data.povIndex === undefined) return [];
  return data.openings.filter(o => o.owner === data.povIndex && o.source === 'pick');
}

export function getOpponentPicks(data: SeriesData): SeriesOpening[] {
  if (data.povIndex === undefined) return [];
  const oppIndex = 1 - data.povIndex;
  return data.openings.filter(o => o.owner === oppIndex && o.source === 'pick');
}

export function getMyBans(data: SeriesData): SeriesOpening[] {
  if (data.povIndex === undefined) return [];
  return data.openings.filter(o => o.owner === data.povIndex && o.source === 'ban');
}

export function getOpponentBans(data: SeriesData): SeriesOpening[] {
  if (data.povIndex === undefined) return [];
  const oppIndex = 1 - data.povIndex;
  return data.openings.filter(o => o.owner === oppIndex && o.source === 'ban');
}

export function getRemainingPicks(data: SeriesData, playerIndex: number): SeriesOpening[] {
  const picks = data.openings.filter(o => o.owner === playerIndex && o.source === 'pick');
  const oppBans = data.openings.filter(o => o.owner === (1 - playerIndex) && o.source === 'ban');
  const bannedNames = new Set(oppBans.map(b => b.name));
  return picks.filter(p => !bannedNames.has(p.name) && !p.usedInRound);
}

export function getAllBans(data: SeriesData): SeriesOpening[] {
  return data.openings.filter(o => o.source === 'ban');
}

export function getUnusedBans(data: SeriesData): SeriesOpening[] {
  return data.openings.filter(o => o.source === 'ban' && !o.usedInRound);
}

export function getNeutralOpening(data: SeriesData): SeriesOpening | undefined {
  return data.openings.find(o => o.source === 'neutral');
}
