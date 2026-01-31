export interface OpeningPreset {
  name: string;
  fen: string;
  url: string;
}

export interface SeriesPlayer {
  score: number;
  user?: {
    id: string;
    name: string;
  };
}

export interface SeriesData {
  id: string;
  phase: number;
  phaseName: string;
  bestOf: number;
  round: number;
  status: number;
  scores: [number, number];
  players: {
    white: SeriesPlayer;
    black: SeriesPlayer;
  };
  picks: {
    white: OpeningPreset[];
    black: OpeningPreset[];
  };
  bans: {
    white: OpeningPreset[];
    black: OpeningPreset[];
  };
  remainingPicks: {
    white: OpeningPreset[];
    black: OpeningPreset[];
  };
  bannedOpenings: OpeningPreset[];
  openings: OpeningPreset[];
  confirmedPicks: {
    white: boolean;
    black: boolean;
  };
  confirmedBans: {
    white: boolean;
    black: boolean;
  };
  povColor?: 'white' | 'black';
  finished: boolean;
  winner?: string;
}

export interface PickConfig {
  seriesId: string;
  phase: number;
  presets: OpeningPreset[];
  series: SeriesData;
  i18n: Record<string, string>;
}

// Phase IDs matching Series.Phase in Scala
export const Phase = {
  Picking: 10,
  Banning: 20,
  Game1Shuffling: 25,
  Playing: 30,
  Selecting: 40,
  Finished: 50,
} as const;