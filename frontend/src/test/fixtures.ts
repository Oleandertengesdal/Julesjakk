import { Chess } from 'chess.js';
import type {
  Game, GameListItem, Move, SavedAnalysis, LeaderboardRow, Matchup, PlayerRef, PlayerStats, Standing, Tournament, TournamentSummary, TvState, User,
} from '../lib/types';

/** Small factories for page and component tests. Override only what a test cares about. */
export const kari: PlayerRef = { id: 2, username: 'kari', displayName: 'Kari' };
export const ola: PlayerRef = { id: 3, username: 'ola', displayName: 'Ola' };
export const adminRef: PlayerRef = { id: 1, username: 'admin', displayName: 'Admin' };

const ratings = { BLITZ: { rating: 1200, ratedGames: 0 }, RAPID: { rating: 1200, ratedGames: 0 }, CLASSICAL: { rating: 1200, ratedGames: 0 } };

export function makeUser(ref: PlayerRef, extra: Partial<User> = {}): User {
  return { ...ref, role: 'PLAYER', managed: false, enabled: true, mustChangePassword: false, chessComUsername: null,
    lichessUsername: null, ratings, ...extra };
}

export const adminUser = makeUser(adminRef, { role: 'ADMIN' });
export const kariUser = makeUser(kari);
export const olaUser = makeUser(ola);

export function makeGame(extra: Partial<Game> = {}): Game {
  return {
    id: 17, status: 'FINISHED', result: 'WHITE_WIN', termination: 'RESIGNATION', category: 'BLITZ', timeControl: '5+3', rated: true,
    stage: 'MAIN', featured: false, source: 'OTB', white: kari, black: ola, registeredBy: kari, confirmedBy: ola, plyCount: 0,
    playedAt: '2026-11-14T19:30:00Z', whiteRatingBefore: 1200, whiteRatingDelta: 20, blackRatingBefore: 1200, blackRatingDelta: -20,
    moves: [], ...extra,
  };
}


export const per: PlayerRef = { id: 4, username: 'per', displayName: 'Per' };

export function makeListItem(extra: Partial<GameListItem> = {}): GameListItem {
  return { id: 30, status: 'FINISHED', result: 'DRAW', category: 'RAPID', timeControl: '15+10', rated: true, source: 'OTB', white: kari,
    black: ola, plyCount: 0, playedAt: '2026-11-10T18:00:00Z', whiteRatingDelta: 0, blackRatingDelta: 0, ...extra };
}

export const leaderboard: LeaderboardRow[] = [
  { rank: 1, user: kari, rating: 1320, peak: 1340, ratedGames: 12 },
  { rank: 2, user: ola, rating: 1250, peak: 1260, ratedGames: 10 },
];

export function makeTv(extra: Partial<TvState> = {}): TvState {
  return { game: makeGame({ status: 'LIVE', result: undefined, plyCount: 3, currentFen: 'rnbqkbnr/pppp1ppp/8/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R b KQkq - 1 2',
    moves: [{ ply: 1, san: 'e4', uci: 'e2e4', fen: 'rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq - 0 1' },
      { ply: 2, san: 'e5', uci: 'e7e5', fen: 'rnbqkbnr/pppp1ppp/8/4p3/4P3/8/PPPP1PPP/RNBQKBNR w KQkq - 0 2' },
      { ply: 3, san: 'Nf3', uci: 'g1f3', fen: 'rnbqkbnr/pppp1ppp/8/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R b KQkq - 1 2' }] }),
  reason: 'LIVE', whiteRating: 1320, blackRating: 1250, tournament: null, ...extra };
}

export function makeTournamentSummary(extra: Partial<TournamentSummary> = {}): TournamentSummary {
  return { id: 5, name: 'Julecupen', format: 'ROUND_ROBIN', status: 'ACTIVE', category: 'RAPID', timeControl: '15+10', participantCount: 4,
    startsAt: null, endsAt: null, createdAt: '2026-11-01T10:00:00Z', ...extra };
}

export function makeStanding(rank: number, user: PlayerRef, score: number, extra: Partial<Standing> = {}): Standing {
  return { rank, user, score, played: 2, wins: Math.floor(score), draws: score % 1 ? 1 : 0, losses: 0, headToHead: 0, buchholz: 1.5,
    sonnebornBerger: 0.75, streak: 0, onFire: false, eliminated: false, ...extra };
}

