# Sjakkportal

Family chess portal: game tracking, Stockfish analysis, TV mode, tournaments and player statistics.
The user interface is in Norwegian (bokmål); code and documentation are in English.

> **Status: Phase 1 of 4 (tests and database schema).** The full README, architecture diagram and
> `docs/` folder arrive in Phase 4.

## Phases

| Phase | Scope | State |
|-------|-------|-------|
| 1 | Database schema, error-code catalog, failing test suite | done |
| 2 | Backend services and security (turn the suite green) | next |
| 3 | React frontend, Stockfish WASM, TV mode | |
| 4 | Documentation and deployment guide | |

## Running the backend tests

Requires Java 21 and Docker.

```bash
docker compose up -d          # PostgreSQL on localhost:5432
cd backend
./gradlew test                # stops the build on failures
./gradlew test -PreportOnly   # runs everything and only reports
```

The HTML report is written to `backend/build/reports/tests/test/index.html`.

During Phase 1 the suite is intentionally red: the schema and error-catalog tests pass, and the
feature tests fail until Phase 2 implements the code behind them.
