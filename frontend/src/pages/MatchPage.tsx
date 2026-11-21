import { PlayerClock } from '../components/ViewerClock';
import { CorrectionNote, CorrectionPanel } from '../components/Correction';
import { Chess, type Square } from 'chess.js';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Link, useParams } from 'react-router';
import { Area, AreaChart, ReferenceLine, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts';
import { winPercent } from '../analysis/evaluation';
import { useGameAnalysis, type GameAnalysis } from '../analysis/useGameAnalysis';
import { useLiveEval } from '../analysis/useLiveEval';
import { Board } from '../components/Board';
import { ProblemScreen } from '../components/ErrorScreens';
import { EvalBar } from '../components/EvalBar';
import { MatchupCard } from '../components/MatchupCard';
import { Button, Delta, ErrorNotice, LiveDot, Loading, PlayerLink, SelectField } from '../components/ui';
import { api, ApiError } from '../lib/api';
import { useAuth } from '../lib/auth';
import { capturedPieces, START_FEN } from '../lib/chess';
import { CATEGORY_LABEL, CLASSIFICATION_LABEL, formatDateTime, resultMark, STATUS_LABEL, TERMINATION_LABEL } from '../lib/format';
import { useTopic } from '../lib/live';
import { useApi } from '../lib/useApi';
import type { Game, GameResult, Matchup, MoveResponse, Termination } from '../lib/types';

const CLASS_MARK: Record<string, { mark: string; tone: string }> = {
  BRILLIANT: { mark: '!!', tone: 'text-moss' },
  BEST: { mark: '★', tone: 'text-moss' },
  INACCURACY: { mark: '?!', tone: 'text-walnut' },
  MISTAKE: { mark: '?', tone: 'text-berry' },
  BLUNDER: { mark: '??', tone: 'text-berry' },
};

export default function MatchPage() {
  const { id } = useParams();
  const { user } = useAuth();
  const { data: game, setData: setGame, error, loading, reload } = useApi<Game>(`/api/games/${id}`);
  useTopic<{ type: string; game: Game }>(`/topic/games/${id}`, (message) => message.type === 'game' && setGame(message.game));

  if (error) return <ProblemScreen error={error} />;
  if (loading && !game) return <Loading label="Henter partiet" />;
  if (!game || !user) return null;

  const isPlayer = game.white.id === user.id || game.black.id === user.id;
  const isAdmin = user.role === 'ADMIN';
  const canAct = isPlayer || isAdmin;

  return (
    <div className="flex flex-col gap-8">
      <MatchHeader game={game} />
      {game.status === 'SCHEDULED' && <Scheduled game={game} canAct={canAct} onChange={setGame} />}
      {game.status === 'LIVE' && <Live game={game} canAct={canAct} onChange={setGame} onConflict={reload} />}
      {game.status === 'PENDING' && <Pending game={game} userId={user.id} isAdmin={isAdmin} canAct={canAct} onChange={setGame} />}
      {(game.status === 'FINISHED' || game.status === 'PENDING' || game.status === 'REJECTED' || game.status === 'ABORTED' || game.status === 'ANNULLED') && game.plyCount > 0 && (
        <Recap game={game} canSave={canAct} />
      )}
      {isAdmin && (game.status === 'LIVE' || game.status === 'SCHEDULED') && (
        <FeatureToggle game={game} onChange={setGame} />
      )}
      {isAdmin && game.status === 'FINISHED' && <CorrectionPanel game={game} onChange={setGame} />}
      {game.status !== 'SCHEDULED' && <Preview gameId={game.id} version={`${game.status}|${game.result ?? ''}|${game.correction?.at ?? ''}`} />}
    </div>
  );
}

function MatchHeader({ game }: { game: Game }) {
  const stage = game.stage === 'PLAYOFF' ? 'Omspill (lyn)' : game.stage === 'ARMAGEDDON' ? 'Armageddon' : null;
  return (
    <header>
      <div className="flex flex-wrap items-center gap-3 text-sm text-slate">
        {game.status === 'LIVE' ? <LiveDot /> : <span className="font-semibold">{STATUS_LABEL[game.status]}</span>}
        <span>{CATEGORY_LABEL[game.category]}{game.timeControl ? ` ${game.timeControl}` : ''}</span>
        {!game.rated && <span>Uratet</span>}
        {stage && <span className="font-semibold text-walnut">{stage}</span>}
        <span>{formatDateTime(game.playedAt)}</span>
      </div>
      <h1 className="mt-2 grid grid-cols-[1fr_auto_1fr] items-baseline gap-4 text-2xl font-extrabold text-spruce sm:text-4xl">
        <span className="text-right">
          <PlayerLink player={game.white} />
          <span className="block text-base font-semibold text-slate tabular">
            {game.whiteRatingBefore ?? ''} <Delta value={game.whiteRatingDelta} />
          </span>
        </span>
        <span className="tabular text-walnut">{game.result ? resultMark(game.result) : <span className="text-lg font-semibold sm:text-2xl">mot</span>}</span>
        <span>
          <PlayerLink player={game.black} />
          <span className="block text-base font-semibold text-slate tabular">
            {game.blackRatingBefore ?? ''} <Delta value={game.blackRatingDelta} />
          </span>
        </span>
      </h1>
      {game.termination && game.result && <p className="mt-1 text-center text-slate">Avgjort ved {TERMINATION_LABEL[game.termination]}</p>}
      {game.comment && <p className="mx-auto mt-3 max-w-prose text-center italic text-slate">«{game.comment}»</p>}
      {game.rejectionReason && <p className="mt-3 text-center text-berry">Avvist: {game.rejectionReason}</p>}
      <CorrectionNote game={game} />
    </header>
  );
}

/** The matchup card. Reloads when the game's outcome changes, since a confirmation or a correction moves the ratings it shows. */
function Preview({ gameId, version }: { gameId: number; version: string }) {
  const preview = useApi<Matchup>(`/api/games/${gameId}/preview`);
  const shown = useRef(version);
  const { reload } = preview;
  useEffect(() => {
    if (shown.current === version) return;
    shown.current = version;
    void reload();
  }, [version, reload]);
  return preview.data ? <MatchupCard matchup={preview.data} /> : null;
}

// ---------------------------------------------------------------------------------------------
// Before the game
// ---------------------------------------------------------------------------------------------

function ResultButtons({ onPick, disabled }: { onPick: (result: GameResult) => void; disabled?: boolean }) {
  return (
    <div className="flex flex-wrap gap-2">
      <Button variant="quiet" disabled={disabled} onClick={() => onPick('WHITE_WIN')}>Hvit vant</Button>
      <Button variant="quiet" disabled={disabled} onClick={() => onPick('DRAW')}>Remis</Button>
      <Button variant="quiet" disabled={disabled} onClick={() => onPick('BLACK_WIN')}>Svart vant</Button>
    </div>
  );
}

function Scheduled({ game, canAct, onChange }: { game: Game; canAct: boolean; onChange: (g: Game) => void }) {
  const preview = useApi<Matchup>(`/api/games/${game.id}/preview`);
  const [error, setError] = useState<ApiError | null>(null);
  const [busy, setBusy] = useState(false);

  async function act(path: string, body: object = {}) {
    setBusy(true);
    setError(null);
    try {
      onChange(await api.post<Game>(`/api/games/${game.id}/${path}`, body));
    } catch (e) {
      setError(e as ApiError);
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="grid gap-8 lg:grid-cols-[minmax(0,1fr)_minmax(0,1fr)]">
      {preview.data && <MatchupCard matchup={preview.data} />}
      {canAct ? (
        <section className="flex flex-col gap-5">
          <div>
            <h2 className="text-xl font-extrabold text-spruce">Følg partiet trekk for trekk</h2>
            <p className="mt-1 text-slate">Før inn trekkene på brettet her mens dere spiller, så kan resten av familien følge med.</p>
            <Button variant="primary" className="mt-3" disabled={busy} onClick={() => act('start')}>Start innspilling</Button>
          </div>
          <div>
            <h2 className="text-xl font-extrabold text-spruce">Eller registrer bare resultatet</h2>
            <div className="mt-3"><ResultButtons disabled={busy} onPick={(result) => act('report', { result })} /></div>
          </div>
          <ErrorNotice error={error} />
        </section>
      ) : (
        <p className="text-slate">Partiet er ikke spilt ennå.</p>
      )}
    </div>
  );
}

// ---------------------------------------------------------------------------------------------
// While the game is played
// ---------------------------------------------------------------------------------------------

function Live({ game, canAct, onChange, onConflict }: { game: Game; canAct: boolean; onChange: (g: Game) => void; onConflict: () => void }) {
  const fen = game.currentFen ?? START_FEN;
  const [orientation, setOrientation] = useState<'white' | 'black'>('white');
  const [showEval, setShowEval] = useState(false);
  const [promotion, setPromotion] = useState<{ from: string; to: string } | null>(null);
  const [error, setError] = useState<ApiError | null>(null);
  const [finishResult, setFinishResult] = useState<GameResult | ''>('');
  const [termination, setTermination] = useState<Termination>('RESIGNATION');
  const evaluation = useLiveEval(fen, showEval);
  const captured = capturedPieces(fen);
  const last = game.moves.at(-1);

  const send = useCallback(async (uci: string) => {
    setError(null);
    try {
      await api.post<MoveResponse>(`/api/games/${game.id}/moves`, { move: uci, expectedPly: game.plyCount });
      onChange(await api.get<Game>(`/api/games/${game.id}`));
    } catch (e) {
      const apiError = e as ApiError;
      setError(apiError);
      if (apiError.code === 'CHESS-GAME-005') onConflict();
    }
  }, [game.id, game.plyCount, onChange, onConflict]);

  function onMove(from: string, to: string): boolean {
    if (!canAct) return false;
    const chess = new Chess(fen);
    const piece = chess.get(from as Square);
    if (!piece || piece.color !== chess.turn()) return false;
    const legal = chess.moves({ square: from as Square, verbose: true }).filter((m) => m.to === to);
    if (legal.length === 0) return false;
    if (legal.some((m) => m.promotion)) {
      setPromotion({ from, to });
      return false;
    }
    void send(from + to);
    return true;
  }

  async function finish() {
    if (!finishResult) return;
    setError(null);
    try {
      onChange(await api.post<Game>(`/api/games/${game.id}/finish`, { result: finishResult, termination }));
    } catch (e) {
      setError(e as ApiError);
    }
  }

  async function abort() {
    if (!window.confirm('Avbryte partiet? Det blir ikke registrert noe resultat.')) return;
    try {
      onChange(await api.post<Game>(`/api/games/${game.id}/abort`, {}));
    } catch (e) {
      setError(e as ApiError);
    }
  }

  const arrows = evaluation?.best ? [{ startSquare: evaluation.best.slice(0, 2), endSquare: evaluation.best.slice(2, 4), color: 'rgba(46, 122, 87, 0.75)' }] : [];
  const top = orientation === 'white' ? game.black : game.white;
  const bottom = orientation === 'white' ? game.white : game.black;

  return (
    <div className="grid gap-8 lg:grid-cols-[minmax(0,34rem)_1fr]">
      <div className="flex flex-col gap-2">
        <p className="flex items-center justify-between gap-2 text-sm"><span className="font-semibold">{top.displayName}</span><span className="text-slate">{orientation === 'white' ? captured.byBlack.join(' ') : captured.byWhite.join(' ')}</span><PlayerClock clock={game.clock} side={orientation === 'white' ? 'BLACK' : 'WHITE'} /></p>
        <div className="flex gap-2">
          {showEval && <EvalBar score={evaluation?.score ?? null} orientation={orientation} />}
          <div className="flex-1">
            <Board fen={fen} orientation={orientation} lastMoveUci={last?.uci} arrows={arrows} onMove={canAct ? onMove : undefined} id={`live-${game.id}`} />
          </div>
        </div>
        <p className="flex items-center justify-between gap-2 text-sm"><span className="font-semibold">{bottom.displayName}</span><span className="text-slate">{orientation === 'white' ? captured.byWhite.join(' ') : captured.byBlack.join(' ')}</span><PlayerClock clock={game.clock} side={orientation === 'white' ? 'WHITE' : 'BLACK'} /></p>
        {promotion && (
          <div role="dialog" aria-label="Velg brikke" className="flex items-center gap-2 rounded-md bg-spruce p-3 text-snow">
            <span className="mr-2 text-sm">Forvandle til</span>
            {[['q', '♛ Dronning'], ['r', '♜ Tårn'], ['b', '♝ Løper'], ['n', '♞ Springer']].map(([piece, label]) => (
              <button key={piece} type="button" className="rounded bg-spruce-3 px-2 py-1 text-sm font-semibold hover:bg-birch hover:text-ink"
                onClick={() => { void send(promotion.from + promotion.to + piece); setPromotion(null); }}>{label}</button>
            ))}
            <button type="button" className="ml-auto text-sm underline" onClick={() => setPromotion(null)}>Avbryt</button>
          </div>
        )}
        <div className="mt-2 flex flex-wrap gap-2">
          <Button variant="quiet" onClick={() => setOrientation(orientation === 'white' ? 'black' : 'white')}>Snu brettet</Button>
          <Button variant="quiet" onClick={() => setShowEval(!showEval)}>{showEval ? 'Skjul Stockfish' : 'Vis Stockfish'}</Button>
          {canAct && game.timeControl && (
            <Link to={`/matches/${game.id}/klokke`} className="inline-flex items-center rounded-md bg-spruce px-4 py-2 font-semibold text-snow hover:bg-spruce-2">
              Åpne sjakkur
            </Link>
          )}
        </div>
      </div>
      <div className="flex flex-col gap-6">
        <MoveList game={game} />
        <ErrorNotice error={error} />
        {canAct && (
          <section className="flex flex-col gap-3 border-t border-mist pt-4">
            <h2 className="text-lg font-extrabold text-spruce">Avslutt partiet</h2>
            <p className="text-sm text-slate">Sjakkmatt og patt avslutter partiet av seg selv. Bruk dette ved oppgivelse, tidstap eller enighet om remis.</p>
            <div className="grid gap-3 sm:grid-cols-2">
              <SelectField label="Resultat" value={finishResult} onChange={(e) => setFinishResult(e.target.value as GameResult)}>
                <option value="">Velg</option>
                <option value="WHITE_WIN">Hvit vant</option>
                <option value="DRAW">Remis</option>
                <option value="BLACK_WIN">Svart vant</option>
              </SelectField>
              <SelectField label="Hvordan" value={termination} onChange={(e) => setTermination(e.target.value as Termination)}>
                {(['RESIGNATION', 'TIMEOUT', 'AGREEMENT', 'THREEFOLD_REPETITION', 'FIFTY_MOVE_RULE'] as Termination[]).map((t) => (
                  <option key={t} value={t}>{TERMINATION_LABEL[t]}</option>
                ))}
              </SelectField>
            </div>
            <div className="flex flex-wrap gap-2">
              <Button variant="primary" disabled={!finishResult} onClick={finish}>Registrer resultat</Button>
              <Button variant="danger" onClick={abort}>Avbryt partiet</Button>
            </div>
          </section>
        )}
      </div>
    </div>
  );
}

function FeatureToggle({ game, onChange }: { game: Game; onChange: (g: Game) => void }) {
  return (
    <div>
      <Button variant="quiet" onClick={async () => onChange(await api.post<Game>(`/api/admin/games/${game.id}/feature`, { featured: !game.featured }))}>
        {game.featured ? 'Fjern fra TV' : 'Vis på TV'}
      </Button>
    </div>
  );
}

// ---------------------------------------------------------------------------------------------
// Waiting for confirmation
// ---------------------------------------------------------------------------------------------

function Pending({ game, userId, isAdmin, canAct, onChange }: { game: Game; userId: number; isAdmin: boolean; canAct: boolean; onChange: (g: Game) => void }) {
  const [error, setError] = useState<ApiError | null>(null);
  const reportedByMe = game.registeredBy?.id === userId;
  const canConfirm = canAct && (isAdmin || !reportedByMe);

  async function act(action: 'confirm' | 'reject') {
    setError(null);
    const body = action === 'reject' ? { reason: window.prompt(reportedByMe ? 'Hvorfor trekker du resultatet? (valgfritt)' : 'Hva er feil med resultatet? (valgfritt)') ?? undefined } : {};
    try {
      onChange(await api.post<Game>(`/api/games/${game.id}/${action}`, body));
    } catch (e) {
      setError(e as ApiError);
    }
  }

  return (
    <section className="rounded-md border-l-4 border-berry bg-white px-5 py-4">
      <p className="font-semibold">
        {game.registeredBy ? `${game.registeredBy.displayName} registrerte resultatet.` : 'Resultatet er registrert.'}{' '}
        {reportedByMe ? 'Det teller når motstanderen eller en administrator har bekreftet det.' : 'Det teller når det er bekreftet.'}
      </p>
      {canAct && (
        <div className="mt-3 flex flex-wrap gap-2">
          {canConfirm && <Button variant="primary" onClick={() => act('confirm')}>Bekreft resultatet</Button>}
          <Button variant="quiet" onClick={() => act('reject')}>{reportedByMe ? 'Trekk tilbake' : 'Avvis'}</Button>
        </div>
      )}
      <div className="mt-3"><ErrorNotice error={error} /></div>
    </section>
  );
}

// ---------------------------------------------------------------------------------------------
// After the game
// ---------------------------------------------------------------------------------------------

function MoveList({ game, analysis, current, onSelect }: { game: Game; analysis?: GameAnalysis | null; current?: number; onSelect?: (ply: number) => void }) {
  const rows: { number: number; white?: number; black?: number }[] = [];
  game.moves.forEach((_, index) => {
    const number = Math.floor(index / 2) + 1;
    if (index % 2 === 0) rows.push({ number, white: index });
    else rows[rows.length - 1].black = index;
  });
  if (game.moves.length === 0) return <p className="text-slate">Ingen trekk ennå.</p>;

  const cell = (index: number | undefined) => {
    if (index === undefined) return <span />;
    const move = game.moves[index];
    const classification = analysis?.moves[index]?.classification;
    const mark = classification ? CLASS_MARK[classification] : undefined;
    const active = current === index + 1;
    return (
      <button type="button" onClick={() => onSelect?.(index + 1)} disabled={!onSelect}
        className={`rounded px-2 py-0.5 text-left font-semibold ${active ? 'bg-spruce text-snow' : 'hover:bg-mist'} disabled:cursor-default`}
        aria-label={`${move.san}${classification ? `, ${CLASSIFICATION_LABEL[classification]}` : ''}`}>
        {move.san}{mark && <span className={`ml-0.5 ${active ? '' : mark.tone}`}>{mark.mark}</span>}
      </button>
    );
  };

  return (
    <ol className="grid max-h-[28rem] grid-cols-[2.5rem_1fr_1fr] gap-y-0.5 overflow-y-auto pr-2 tabular" aria-label="Trekk">
      {rows.map((row) => (
        <li key={row.number} className="contents">
          <span className="py-0.5 text-right text-slate">{row.number}.</span>
          {cell(row.white)}
          {cell(row.black)}
        </li>
      ))}
    </ol>
  );
}

function Recap({ game, canSave }: { game: Game; canSave: boolean }) {
  const { analysis, progress, error, run } = useGameAnalysis(game, canSave && game.status === 'FINISHED');
  const [ply, setPly] = useState(game.plyCount);
  const fen = ply === 0 ? START_FEN : game.moves[ply - 1].fen;

  useEffect(() => setPly(game.plyCount), [game.plyCount]);
  useEffect(() => {
    function onKey(event: KeyboardEvent) {
      if ((event.target as HTMLElement)?.tagName === 'INPUT') return;
      if (event.key === 'ArrowLeft') setPly((p) => Math.max(0, p - 1));
      if (event.key === 'ArrowRight') setPly((p) => Math.min(game.plyCount, p + 1));
      if (event.key === 'Home') setPly(0);
      if (event.key === 'End') setPly(game.plyCount);
    }
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [game.plyCount]);

  const moveAnalysis = analysis && ply > 0 ? analysis.moves[ply - 1] : null;
  const score = analysis ? (ply === 0 ? { cp: 0 } : moveAnalysis ? toScore(moveAnalysis) : null) : null;
  const nextBest = analysis && ply < game.plyCount ? analysis.moves[ply]?.best : null;
  const arrows = nextBest ? [{ startSquare: nextBest.slice(0, 2), endSquare: nextBest.slice(2, 4), color: 'rgba(46, 122, 87, 0.75)' }] : [];

  return (
    <section aria-labelledby="gjennomgang" className="grid gap-8 lg:grid-cols-[minmax(0,34rem)_1fr]">
      <div className="flex flex-col gap-3">
        <h2 id="gjennomgang" className="sr-only">Gjennomgang</h2>
        <div className="flex gap-2">
          {analysis && <EvalBar score={score} />}
          <div className="flex-1"><Board fen={fen} lastMoveUci={ply > 0 ? game.moves[ply - 1].uci : undefined} arrows={arrows} id={`recap-${game.id}`} /></div>
        </div>
        <div className="flex items-center justify-between gap-2">
          <div className="flex gap-1">
            <Button variant="quiet" aria-label="Til start" onClick={() => setPly(0)}>|‹</Button>
            <Button variant="quiet" aria-label="Forrige trekk" onClick={() => setPly(Math.max(0, ply - 1))}>‹</Button>
            <Button variant="quiet" aria-label="Neste trekk" onClick={() => setPly(Math.min(game.plyCount, ply + 1))}>›</Button>
            <Button variant="quiet" aria-label="Til slutt" onClick={() => setPly(game.plyCount)}>›|</Button>
          </div>
          <span className="text-sm text-slate tabular">Trekk {Math.ceil(ply / 2)} av {Math.ceil(game.plyCount / 2)}</span>
        </div>
        {moveAnalysis && (
          <p className="text-sm">
            <span className="font-semibold">{game.moves[ply - 1].san}</span> er {CLASSIFICATION_LABEL[moveAnalysis.classification]?.toLowerCase()}.
            {moveAnalysis.best && moveAnalysis.classification !== 'BEST' && moveAnalysis.classification !== 'BRILLIANT' && ` Stockfish foretrakk ${moveAnalysis.best}.`}
          </p>
        )}
      </div>
      <div className="flex flex-col gap-6">
        <MoveList game={game} analysis={analysis} current={ply} onSelect={setPly} />
        {analysis && error && <ErrorNotice error={error} />}
        {analysis ? (
          <AnalysisSummaryView game={game} analysis={analysis} onSelect={setPly} />
        ) : (
          game.status === 'FINISHED' && (
            <div className="border-t border-mist pt-4">
              <h3 className="text-lg font-extrabold text-spruce">Analyse</h3>
              <p className="mt-1 text-slate">Stockfish går gjennom hvert trekk her i nettleseren. Det tar omtrent et halvt minutt.</p>
              {progress === null ? (
                <Button variant="primary" className="mt-3" onClick={run}>Analyser med Stockfish</Button>
              ) : (
                <div className="mt-3" role="progressbar" aria-valuenow={Math.round(progress * 100)} aria-valuemin={0} aria-valuemax={100} aria-label="Analyse">
                  <div className="h-2 overflow-hidden rounded-full bg-mist"><div className="h-full bg-spruce transition-[width]" style={{ width: `${progress * 100}%` }} /></div>
                  <p className="mt-1 text-sm text-slate tabular">{Math.round(progress * 100)} %</p>
                </div>
              )}
              <div className="mt-3"><ErrorNotice error={error} /></div>
            </div>
          )
        )}
      </div>
    </section>
  );
}

function toScore(move: { cp: number | null; mate: number | null }) {
  return move.mate !== null ? { mate: move.mate } : { cp: move.cp ?? 0 };
}

function AnalysisSummaryView({ game, analysis, onSelect }: { game: Game; analysis: GameAnalysis; onSelect: (ply: number) => void }) {
  const data = useMemo(() => [{ ply: 0, white: 50 }, ...analysis.moves.map((m) => ({ ply: m.ply, white: winPercent(toScore(m)) }))], [analysis]);
  const count = (side: 0 | 1, classification: string) =>
    analysis.moves.filter((m, i) => i % 2 === side && m.classification === classification).length;

  // Turning points: the moves where the winning chances swung the most.
  const turning = analysis.moves
    .map((m, i) => ({ ply: m.ply, san: game.moves[i]?.san, classification: m.classification, swing: Math.abs(data[i + 1].white - data[i].white) }))
    .filter((t) => t.swing >= 8)
    .sort((a, b) => b.swing - a.swing)
    .slice(0, 3)
    .sort((a, b) => a.ply - b.ply);

  return (
    <div className="flex flex-col gap-6 border-t border-mist pt-4">
      <div>
        <h3 className="text-lg font-extrabold text-spruce">Stillingen gjennom partiet</h3>
        <div className="mt-2 h-40" aria-label="Graf over vinnersjanser for hvit gjennom partiet">
          <ResponsiveContainer width="100%" height="100%">
            <AreaChart data={data} margin={{ top: 4, right: 4, bottom: 0, left: -28 }}
              onClick={(state) => { const p = Number(state?.activeLabel); if (!Number.isNaN(p)) onSelect(p); }}>
              <defs>
                <linearGradient id="evalFill" x1="0" y1="0" x2="0" y2="1">
                  <stop offset="0%" stopColor="#E6D5B0" /><stop offset="100%" stopColor="#E6D5B0" />
                </linearGradient>
              </defs>
              <XAxis dataKey="ply" hide />
              <YAxis domain={[0, 100]} ticks={[0, 50, 100]} tick={{ fontSize: 11, fill: '#56635d' }} />
              <ReferenceLine y={50} stroke="#56635d" strokeDasharray="3 3" />
              <Tooltip formatter={(value) => [`${Math.round(Number(value))} %`, 'Hvits vinnersjanse']} labelFormatter={(p) => `Halvtrekk ${p}`} />
              <Area type="monotone" dataKey="white" stroke="#6E4B32" strokeWidth={2} fill="url(#evalFill)" baseValue={0} isAnimationActive={false} />
            </AreaChart>
          </ResponsiveContainer>
        </div>
      </div>

      <table className="w-full text-sm tabular">
        <caption className="sr-only">Nøyaktighet</caption>
        <thead>
          <tr className="text-left text-slate"><th className="font-semibold" /><th className="font-semibold">{game.white.displayName}</th><th className="font-semibold">{game.black.displayName}</th></tr>
        </thead>
        <tbody className="divide-y divide-mist">
          <tr><th scope="row" className="py-1.5 text-left font-semibold">Nøyaktighet</th><td className="text-lg font-extrabold">{analysis.whiteAccuracy.toFixed(1)} %</td><td className="text-lg font-extrabold">{analysis.blackAccuracy.toFixed(1)} %</td></tr>
          <tr><th scope="row" className="py-1.5 text-left font-normal text-slate">Snitt tapte centibønder</th><td>{analysis.whiteAcpl}</td><td>{analysis.blackAcpl}</td></tr>
          {(['BRILLIANT', 'INACCURACY', 'MISTAKE', 'BLUNDER'] as const).map((c) => (
            <tr key={c}><th scope="row" className="py-1.5 text-left font-normal text-slate">{CLASSIFICATION_LABEL[c]}</th><td>{count(0, c)}</td><td>{count(1, c)}</td></tr>
          ))}
        </tbody>
      </table>

      {turning.length > 0 && (
        <div>
          <h3 className="text-lg font-extrabold text-spruce">Vendepunkter</h3>
          <ul className="mt-2 flex flex-col gap-1">
            {turning.map((t) => (
              <li key={t.ply}>
                <button type="button" className="text-left hover:underline" onClick={() => onSelect(t.ply)}>
                  <span className="font-semibold tabular">{Math.ceil(t.ply / 2)}{t.ply % 2 === 0 ? '…' : '.'} {t.san}</span>{' '}
                  <span className="text-slate">endret vinnersjansen med {Math.round(t.swing)} prosentpoeng</span>
                  {CLASS_MARK[t.classification] && <span className={`ml-1 ${CLASS_MARK[t.classification].tone}`}>({CLASSIFICATION_LABEL[t.classification].toLowerCase()})</span>}
                </button>
              </li>
            ))}
          </ul>
        </div>
      )}
      <p className="text-xs text-slate">Analysert med Stockfish til dybde {analysis.depth}. <Link to="/games/import" className="underline">Importer flere partier</Link></p>
    </div>
  );
}