export function makeTournament(extra: Partial<Tournament> = {}): Tournament {
  return {
    id: 5, name: 'Julecupen', description: 'Alle mot alle i romjula.', format: 'ROUND_ROBIN', status: 'ACTIVE', category: 'RAPID',
    timeControl: '15+10', rated: true, drawResolution: null, totalRounds: 1, currentRound: 1, startsAt: null, endsAt: null,
    createdAt: '2026-11-01T10:00:00Z',
    participants: [{ id: 1, user: kari, seed: 1, seedRating: 1320 }, { id: 2, user: ola, seed: 2, seedRating: 1250 }],
    rounds: [{ id: 1, bracket: 'MAIN', number: 1, status: 'ACTIVE', pairings: [{ id: 11, board: 1, white: kari, black: ola, bye: false,
      result: null, advancing: null, currentGame: { id: 40, status: 'SCHEDULED', stage: 'MAIN', category: 'RAPID', rated: true, white: kari,
        black: ola, playedAt: '2026-11-20T18:00:00Z' }, games: [] }] }],
    standings: [makeStanding(1, kari, 0, { played: 0 }), makeStanding(2, ola, 0, { played: 0 })],
    bracket: null, champion: null, ...extra,
  };
}

export function makeStats(extra: Partial<PlayerStats> = {}): PlayerStats {
  return {
    user: kari, category: 'RAPID', games: 12, wins: 7, draws: 2, losses: 3,
    asWhite: { games: 6, wins: 4, draws: 1, losses: 1 }, asBlack: { games: 6, wins: 3, draws: 1, losses: 2 },
    currentStreak: { type: 'WIN', length: 3 }, longestWinStreak: 4, recentForm: ['W', 'W', 'W', 'D', 'L'],
    ratings: { BLITZ: { rating: 1210, peak: 1230, ratedGames: 3 }, RAPID: { rating: 1320, peak: 1340, ratedGames: 12 },
      CLASSICAL: { rating: 1200, peak: 1200, ratedGames: 0 } },
    averageAccuracy: 84.2, analysedGames: 4, blundersPerGame: 0.5,
    nemesis: { user: ola, games: 5, score: 1.5 }, favouriteOpponent: { user: per, games: 4, score: 3.5 },
    badges: [{ code: 'FIRST_WIN', title: 'Første seier', description: 'Vant et ratet parti.' }], ...extra,
  };
}

export function makeMatchup(extra: Partial<Matchup> = {}): Matchup {
  return {
    playerA: kari, playerB: ola, category: 'RAPID', ratingA: 1320, ratingB: 1250,
    headToHead: { games: 6, aWins: 4, draws: 1, bWins: 1 }, recentGames: [makeListItem()], formA: ['W', 'D'], formB: ['L', 'D'],
    probabilities: { aWin: 0.52, draw: 0.17, bWin: 0.31 }, stakesA: { onWin: 8, onDraw: -2, onLoss: -12 },
    stakesB: { onWin: 12, onDraw: 2, onLoss: -8 }, ...extra,
  };
}


/** Moves with real FENs and UCI, played through chess.js. */
export function playMoves(...sans: string[]): Move[] {
  const chess = new Chess();
  return sans.map((san, i) => {
    const move = chess.move(san);
    return { ply: i + 1, san: move.san, uci: move.from + move.to + (move.promotion ?? ''), fen: chess.fen() };
  });
}

export const SCHOLARS_MATE = playMoves('e4', 'e5', 'Bc4', 'Nc6', 'Qh5', 'Nf6', 'Qxf7#');

export function makeAnalysis(extra: Partial<SavedAnalysis> = {}): SavedAnalysis {
  const classes = ['BEST', 'BEST', 'GOOD', 'BEST', 'EXCELLENT', 'BLUNDER', 'BEST'];
  const cps = [30, 25, 40, 35, 60, null, null];
  return {
    gameId: 17, engine: 'Stockfish 19 (lite)', depth: 14, whiteAccuracy: 96.4, blackAccuracy: 61.2, whiteAcpl: 8, blackAcpl: 140,
    whiteBrilliancies: 0, blackBrilliancies: 0, whiteInaccuracies: 0, blackInaccuracies: 0, whiteMistakes: 0, blackMistakes: 0,
    whiteBlunders: 0, blackBlunders: 1,
    moves: classes.map((classification, i) => ({ ply: i + 1, cp: cps[i], mate: cps[i] === null ? (i === 5 ? 1 : 0) : null,
      best: i === 5 ? 'g7g6' : null, classification })),
    ...extra,
  };
}
