import type { ServerClock } from './clock';
/** Shapes returned by the Sjakkportal API. */

export type Category = 'BLITZ' | 'RAPID' | 'CLASSICAL';
export type GameResult = 'WHITE_WIN' | 'BLACK_WIN' | 'DRAW';
export type GameStatus = 'SCHEDULED' | 'LIVE' | 'PENDING' | 'REJECTED' | 'FINISHED' | 'ABORTED' | 'ANNULLED';
export type Termination =
  | 'CHECKMATE' | 'RESIGNATION' | 'TIMEOUT' | 'STALEMATE' | 'THREEFOLD_REPETITION' | 'FIFTY_MOVE_RULE'
  | 'INSUFFICIENT_MATERIAL' | 'AGREEMENT' | 'FORFEIT' | 'UNKNOWN';

export interface RatingView { rating: number; ratedGames: number }

export interface User {
  id: number;
  username: string;
  displayName: string;
  role: 'ADMIN' | 'PLAYER';
  managed: boolean;
  enabled: boolean;
  mustChangePassword: boolean;
  chessComUsername: string | null;
  lichessUsername: string | null;
  ratings: Record<Category, RatingView>;
}

export interface PlayerRef { id: number; username: string; displayName: string }

export interface Move { ply: number; san: string; uci: string; fen: string }

export interface Game {
  id: number;
  status: GameStatus;
  result?: GameResult;
  termination?: Termination;
  category: Category;
  timeControl?: string;
  rated: boolean;
  stage?: 'MAIN' | 'PLAYOFF' | 'ARMAGEDDON';
  tournamentPairingId?: number;
  featured: boolean;
  source: string;
  white: PlayerRef;
  black: PlayerRef;
  registeredBy?: PlayerRef;
  confirmedBy?: PlayerRef;
  rejectionReason?: string;
  plyCount: number;
  currentFen?: string;
  comment?: string;
  playedAt: string;
  whiteRatingBefore?: number;
  whiteRatingDelta?: number;
  blackRatingBefore?: number;
  blackRatingDelta?: number;
  pgn?: string;
  moves: Move[];
  correction?: { reason: string; by: PlayerRef; at: string };
  clock?: ServerClock;
}

export interface GameListItem {
  id: number;
  status: GameStatus;
  result?: GameResult;
  termination?: Termination;
  category: Category;
  timeControl?: string;
  rated: boolean;
  source: string;
  white: PlayerRef;
  black: PlayerRef;
  plyCount: number;
  playedAt: string;
  whiteRatingDelta?: number;
  blackRatingDelta?: number;
  tournamentPairingId?: number;
}

export interface MoveResponse {
  ply: number; san: string; uci: string; fen: string; check: boolean; gameOver: boolean;
  status: GameStatus; result?: GameResult; termination?: Termination;
}

export interface SavedAnalysis {
  gameId: number;
  engine: string;
  depth: number;
  whiteAccuracy: number;
  blackAccuracy: number;
  whiteAcpl: number;
  blackAcpl: number;
  whiteBrilliancies: number; blackBrilliancies: number;
  whiteInaccuracies: number; blackInaccuracies: number;
  whiteMistakes: number; blackMistakes: number;
  whiteBlunders: number; blackBlunders: number;
  moves: { ply: number; cp: number | null; mate: number | null; best: string | null; classification: string }[];
}

export interface Probabilities { aWin: number; draw: number; bWin: number }
export interface Stakes { onWin: number; onDraw: number; onLoss: number }

export interface Matchup {
  playerA: PlayerRef;
  playerB: PlayerRef;
  category: Category;
  ratingA: number;
  ratingB: number;
  headToHead: { games: number; aWins: number; draws: number; bWins: number };
  recentGames: GameListItem[];
  formA: string[];
  formB: string[];
  probabilities: Probabilities;
  stakesA: Stakes;
  stakesB: Stakes;
}

export interface Badge { code: string; title: string; description: string }

export interface PlayerStats {
  user: PlayerRef;
  category: string;
  games: number; wins: number; draws: number; losses: number;
  asWhite: { games: number; wins: number; draws: number; losses: number };
  asBlack: { games: number; wins: number; draws: number; losses: number };
  currentStreak: { type: 'WIN' | 'DRAW' | 'LOSS'; length: number } | null;
  longestWinStreak: number;
  recentForm: string[];
  ratings: Record<Category, { rating: number; peak: number; ratedGames: number }>;
  averageAccuracy: number | null;
  analysedGames: number;
  blundersPerGame: number;
  nemesis: { user: PlayerRef; games: number; score: number } | null;
  favouriteOpponent: { user: PlayerRef; games: number; score: number } | null;
  badges: Badge[];
}

export interface RatingPoint { gameId: number | null; at: string; rating: number }
export interface LeaderboardRow { rank: number; user: PlayerRef; rating: number; peak: number; ratedGames: number }

export type TournamentFormat = 'ROUND_ROBIN' | 'DOUBLE_ROUND_ROBIN' | 'SWISS' | 'SINGLE_ELIMINATION' | 'DOUBLE_ELIMINATION' | 'ARENA';
export type TournamentStatus = 'DRAFT' | 'ACTIVE' | 'FINISHED' | 'CANCELLED';

export interface TournamentSummary {
  id: number; name: string; format: TournamentFormat; status: TournamentStatus; category: Category;
  timeControl: string | null; participantCount: number; startsAt: string | null; endsAt: string | null; createdAt: string;
}

export interface TournamentGame {
  id: number; status: GameStatus; result?: GameResult; stage: string; category: Category; rated: boolean;
  white: PlayerRef; black: PlayerRef; playedAt: string;
}

export interface Pairing {
  id: number; board: number; white: PlayerRef | null; black: PlayerRef | null; bye: boolean; result: string | null;
  advancing: PlayerRef | null; currentGame: TournamentGame | null; games: TournamentGame[];
}

export interface Round { id: number; bracket: string; number: number; status: string; pairings: Pairing[] }

export interface Standing {
  rank: number; user: PlayerRef; score: number; played: number; wins: number; draws: number; losses: number;
  headToHead: number; buchholz: number; sonnebornBerger: number; streak: number; onFire: boolean; eliminated: boolean;
}

export interface BracketMatch { id: number; side: string; round: number; playerA: PlayerRef | null; playerB: PlayerRef | null; winner: PlayerRef | null }

export interface Tournament {
  id: number; name: string; description: string | null; format: TournamentFormat; status: TournamentStatus;
  category: Category; timeControl: string | null; rated: boolean; drawResolution: string | null;
  totalRounds: number | null; currentRound: number; startsAt: string | null; endsAt: string | null; createdAt: string;
  participants: { id: number; user: PlayerRef; seed: number; seedRating: number }[];
  rounds: Round[]; standings: Standing[]; bracket: BracketMatch[] | null; champion: PlayerRef | null;
}

export interface TvState {
  game: Game | null;
  reason: 'FEATURED' | 'LIVE' | 'RECENT' | null;
  whiteRating: number | null;
  blackRating: number | null;
  tournament: { id: number; name: string; format: TournamentFormat; standings: Standing[] } | null;
}

export interface Invite {
  id: number; code: string; note: string | null; maxUses: number | null; useCount: number; remainingUses: number | null;
  expiresAt: string | null; revokedAt: string | null; createdAt: string; status: 'ACTIVE' | 'EXPIRED' | 'EXHAUSTED' | 'REVOKED';
  redemptions: { userId: number; username: string; displayName: string; redeemedAt: string }[];
}
