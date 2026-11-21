# API and error reference

The backend is a JSON REST API under `/api`, plus a STOMP WebSocket at `/ws`. The web app is its
only client, but everything here is usable with `curl`.

- [Conventions](#conventions)
- [Endpoints](#endpoints)
- [WebSocket](#websocket)
- [Error format](#error-format)
- [Error catalog](#error-catalog)
- [Client-side codes](#client-side-codes)
- [Troubleshooting](#troubleshooting)

## Conventions

- **Authentication**: `Authorization: Bearer <access token>` from `POST /api/auth/login`. Access tokens last
  15 minutes. The refresh token is an HttpOnly cookie (`sjakkportal_refresh`, path `/api/auth`)
  exchanged at `POST /api/auth/refresh`; each use returns a new access token and rotates the cookie.
- **Roles**:
  - **Public**: no token.
  - **Player**: any logged-in user.
  - **Participant**: one of the game's two players, or an admin.
  - **Admin**: admins only.
- **Trace ids**: send `X-Trace-Id` (8–64 characters `[A-Za-z0-9-]`) to correlate with server logs; otherwise
  the server makes one. It comes back as a response header and in every error.
- **Times** are ISO-8601 UTC (`2026-12-24T18:00:00Z`).
- **Enumerations**:
  - results: `WHITE_WIN`, `BLACK_WIN`, `DRAW`;
  - game statuses: `SCHEDULED`, `LIVE`, `PENDING`, `REJECTED`, `FINISHED`, `ABORTED`, `ANNULLED`;
  - categories: `BLITZ`, `RAPID`, `CLASSICAL`;
  - terminations: `CHECKMATE`, `RESIGNATION`, `TIMEOUT`, `STALEMATE`, `THREEFOLD_REPETITION`, `FIFTY_MOVE_RULE`, `INSUFFICIENT_MATERIAL`, `AGREEMENT`, `FORFEIT`, `UNKNOWN`.
- **Time controls** are written `minutes+increment` (`5+3`, `15+10`, `90+30`) or just `minutes`.
  They decide the rating category; see [ARCHITECTURE.md](ARCHITECTURE.md#elo-ratings).
- **Cross-origin requests** are accepted only from `SJAKKPORTAL_ALLOWED_ORIGINS`. A browser request
  carrying another `Origin` is refused with `CHESS-AUTH-003`.

### Example

```bash
API=https://sjakkportal.vercel.app     # or http://localhost:8080
TOKEN=$(curl -s -H 'Content-Type: application/json' \
  -d '{"username":"kari","password":"…"}' $API/api/auth/login | jq -r .accessToken)

curl -s -H "Authorization: Bearer $TOKEN" "$API/api/leaderboard?category=BLITZ"

curl -s -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -H "Idempotency-Key: $(uuidgen)" \
  -d '{"whiteId":2,"blackId":3,"result":"DRAW","timeControl":"5+3"}' $API/api/games
```

## Endpoints

### Authentication

| Method | Path | Role | Body / parameters | Notes |
|--------|------|------|-------------------|-------|
| POST | `/api/auth/register` | Public | `inviteCode`, `username` (3–32: a–z 0–9 `.` `_` `-`), `displayName` (≤ 64), `password` | Creates a player and logs in. Rate-limited per address |
| POST | `/api/auth/login` | Public | `username`, `password` | Returns `{accessToken, expiresIn, user}` and sets the refresh cookie. Rate-limited per username and address |
| POST | `/api/auth/refresh` | Cookie | | Rotates the refresh cookie, returns a new session |
| POST | `/api/auth/logout` | Cookie | | Revokes this refresh token and clears the cookie. 204 |
| POST | `/api/auth/change-password` | Player | `currentPassword`, `newPassword` | Signs the user out everywhere (all refresh tokens revoked, access tokens invalidated, cookie cleared); log in again with the new password. 204 |
| POST | `/api/auth/reset-password` | Public | `token`, `newPassword` | Uses a reset link made by an admin (24 h, single use). Rate-limited per address |

### Users and statistics

| Method | Path | Role | Notes |
|--------|------|------|-------|
| GET | `/api/users/me` | Player | The logged-in user, including `mustChangePassword` |
| PUT | `/api/users/me/accounts` | Player | `lichessUsername`, `chessComUsername` (2–50 characters or empty). Used to match imported games |
| GET | `/api/users` | Player | Everyone, with ratings |
| GET | `/api/users/{id}` | Player | One profile |
| GET | `/api/users/{id}/stats?category=` | Player | Record, accuracy, streaks, nemesis, favourite opponent, badges |
| GET | `/api/users/{id}/rating-history?category=` | Player | Rating after each rated game |
| GET | `/api/users/{id}/games?limit=&before=` | Player | Newest first; `limit` 1–100 (default 20); `before` is a game id for paging |
| GET | `/api/leaderboard?category=` | Player | Ranking for one category |
| GET | `/api/matchups?a=&b=&category=` | Player | Head-to-head record and the Elo forecast `{aWin, draw, bWin}` |

### Games

| Method | Path | Role | Body / parameters | Notes |
|--------|------|------|-------------------|-------|
| GET | `/api/games?limit=&before=` | Player | | All games, newest first |
| POST | `/api/games` | Player (one of the players) or Admin | `whiteId`, `blackId`, `result`, optional `pgn` (≤ 200 000), `termination`, `playedAt`, `rated`, `comment`, `category`, `timeControl` | PENDING when a player registers, FINISHED when an admin does. Optional `Idempotency-Key` header (UUID). Rate-limited per user |
| GET | `/api/games/{id}` | Player | | Full game with moves, players, rating changes |
| GET | `/api/games/{id}/preview` | Player | | What each player stands to win, draw or lose |
| GET | `/api/games/pending` | Player | | Games waiting for *my* confirmation |
| POST | `/api/games/{id}/confirm` | Opponent or Admin | | PENDING → FINISHED; ratings move |
| POST | `/api/games/{id}/reject` | Participant | `reason` (optional) | PENDING → REJECTED. The reporter can use it to withdraw |
| POST | `/api/games/live` | Player (one of the players) or Admin | `whiteId`, `blackId`, `rated`, `category`, `timeControl` | Starts move-by-move entry (LIVE) |
| POST | `/api/games/{id}/moves` | Participant | `move` (SAN `Nf3` or UCI `g1f3`), `expectedPly` | Validated server-side; mate or stalemate ends the game |
| POST | `/api/games/{id}/finish` | Participant | `result`, `termination` | LIVE → PENDING (player) or FINISHED (admin) |
| PUT | `/api/games/{id}/clock` | Participant | `whiteMs`, `blackMs` (0–86 400 000), `running` (`WHITE`, `BLACK` or null), `sequence` | The phone running the chess clock reports its state on every press. LIVE games with a time control only (`CHESS-GAME-003`, `CHESS-GAME-009`). A sequence not newer than the stored one is refused (`CHESS-GAME-005`). Returns the clock; viewers get it in the game view |
| POST | `/api/games/{id}/abort` | Participant | | LIVE → ABORTED, unrated |
| POST | `/api/games/{id}/start` | Participant | | SCHEDULED tournament game → LIVE |
| POST | `/api/games/{id}/report` | Participant | `result`, optional `pgn`, `termination` | Result for a SCHEDULED tournament game |
| GET | `/api/games/{id}/analysis` | Player | | Saved Stockfish analysis; 404 when there is none |
| PUT | `/api/games/{id}/analysis` | Participant | `engine`, `depth`, accuracies, ACPL, `moves[]` | Validated against the game; one analysis per game, a later save replaces it |
| POST | `/api/games/import` | Player | `url` (Lichess/Chess.com) or `pgn` | Both players must be linked family members. FINISHED and unrated |

### Tournaments

| Method | Path | Role | Notes |
|--------|------|------|-------|
| GET | `/api/tournaments` | Player | All tournaments |
| GET | `/api/tournaments/{id}` | Player | Participants, rounds, pairings, standings or bracket |
| POST | `/api/tournaments` | Admin | `name`, `description`, `format` (`ROUND_ROBIN`, `DOUBLE_ROUND_ROBIN`, `SWISS`, `SINGLE_ELIMINATION`, `DOUBLE_ELIMINATION`, `ARENA`), `category`, `timeControl`, `rated`, `drawResolution` (knockouts only: `REPLAY`, `BLITZ_PLAYOFF`, `ARMAGEDDON`), `rounds` (Swiss, 1–30), `startsAt`, `endsAt` (arena) |
| POST | `/api/tournaments/{id}/participants` | Admin | `userId`; adds anyone, including managed players |
| DELETE | `/api/tournaments/{id}/participants/{userId}` | Admin | Before the start |
| POST | `/api/tournaments/{id}/join` | Player | Sign up while in DRAFT |
| POST | `/api/tournaments/{id}/leave` | Player | Withdraw while in DRAFT |
| POST | `/api/tournaments/{id}/start` | Admin | DRAFT → ACTIVE; creates round 1 or the bracket |
| POST | `/api/tournaments/{id}/finish` | Admin | Ends an ACTIVE arena (other formats finish by themselves after the last round) |
| POST | `/api/tournaments/{id}/cancel` | Admin | → CANCELLED; its scheduled and live games are aborted |
| POST | `/api/tournaments/{id}/arena-games` | One of the two players, or Admin | `whiteId`, `blackId`, `result`, `pgn`, `termination`; only while the arena is ACTIVE. Confirmed like any other game |

### Admin

| Method | Path | Notes |
|--------|------|-------|
| GET | `/api/admin/invites` | All invite codes with uses |
| POST | `/api/admin/invites` | `code` (optional; generated `XXXX-XXXX-XXXX-XXXX` otherwise), `maxUses`, `expiresInHours` or `expiresAt`, `note` |
| DELETE | `/api/admin/invites/{id}` | Revokes a code |
| POST | `/api/admin/users/{id}/disable` | Shuts the account out at once: login refused (`CHESS-AUTH-006`, shown only after a correct password), access tokens and sessions ended. Not for your own account (`CHESS-USER-002`). Games and ratings stay |
| POST | `/api/admin/users/{id}/enable` | Lets a disabled account log in again |
| POST | `/api/admin/users/{id}/password-reset` | `mode`: `TEMPORARY_PASSWORD` (returns a one-off password; user must change it) or `RESET_LINK` (returns a single-use token valid for 24 hours; the admin page turns it into a `/reset-password?token=…` link) |
| POST | `/api/admin/players` | `username`, `displayName`. A managed player without login, for children |
| GET | `/api/admin/games/pending` | Every unconfirmed game |
| POST | `/api/admin/games/{id}/correct` | `result`, optional `termination`, `reason` (required, ≤ 300). Changes a FINISHED game's result and replays every rating in its category (see [ARCHITECTURE.md](ARCHITECTURE.md#result-corrections)). Refused for knockout games (`CHESS-GAME-010`) and when the moves prove another result (`CHESS-PGN-004`) |
| POST | `/api/admin/games/{id}/annul` | `reason` (required). FINISHED → ANNULLED: out of ratings, standings and statistics, still visible with the reason. Refused for knockout and paired round-robin/Swiss games (`CHESS-GAME-010`) |
| POST | `/api/admin/games/{id}/feature` | `featured`: show this game on TV |
| GET | `/api/admin/diagnostics/request` | How the server sees this request: connection address, forwarding headers, the address used for rate limits |

### Other

| Method | Path | Role | Notes |
|--------|------|------|-------|
| GET | `/api/tv` | Player | What TV mode should show now |
| GET | `/api/ping` | Public | 204, no database access. Used while waking a sleeping server |
| GET | `/actuator/health` | Public | `{"status":"UP"}` including the database check. Render's health check |

## WebSocket

Connect a STOMP client to `wss://<backend>/ws` (plain WebSocket, no SockJS). Send the access token in the
CONNECT frame header: `Authorization: Bearer <token>`. The handshake is accepted only from allowed origins.

| Topic | Payload | Sent when |
|-------|---------|-----------|
| `/topic/games/{id}` | The full game view (same as `GET /api/games/{id}`) | Any change to that game: move, clock press, finish, confirmation, rejection, abort, featuring, correction |
| `/topic/tv` | The TV view (same as `GET /api/tv`) | The game TV should show changes, or that game changes |
| `/topic/tournaments/{id}` | The tournament view | Results, new rounds, bracket progress |

Messages are sent only after the database transaction commits, in order per game. Heartbeats are 10 s in each direction.

## Error format

Every error is an [RFC 9457](https://www.rfc-editor.org/rfc/rfc9457) problem document with content type
`application/problem+json`:

```json
{
  "type": "urn:sjakkportal:error:CHESS-GAME-008",
  "title": "The player who registered a result cannot confirm it",
  "status": 403,
  "detail": "Motstanderen din eller en administrator må bekrefte partiet.",
  "instance": "/api/games/17/confirm",
  "code": "CHESS-GAME-008",
  "timestamp": "2026-12-24T18:03:11.402Z",
  "traceId": "web-6f1c2a9e-0b7d-4c4e-9a51-2f0f3d1c8b77"
}
```

| Field | Meaning |
|-------|---------|
| `code` | Stable catalog code; switch on this, not on `status` or text |
| `title` | Short English summary for developers |
| `detail` | Norwegian message, safe to show to users as is |
| `traceId` | Find this in the backend log for the full story |
| `errors` | Only for `CHESS-GEN-001`: `[{ "field": "username", "message": "…" }]` |

- Rate-limited responses (429) also carry `Retry-After` in seconds.
- Responses never include stack traces, SQL or class names.
- Codes are never renumbered or reused; new ones are appended.

## Error catalog

All server-side codes, generated from
[`ErrorCode.java`](../backend/src/main/java/no/sjakkportal/common/error/ErrorCode.java). A test
(`DocumentationTest`) fails if a code is missing here or its HTTP status differs.

### Authentication and authorisation

| Code | HTTP | Meaning | Message shown (bokmål) | Cause and fix |
|------|------|---------|------------------------|---------------|
| `CHESS-AUTH-001` | 401 | Invalid credentials | Feil brukernavn eller passord. | Wrong username or password. Usernames are case-insensitive. Repeated failures lead to `CHESS-RATE-001`. |
| `CHESS-AUTH-002` | 401 | Access token invalid or expired | Innloggingen din er utløpt. Logg inn på nytt. | The 15-minute access token expired or was revoked. The web client refreshes automatically; API clients call `POST /api/auth/refresh`. |
| `CHESS-AUTH-003` | 403 | Access denied | Du har ikke tilgang til dette. | The endpoint needs the admin role, or the user is not allowed to see this resource. |
| `CHESS-AUTH-004` | 403 | Password change required | Du må velge et nytt passord før du kan fortsette. | The account has a temporary password (bootstrap admin, admin reset). Only `/api/auth/change-password`, `/api/users/me` and logout work until it is changed. |
| `CHESS-AUTH-005` | 401 | Refresh token invalid, expired or reused | Økten din er ikke lenger gyldig. Logg inn på nytt. | The refresh cookie is missing, expired (30 days), revoked by logout or password change, or was used twice (all sessions of that login are then revoked). Log in again. If it happens right after every login, see "Logged out on every reload" below. |
| `CHESS-AUTH-006` | 403 | Account disabled | Kontoen din er deaktivert. Ta kontakt med en administrator. | An admin disabled the account (Admin → Spillere og passord → Deaktiver). An admin can enable it again. |
| `CHESS-AUTH-007` | 422 | Password does not meet the policy | Passordet må ha minst 8 tegn og kan ikke være likt brukernavnet. | 8 characters minimum, at most 72 bytes (BCrypt's limit), not equal to the username. |
| `CHESS-AUTH-008` | 401 | Authentication required | Du må logge inn for å fortsette. | No `Authorization: Bearer` header on an endpoint that needs one. |
| `CHESS-AUTH-009` | 409 | Username already taken | Brukernavnet er allerede i bruk. | Pick another username (comparison is case-insensitive). |

### Invite codes

| Code | HTTP | Meaning | Message shown (bokmål) | Cause and fix |
|------|------|---------|------------------------|---------------|
| `CHESS-INVITE-001` | 404 | Invite code not found | Invitasjonskoden finnes ikke. | Code mistyped, or never existed. Codes are case-insensitive. |
| `CHESS-INVITE-002` | 410 | Invite code expired | Invitasjonskoden er utløpt. | Create a new invite in Admin → Invitasjoner. |
| `CHESS-INVITE-003` | 410 | Invite code has no uses left | Invitasjonskoden er brukt opp. | All uses are taken. Create a new invite or one with more uses. |
| `CHESS-INVITE-004` | 410 | Invite code revoked | Invitasjonskoden er trukket tilbake. | An admin revoked the code. |
| `CHESS-INVITE-005` | 409 | Invite code already exists | Det finnes allerede en invitasjon med denne koden. | A custom code must be unique. |
| `CHESS-INVITE-006` | 422 | Invalid invite configuration | Ugyldig oppsett for invitasjonskoden. | Uses must be ≥ 1 and the expiry in the future. |

### Password reset

| Code | HTTP | Meaning | Message shown (bokmål) | Cause and fix |
|------|------|---------|------------------------|---------------|
| `CHESS-RESET-001` | 400 | Password reset token invalid | Lenken for tilbakestilling av passord er ugyldig. | The reset link was altered or belongs to another environment. |
| `CHESS-RESET-002` | 410 | Password reset token expired | Lenken for tilbakestilling av passord er utløpt. | Reset links last 24 hours. Ask an admin for a new one. |
| `CHESS-RESET-003` | 410 | Password reset token already used | Lenken for tilbakestilling av passord er allerede brukt. | Each reset link works once. |

### PGN and FEN

| Code | HTTP | Meaning | Message shown (bokmål) | Cause and fix |
|------|------|---------|------------------------|---------------|
| `CHESS-PGN-001` | 422 | PGN could not be parsed | PGN-teksten kunne ikke leses. | Not valid PGN. Paste the full export including the move list; headers alone are not enough. |
| `CHESS-PGN-002` | 422 | PGN contains an illegal move | PGN-teksten inneholder et ulovlig trekk. | The response says at which move. Often a variant game (Chess960) or a move list from another notation. |
| `CHESS-PGN-003` | 413 | PGN too large | PGN-teksten er for lang. | PGN is limited to 200 000 characters. |
| `CHESS-PGN-004` | 422 | Result contradicts the PGN | Resultatet stemmer ikke med trekkene i PGN-teksten. | The selected result contradicts the moves (e.g. "white won" but the PGN ends in mate by Black). |
| `CHESS-PGN-005` | 422 | PGN must contain exactly one game | PGN-teksten må inneholde nøyaktig ett parti. | Paste one game at a time. |
| `CHESS-FEN-001` | 422 | FEN position invalid | FEN-stillingen er ugyldig. | A position (FEN) sent by the client is malformed. |

### Games

| Code | HTTP | Meaning | Message shown (bokmål) | Cause and fix |
|------|------|---------|------------------------|---------------|
| `CHESS-GAME-001` | 404 | Game not found | Partiet finnes ikke. | Wrong id, or the game was deleted. |
| `CHESS-GAME-002` | 422 | A player cannot play themselves | Hvit og svart må være to forskjellige spillere. | White and Black must be different players. |
| `CHESS-GAME-003` | 409 | Game is not in a state that allows this | Dette kan ikke gjøres med partiet slik det står nå. | The action does not fit the status, e.g. confirming a game that is already FINISHED, or entering moves into a PENDING game. Reload the page. |
| `CHESS-GAME-004` | 422 | Illegal move | Trekket er ikke lovlig i denne stillingen. | The move is illegal in the current position (or the position changed; reload). |
| `CHESS-GAME-005` | 409 | Game was modified concurrently | Partiet ble endret av noen andre samtidig. Last inn på nytt og prøv igjen. | Someone else changed the game at the same moment (two phones entering moves). Reload and try again. |
| `CHESS-GAME-006` | 409 | Game already imported | Dette partiet er allerede importert. | This Lichess/Chess.com game was imported before; the response links to it. |
| `CHESS-GAME-007` | 403 | Only participants or admins may modify a game | Bare spillerne i partiet eller en administrator kan gjøre dette. | Only the two players or an admin may change a game. |
| `CHESS-GAME-008` | 403 | The player who registered a result cannot confirm it | Motstanderen din eller en administrator må bekrefte partiet. | The player who registered the result cannot confirm it; the opponent or an admin must. |
| `CHESS-GAME-009` | 422 | Time control invalid or inconsistent with the category | Tidskontrollen er ugyldig eller passer ikke med valgt kategori. | Time control must look like `5+3`, `15+10` or `10` (minutes ≤ 600, increment ≤ 180), and agree with the chosen category. |
| `CHESS-GAME-010` | 409 | This result cannot be corrected | Resultatet kan ikke endres her: det avgjorde en utslagskamp, eller partiet hører til en turneringsrunde. | Knockout results that decided a match cannot change, because the bracket has moved on. Paired round-robin and Swiss games can be corrected but not annulled. If a knockout result really was wrong, cancel the tournament and start a new one. |

### Analysis

| Code | HTTP | Meaning | Message shown (bokmål) | Cause and fix |
|------|------|---------|------------------------|---------------|
| `CHESS-ANALYSIS-001` | 422 | Analysis payload invalid | Analysen kunne ikke lagres fordi dataene er ugyldige. | The browser sent an analysis that does not match the game (move count, value ranges). |
| `CHESS-ANALYSIS-002` | 409 | Game has no moves to analyse or is not finished | Partiet må være ferdig og ha trekk før analysen kan lagres. | Only finished games with moves can be analysed. |

### Tournaments

| Code | HTTP | Meaning | Message shown (bokmål) | Cause and fix |
|------|------|---------|------------------------|---------------|
| `CHESS-TOURN-001` | 404 | Tournament not found | Turneringen finnes ikke. | Wrong id. |
| `CHESS-TOURN-002` | 409 | Tournament is not in a state that allows this | Dette kan ikke gjøres med turneringen slik den står nå. | E.g. joining a tournament that has started, or starting one twice. |
| `CHESS-TOURN-003` | 422 | Not enough players | Turneringen har for få spillere. | A tournament needs at least two players to start. |
| `CHESS-TOURN-004` | 409 | Current round still has unfinished games | Runden er ikke ferdig. Alle partier må ha et resultat først. | Every game of the round needs a confirmed result before the round can close. |
| `CHESS-TOURN-005` | 409 | Player already registered | Spilleren er allerede påmeldt. | The player is already signed up. |
| `CHESS-TOURN-006` | 409 | Pairing not found or result does not fit it | Resultatet passer ikke med oppsettet i turneringen. | A reported result does not belong to this pairing, or the pairing is already decided. |
| `CHESS-TOURN-007` | 409 | Arena window is not open | Arenaen er ikke åpen akkurat nå. | Arena games can only be registered between the arena's start and end time. |
| `CHESS-TOURN-008` | 422 | Invalid tournament configuration | Turneringsoppsettet er ugyldig. | A knockout needs a draw rule (and other formats must not have one); an arena needs a start and an end time; Swiss rounds must be 1–30; the end must be after the start. |
| `CHESS-TOURN-009` | 409 | Player is not registered in this tournament | Spilleren er ikke påmeldt denne turneringen. | The player is not in this tournament. |

### Users, import and rate limiting

| Code | HTTP | Meaning | Message shown (bokmål) | Cause and fix |
|------|------|---------|------------------------|---------------|
| `CHESS-USER-001` | 404 | User not found | Brukeren finnes ikke. | Wrong id. |
| `CHESS-USER-002` | 409 | This account cannot be disabled | Du kan ikke deaktivere din egen konto. | An admin tried to disable their own account. Another admin must do it, so the site always has an admin who can log in. |
| `CHESS-IMPORT-001` | 422 | Unsupported import link | Lenken gjenkjennes ikke som et parti fra Chess.com eller Lichess. | Only Lichess game links (`lichess.org/<8 or 12 characters>`) and Chess.com game links (`chess.com/game/live/<number>`, `/game/daily/…`, `/live/game/…`) are recognised. |
| `CHESS-IMPORT-002` | 502 | Could not fetch the game from the external site | Partiet kunne ikke hentes fra den eksterne tjenesten. Prøv igjen senere, eller lim inn PGN. | The external site did not answer, rate-limited us, or changed its format (Chess.com's endpoint is unofficial). Paste the PGN instead. |
| `CHESS-IMPORT-003` | 422 | Both players must be linked family members | Begge spillerne må være familiemedlemmer med koblet Chess.com- eller Lichess-brukernavn. | Both online usernames in the game must be linked to family members in Innstillinger. |
| `CHESS-RATE-001` | 429 | Too many requests | For mange forsøk. Vent litt og prøv igjen. | Too many attempts. The `Retry-After` header says how many seconds to wait. The limits are listed in SECURITY.md. |

### Generic

| Code | HTTP | Meaning | Message shown (bokmål) | Cause and fix |
|------|------|---------|------------------------|---------------|
| `CHESS-GEN-001` | 400 | Request validation failed | Noen av feltene er ugyldige. | One or more fields are invalid; `errors[]` lists them. |
| `CHESS-GEN-002` | 404 | Resource not found | Fant ikke det du lette etter. | Unknown URL or resource. |
| `CHESS-GEN-003` | 405 | Method not allowed | Denne handlingen støttes ikke her. | Wrong HTTP method for this URL. |
| `CHESS-GEN-004` | 400 | Malformed request | Forespørselen kunne ikke leses. | The body is not valid JSON, a value has the wrong type, or the request is otherwise unreadable. |
| `CHESS-GEN-005` | 409 | Conflicting concurrent update | Noen andre endret dette samtidig. Last inn på nytt og prøv igjen. | A concurrent update was detected. Reload and repeat. |
| `CHESS-GEN-999` | 500 | Unexpected server error | Noe gikk galt hos oss. Prøv igjen, og gi feilkoden til en administrator hvis det fortsetter. | A bug. Find the `traceId` in the backend log (Render → Logs) to see the stack trace. |

## Client-side codes

These come from the web app itself (`frontend/src/lib/api.ts`), for failures where the server's
problem document never arrived.

| Code | Shown when | What to do |
|------|------------|------------|
| `CHESS-CLIENT-001` | No answer at all: offline, DNS failure, CORS refusal, or the server did not wake within 3 minutes | Check the connection. If only the app fails, check the backend in Render (it may be failing its health check) |
| `CHESS-CLIENT-002` | A response that is not a problem document, e.g. a proxy error page | Usually a platform problem (Vercel or Render). The status code in the message helps; check the Render logs |
| `CHESS-CLIENT-003` | The page crashed while rendering (React error boundary) | Reload. If it repeats, the browser console has the stack trace |
| `CHESS-CLIENT-004` | A write (save, confirm, move) hit a sleeping server. Reads are repeated automatically; writes are not, so nothing is ever saved twice | Press the button again; the server is awake now |

## Troubleshooting

**"Serveren våkner…" banner for up to a minute.** The free Render instance sleeps after 15 minutes
without traffic. This is expected; see [DEPLOYMENT.md](DEPLOYMENT.md#cold-starts). The app continues by itself.

**Logged out on every reload (`CHESS-AUTH-005` right after login).**
- The refresh cookie is not coming back. Check that the browser reaches the API on the *same site* as the page.
- In production, `/api` must go through the Vercel rewrite, and `VITE_API_URL` must be empty.
- Calling `onrender.com` directly from a `vercel.app` page makes the cookie third-party. Safari always
  blocks it, and other browsers increasingly do.

**Every request fails with `CHESS-AUTH-003` from the deployed site, but works locally.** The page's
origin is not in `SJAKKPORTAL_ALLOWED_ORIGINS`. It must match exactly: scheme, host, no trailing
slash (`https://sjakkportal.vercel.app`). Add custom domains and preview URLs you use.

**Live updates do not arrive (moves appear only after reload).**
1. Check `VITE_WS_URL` points at `wss://<service>.onrender.com/ws`, and that the frontend was
   rebuilt afterwards (Vite bakes it in at build time).
2. Check that the page's origin is in `SJAKKPORTAL_ALLOWED_ORIGINS`; the handshake checks it too.
3. Check the CSP's `connect-src` in `vercel.json` allows that host. The browser console says
   "Refused to connect" if not.

**Stockfish does not start ("Analyser" spins forever).**
- Open the browser console. A CSP message about `wasm-unsafe-eval` or `worker-src` means `vercel.json` headers were changed.
- A 404 for `/stockfish/stockfish-19-lite-single.js` means the engine was not copied at build. Vercel must run `npm ci`, which triggers `postinstall`.
- Very old browsers without WebAssembly are not supported.

**`CHESS-RATE-001` when several family members log in at once.**
- Without `SJAKKPORTAL_CLIENT_IP_HEADER`, everyone behind the proxy shares one address bucket: 20 login attempts a minute in total.
- That is plenty for a family. If it is not, see
  [SECURITY.md](SECURITY.md#client-address-behind-proxies) before changing it.

**`CHESS-GEN-999`.** A bug. Copy the trace id from the error message, open Render → the service →
Logs, and search for it; the line with the stack trace has the same id in square brackets.

**Import fails with `CHESS-IMPORT-002`.** Lichess or Chess.com did not answer as expected. Chess.com in
particular has no official single-game API; the importer uses the game page's data feed, which can
change. Paste the PGN instead (Chess.com: Share → PGN).
